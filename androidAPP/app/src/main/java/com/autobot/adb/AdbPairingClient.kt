package com.autobot.adb

import android.annotation.TargetApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.tntok.autobot.adb.PairingAuthCtx
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.RSAKeyGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509ExtendedKeyManager
import timber.log.Timber
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * ADB 无线配对客户端
 * 
 * 实现完整的 TLS 1.3 + SPAKE2+ + 对等信息交换流程
 */
@TargetApi(30)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val servicePort: Int? = null,
    private val onKeyPairGenerated: ((java.security.KeyPair) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AdbPairingClient"
        
        // TLS 密钥导出标签
        private const val ADB_LABEL = "adb-label\u0000"
        private const val EXPORT_KEY_SIZE = 64
        
        // 数据包头大小
        private const val HEADER_SIZE = 6
        
        // 数据包类型
        private const val TYPE_SPAKE2_MSG: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        
        // 对等信息大小
        private const val PEER_INFO_SIZE = 8192
    }
    
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null
    private var authCtx: PairingAuthCtx? = null
    
    /**
     * 执行配对
     */
    suspend fun pair(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("========================================")
            Timber.i("开始 ADB 无线配对")
            Timber.i("目标: $host:$port")
            Timber.i("配对码: $pairingCode")
            Timber.i("========================================")
            
            // 1. 建立 TLS 连接
            Timber.i("")
            Timber.i("步骤 1/3: 建立 TLS 1.3 连接...")
            if (!setupTlsConnection()) {
                Timber.e("✗ TLS 连接失败")
                return@withContext false
            }
            Timber.i("✓ TLS 连接成功")
            
            // 2. SPAKE2 密钥交换
            Timber.i("")
            Timber.i("步骤 2/3: SPAKE2+ 密钥交换...")
            if (!spake2KeyExchange()) {
                Timber.e("✗ SPAKE2 密钥交换失败")
                return@withContext false
            }
            Timber.i("✓ SPAKE2 密钥交换成功")
            
            // 3. 交换对等信息
            Timber.i("")
            Timber.i("步骤 3/3: 交换对等信息（RSA 公钥）...")
            if (!exchangePeerInfo()) {
                Timber.e("✗ 对等信息交换失败")
                return@withContext false
            }
            Timber.i("✓ 对等信息交换成功")
            
            Timber.i("")
            Timber.i("========================================")
            Timber.i("🎉 ADB 配对成功！")
            Timber.i("========================================")
            true
        } catch (e: Exception) {
            Timber.e(e, "========================================")
            Timber.e("✗ 配对失败")
            Timber.e("========================================")
            false
        } finally {
            cleanup()
        }
    }
    
    /**
     * 建立 TLS 1.3 连接并导出密钥材料
     */
    private fun setupTlsConnection(): Boolean {
        try {
            // 创建普通 TCP 连接
            Timber.d("  创建 TCP 连接...")
            val tcpSocket = Socket(host, port)
            tcpSocket.tcpNoDelay = true
            Timber.d("  ✓ TCP 连接已建立")
            
            // 创建 TLS 1.3 socket
            Timber.d("  创建 TLS 1.3 socket...")
            val sslContext = createSSLContext()
            val sslSocket = sslContext.socketFactory.createSocket(
                tcpSocket, host, port, true
            ) as SSLSocket
            
            // 启动 TLS 握手
            Timber.d("  启动 TLS 握手...")
            sslSocket.startHandshake()
            Timber.d("  ✓ TLS 握手完成")
            
            inputStream = DataInputStream(sslSocket.inputStream)
            outputStream = DataOutputStream(sslSocket.outputStream)
            
            // 导出密钥材料
            Timber.d("  导出 TLS 密钥材料...")
            val pairingCodeBytes = pairingCode.toByteArray(StandardCharsets.UTF_8)
            
            // 使用反射调用 Conscrypt.exportKeyingMaterial
            // Hidden API Bypass 已在 Application 中初始化，可以绕过运行时限制
            val exportedKey = try {
                val conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt")
                val exportMethod = conscryptClass.getMethod(
                            "exportKeyingMaterial",
                    javax.net.ssl.SSLSocket::class.java,
                            String::class.java,
                            ByteArray::class.java,
                            Int::class.javaPrimitiveType
                        )
                exportMethod.invoke(null, sslSocket, ADB_LABEL, null, EXPORT_KEY_SIZE) as? ByteArray
            } catch (e: NoSuchMethodException) {
                Timber.e(e, "  ✗ 未找到 exportKeyingMaterial 方法")
                null
            } catch (e: Exception) {
                Timber.e(e, "  ✗ 调用 Conscrypt.exportKeyingMaterial 失败: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            
            if (exportedKey == null) {
                Timber.e("  ✗ 导出密钥材料失败")
                return false
            }
            Timber.d("  ✓ 导出密钥材料成功: ${exportedKey.size} bytes")
            
            // 组合配对码和导出的密钥
            val password = ByteArray(pairingCodeBytes.size + exportedKey.size)
            System.arraycopy(pairingCodeBytes, 0, password, 0, pairingCodeBytes.size)
            System.arraycopy(exportedKey, 0, password, pairingCodeBytes.size, exportedKey.size)
            
            // 创建 SPAKE2 认证上下文
            Timber.d("  创建 SPAKE2 上下文...")
            authCtx = PairingAuthCtx.create(true, password) ?: run {
                Timber.e("  ✗ 创建 SPAKE2 上下文失败")
                return false
            }
            Timber.d("  ✓ SPAKE2 上下文创建成功")
            
            return true
        } catch (e: Exception) {
            Timber.e(e, "  TLS 连接失败")
            return false
        }
    }
    
    /**
     * SPAKE2 密钥交换
     */
    private fun spake2KeyExchange(): Boolean {
        val ctx = authCtx ?: return false
        
        try {
            // 发送我们的 SPAKE2 消息
            val ourMsg = ctx.message
            Timber.d("  发送 SPAKE2 消息: ${ourMsg.size} bytes")
            sendPacket(TYPE_SPAKE2_MSG, ourMsg)
            Timber.d("  ✓ SPAKE2 消息已发送")
            
            // 接收对方的 SPAKE2 消息
            Timber.d("  等待对方的 SPAKE2 消息...")
            val theirHeader = receiveHeader() ?: run {
                Timber.e("  ✗ 接收数据包头失败")
                return false
            }
            
            if (theirHeader.type != TYPE_SPAKE2_MSG) {
                Timber.e("  ✗ 期望 SPAKE2_MSG，但收到类型: ${theirHeader.type}")
                return false
            }
            
            val theirMsg = ByteArray(theirHeader.payloadSize)
            inputStream?.readFully(theirMsg)
            Timber.d("  ✓ 接收对方 SPAKE2 消息: ${theirMsg.size} bytes")
            
            // 初始化加密密钥
            Timber.d("  初始化加密密钥...")
            val success = ctx.initCipher(theirMsg)
            if (!success) {
                Timber.e("  ✗ 初始化密钥失败")
                return false
            }
            Timber.d("  ✓ 加密密钥已初始化")
            
            return true
        } catch (e: Exception) {
            Timber.e(e, "  SPAKE2 密钥交换失败")
            return false
        }
    }
    
    /**
     * 交换对等信息（包含 RSA 公钥）
     */
    private fun exchangePeerInfo(): Boolean {
        val ctx = authCtx ?: return false
        
        try {
            // 生成 RSA 密钥对
            Timber.d("  生成 RSA 2048 位密钥对...")
            val keyPairGen = KeyPairGenerator.getInstance("RSA")
            keyPairGen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val keyPair = keyPairGen.generateKeyPair()
            val publicKey = keyPair.public as RSAPublicKey
            Timber.d("  ✓ RSA 密钥对生成成功")
            
            // 通知密钥对已生成
            onKeyPairGenerated?.invoke(keyPair)
            
            // 构造对等信息
            Timber.d("  构造对等信息...")
            val peerInfo = ByteBuffer.allocate(PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
            
            // 写入类型
            peerInfo.put(0x00.toByte())
            
            // 编码 RSA 公钥为 ADB 格式
            val adbPublicKey = encodeRSAPublicKeyToADB(publicKey)
            peerInfo.put(adbPublicKey)
            
            // 填充剩余部分
            while (peerInfo.position() < PEER_INFO_SIZE) {
                peerInfo.put(0x00.toByte())
            }
            
            Timber.d("  ✓ 对等信息已构造")
            
            // 加密对等信息
            Timber.d("  加密对等信息...")
            val encryptedPeerInfo = ctx.encrypt(peerInfo.array()) ?: run {
                Timber.e("  ✗ 加密对等信息失败")
                return false
            }
            Timber.d("  ✓ 对等信息已加密: ${encryptedPeerInfo.size} bytes")
            
            // 发送加密的对等信息
            Timber.d("  发送对等信息...")
            sendPacket(TYPE_PEER_INFO, encryptedPeerInfo)
            Timber.d("  ✓ 对等信息已发送")
            
            // 接收对方的对等信息
            Timber.d("  等待对方的对等信息...")
            val theirHeader = receiveHeader() ?: run {
                Timber.e("  ✗ 接收数据包头失败")
                return false
            }
            
            if (theirHeader.type != TYPE_PEER_INFO) {
                Timber.e("  ✗ 期望 PEER_INFO，但收到类型: ${theirHeader.type}")
                return false
            }
            
            val theirEncryptedPeerInfo = ByteArray(theirHeader.payloadSize)
            inputStream?.readFully(theirEncryptedPeerInfo)
            Timber.d("  ✓ 接收对方对等信息: ${theirEncryptedPeerInfo.size} bytes")
            
            // 解密对方的对等信息
            Timber.d("  解密对方的对等信息...")
            val theirPeerInfo = ctx.decrypt(theirEncryptedPeerInfo) ?: run {
                Timber.e("  ✗ 解密对等信息失败")
                return false
            }
            Timber.d("  ✓ 对方的对等信息解密成功")
            
            return true
        } catch (e: Exception) {
            Timber.e(e, "  对等信息交换失败")
            return false
        }
    }
    
    /**
     * 发送数据包
     */
    private fun sendPacket(type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.put(1.toByte())
        header.put(type)
        header.putInt(payload.size)
        
        outputStream?.write(header.array())
        outputStream?.write(payload)
        outputStream?.flush()
    }
    
    /**
     * 接收数据包头
     */
    private fun receiveHeader(): PacketHeader? {
        return try {
            val headerBytes = ByteArray(HEADER_SIZE)
            inputStream?.readFully(headerBytes)
            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
            
            PacketHeader(
                version = buffer.get(),
                type = buffer.get(),
                payloadSize = buffer.int
            )
        } catch (e: Exception) {
            Timber.e(e, "  接收数据包头失败")
            null
        }
    }
    
    /**
     * 创建 SSL 上下文
     */
    private fun createSSLContext(): SSLContext {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        
        // 创建信任所有证书的 TrustManager
        val trustManager = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
        }
        
        // 生成客户端证书和密钥（ADB 要求客户端证书）
        val keyManager = try {
            generateClientKeyManager()
        } catch (e: Exception) {
            Timber.e(e, "生成客户端证书失败")
            null
        }
        
        sslContext.init(
            keyManager?.let { arrayOf(it) },
            arrayOf(trustManager),
            SecureRandom()
        )
        return sslContext
    }
    
    /**
     * 生成客户端 KeyManager（带 RSA 密钥和自签名证书）
     */
    private fun generateClientKeyManager(): javax.net.ssl.X509ExtendedKeyManager {
        Timber.d("  生成客户端 RSA 密钥和证书...")
        
        // 生成 RSA 密钥对
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val keyPair = keyPairGen.generateKeyPair()
        val privateKey = keyPair.private as RSAPrivateKey
        val publicKey = keyPair.public as RSAPublicKey
        
        // 生成自签名 X.509 证书（使用 BouncyCastle）
        val cert = generateSelfSignedCertificate(keyPair)
        
        Timber.d("  ✓ 客户端证书生成完成")
        
        // 创建 KeyManager
        return object : javax.net.ssl.X509ExtendedKeyManager() {
            override fun chooseClientAlias(
                keyType: Array<String>,
                issuers: Array<java.security.Principal>?,
                socket: Socket?
            ): String? {
                return if (keyType.contains("RSA")) "adb-client" else null
            }
            
            override fun chooseServerAlias(
                keyType: String,
                issuers: Array<java.security.Principal>?,
                socket: Socket?
            ): String? = null
            
            override fun getCertificateChain(alias: String): Array<X509Certificate>? {
                return if (alias == "adb-client") arrayOf(cert) else null
            }
            
            override fun getClientAliases(
                keyType: String,
                issuers: Array<java.security.Principal>?
            ): Array<String>? = null
            
            override fun getServerAliases(
                keyType: String,
                issuers: Array<java.security.Principal>?
            ): Array<String>? = null
            
            override fun getPrivateKey(alias: String): java.security.PrivateKey? {
                return if (alias == "adb-client") privateKey else null
            }
        }
    }
    
    /**
     * 生成自签名 X.509 证书（使用 BouncyCastle）
     */
    private fun generateSelfSignedCertificate(keyPair: java.security.KeyPair): X509Certificate {
        try {
            // 使用 BouncyCastle 生成 X.509 证书
            // CN 设置为包名
            val issuer = org.bouncycastle.asn1.x500.X500Name("CN=com.autobot")
            val subject = org.bouncycastle.asn1.x500.X500Name("CN=com.autobot")
            val serial = java.math.BigInteger.ONE
            val notBefore = java.util.Date()
            val notAfter = java.util.Date(notBefore.time + 365L * 24 * 60 * 60 * 1000)
            
            val certGen = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                issuer,
                serial,
                notBefore,
                notAfter,
                subject,
                keyPair.public
            )
            
            val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.private)
            
            val certHolder = certGen.build(signer)
            val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
                .getCertificate(certHolder)
            
            return cert
        } catch (e: Exception) {
            Timber.e(e, "  ✗ 生成证书失败")
            throw e
        }
    }
    
    /**
     * 将 RSA 公钥编码为 ADB 格式
     */
    private fun encodeRSAPublicKeyToADB(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        
        val RSANUMWORDS = 64
        val RSANUMBYTES = RSANUMWORDS * 4
        
        val binaryKey = ByteBuffer.allocate(4 + 4 + RSANUMBYTES + RSANUMBYTES + 4)
        binaryKey.order(ByteOrder.LITTLE_ENDIAN)
        
        // 写入 RSANUMWORDS
        binaryKey.putInt(RSANUMWORDS)
        
        // 计算 n0inv
        val n0 = modulus.mod(BigInteger.valueOf(1L shl 32))
        val n0inv = n0.modInverse(BigInteger.valueOf(1L shl 32)).negate()
        binaryKey.putInt(n0inv.toInt())
        
        // 写入模数 n
        val modulusBytes = modulus.toByteArray()
        val modulusPadded = ByteArray(RSANUMBYTES)
        val startOffset = Math.max(0, modulusBytes.size - RSANUMBYTES)
        val copyLength = Math.min(modulusBytes.size, RSANUMBYTES)
        
        for (i in 0 until copyLength) {
            val srcIdx = startOffset + copyLength - 1 - i
            if (srcIdx >= 0 && srcIdx < modulusBytes.size) {
                modulusPadded[i] = modulusBytes[srcIdx]
            }
        }
        binaryKey.put(modulusPadded)
        
        // 计算 rr = R^2 mod n
        val r = BigInteger.ONE.shiftLeft(modulus.bitLength())
        val rr = r.multiply(r).mod(modulus)
        
        val rrBytes = rr.toByteArray()
        val rrPadded = ByteArray(RSANUMBYTES)
        val rrStartOffset = Math.max(0, rrBytes.size - RSANUMBYTES)
        val rrCopyLength = Math.min(rrBytes.size, RSANUMBYTES)
        
        for (i in 0 until rrCopyLength) {
            val srcIdx = rrStartOffset + rrCopyLength - 1 - i
            if (srcIdx >= 0 && srcIdx < rrBytes.size) {
                rrPadded[i] = rrBytes[srcIdx]
            }
        }
        binaryKey.put(rrPadded)
        
        // 写入指数
        binaryKey.putInt(exponent.toInt())
        
        // Base64 编码
        val base64Key = android.util.Base64.encode(binaryKey.array(), android.util.Base64.NO_WRAP)
        
        // 附加包名标识符
        val packageName = "com.autobot"
        val identifier = " $packageName\u0000"
        val identifierBytes = identifier.toByteArray(StandardCharsets.UTF_8)
        
        // 组合
        val result = ByteArray(base64Key.size + identifierBytes.size)
        System.arraycopy(base64Key, 0, result, 0, base64Key.size)
        System.arraycopy(identifierBytes, 0, result, base64Key.size, identifierBytes.size)
        
        return result
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        try {
            authCtx?.destroy()
            inputStream?.close()
            outputStream?.close()
        } catch (e: Exception) {
            Timber.e(e, "清理资源失败")
        }
    }
    
    /**
     * 数据包头
     */
    private data class PacketHeader(
        val version: Byte,
        val type: Byte,
        val payloadSize: Int
    )
}

