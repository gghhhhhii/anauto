package com.autobot.adb

import android.content.Context
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509ExtendedKeyManager
import java.security.cert.X509Certificate
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import kotlin.concurrent.thread

/**
 * ADB 连接管理器
 * 维护持久的 ADB 连接，使系统显示"已连接到无线调试"
 */
class AdbConnectionManager {
    
    companion object {
        private const val HOST = "127.0.0.1"
        private const val LEGACY_ADB_PORT = 5555  // 传统无线ADB端口
        
        @Volatile
        private var instance: AdbConnectionManager? = null
        
        fun getInstance(): AdbConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: AdbConnectionManager().also { instance = it }
            }
        }
    }
    
    @Volatile
    private var isConnected = false
    
    @Volatile
    private var socket: Socket? = null  // 可能是普通 Socket 或 SSLSocket
    
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null
    
    private var heartbeatThread: Thread? = null
    
    /**
     * 建立持久 ADB 连接（使用 TLS）
     * 参考 ADB持久连接说明.md 的实现
     * 
     * 根据文档，Connect Service 可能使用：
     * 1. 固定端口 5555（文档所述）
     * 2. 动态端口（配对时发现）
     */
    fun connect(context: Context): Boolean {
        if (isConnected) {
            Timber.d( "已经连接，跳过")
            return true
        }
        
        // 优先使用保存的 connect 端口（配对时发现的动态端口）
        var connectPort = AdbConfig.getConnectPort(context)
        
        if (connectPort == null) {
            // 如果没有保存的端口，尝试使用文档中提到的固定端口 5555
            Timber.w( "Connect 端口未保存，尝试使用固定端口 5555")
            connectPort = 5555
        } else {
            Timber.i( "使用保存的 Connect 端口: $connectPort")
        }
        
        return try {
            Timber.i( "========================================")
            Timber.i( "建立持久 ADB TLS 连接...")
            Timber.i( "========================================")
            
            // 1. 创建普通 Socket 连接
            Timber.d( "步骤 1: 连接到 $HOST:$connectPort")
            var plainSocket: Socket? = null
            
            try {
                plainSocket = Socket()
                plainSocket.soTimeout = 2000  // 设置读取超时 2 秒
                plainSocket.connect(java.net.InetSocketAddress(HOST, connectPort), 5000)
                Timber.d( "  ✓ Socket 连接成功")
            } catch (e: Exception) {
                // 连接失败，可能端口已变化或 TLS 会话过期
                if (e is java.net.ConnectException || e.message?.contains("ECONNREFUSED") == true) {
                    Timber.w( "⚠ 端口 $connectPort 连接失败")
                    Timber.i( "尝试通过 mDNS 重新发现 Connect 端口...")
                    
                    // 清除旧端口
                    AdbConfig.saveConnectPort(context, -1)
                    
                    // 尝试重新发现端口
                    val newPort = discoverConnectPort(context)
                    if (newPort != null) {
                        if (newPort == connectPort) {
                            Timber.i( "✓ 确认 Connect 端口仍为: $newPort（可能 TLS 会话过期）")
                        } else {
                            Timber.i( "✓ 发现新的 Connect 端口: $newPort（旧端口: $connectPort）")
                        }
                        
                        AdbConfig.saveConnectPort(context, newPort)
                        connectPort = newPort
                        
                        // 用发现的端口重试
                        Timber.i( "重新建立 TLS 连接...")
                        plainSocket?.close()  // 关闭旧 socket
                        plainSocket = Socket()
                        plainSocket.soTimeout = 2000
                        plainSocket.connect(java.net.InetSocketAddress(HOST, connectPort), 5000)
                        Timber.d( "  ✓ Socket 连接成功")
                    } else {
                        throw Exception("无法发现 Connect 端口，请确认：\n1. 无线调试已开启\n2. 设备已配对")
                    }
                } else {
                    throw e
                }
            }
            
            // 2. 客户端发送 STLS 消息（告诉服务器要升级到 TLS）
            Timber.d( "步骤 2: 发送 STLS 消息")
            try {
                val plainOutput = DataOutputStream(plainSocket!!.getOutputStream())
                sendSTLSMessage(plainOutput)
                Timber.d( "  ✓ STLS 消息已发送")
            } catch (e: Exception) {
                Timber.e(e, "  ✗ 发送 STLS 消息失败")
                throw e
            }
            
            // 3. 升级到 TLS
            Timber.d( "步骤 3: 升级到 TLS 连接")
            val sslSocket = upgradeToTLS(plainSocket!!, context)
            socket = sslSocket
            
            outputStream = DataOutputStream(sslSocket.getOutputStream())
            inputStream = DataInputStream(sslSocket.getInputStream())
            
            Timber.d( "  ✓ TLS 连接升级成功")
            
            // 4. 在 TLS 上发送 CONNECT 消息
            Timber.d( "步骤 4: 在 TLS 上发送 CONNECT 消息")
            sendConnectMessage()
            
            // 5. 接收响应
            Timber.d( "步骤 5: 等待服务器响应")
            val connectResponse = receiveMessage()
            
            if (connectResponse != null) {
                val command = String(connectResponse, 0, 4, StandardCharsets.US_ASCII)
                if (command == "CNXN") {
                    Timber.i( "  ✓ ADB TLS 握手成功！")
                    isConnected = true
                    
                    // 7. 启动心跳线程
                    startHeartbeat()
                    
                    Timber.i( "========================================")
                    Timber.i( "✅ ADB TLS 连接已建立！")
                    Timber.i( "   连接状态: ${isConnected}")
                    Timber.i( "   Socket 状态: connected=${socket?.isConnected}, closed=${socket?.isClosed}")
                    Timber.i( "   系统应显示'已连接到无线调试'通知")
                    Timber.i( "   已配对设备应显示'当前已连接'状态")
                    Timber.i( "========================================")
                    
                    true
                } else {
                    Timber.w( "  ✗ 服务器响应异常: $command")
                    Timber.w( "   期望: CNXN, 实际: $command")
                    disconnect()
                    false
                }
            } else {
                Timber.w( "  ✗ 未收到服务器响应")
                Timber.w( "   可能是连接超时或服务器关闭了连接")
                disconnect()
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "TLS 连接失败")
            Timber.e("  错误类型: ${e.javaClass.simpleName}")
            Timber.e("  错误信息: ${e.message}")
            disconnect()
            
            // 回退：尝试传统无线ADB（端口5555，无TLS）
            Timber.i( "========================================")
            Timber.i( "尝试传统无线ADB连接（端口5555）...")
            Timber.i( "========================================")
            
            return connectLegacyAdb(context)
        }
    }
    
    /**
     * 连接到传统无线ADB（端口5555，无TLS）
     * 这是通过 `adb tcpip 5555` 启用的无线ADB
     */
    private fun connectLegacyAdb(context: Context): Boolean {
        return try {
            Timber.d( "步骤 1: 连接到 $HOST:$LEGACY_ADB_PORT")
            
            val plainSocket = Socket()
            plainSocket.soTimeout = 2000
            plainSocket.connect(java.net.InetSocketAddress(HOST, LEGACY_ADB_PORT), 5000)
            
            Timber.d( "  ✓ Socket 连接成功（传统ADB）")
            
            socket = plainSocket
            outputStream = DataOutputStream(plainSocket.getOutputStream())
            inputStream = DataInputStream(plainSocket.getInputStream())
            
            // 步骤 2: 发送 CONNECT 消息（不需要STLS升级）
            Timber.d( "步骤 2: 发送 CONNECT 消息")
            sendConnectMessage()
            
            // 步骤 3: 接收响应
            Timber.d( "步骤 3: 等待服务器响应")
            val connectResponse = receiveMessage()
            
            if (connectResponse != null) {
                val command = String(connectResponse, 0, 4, StandardCharsets.US_ASCII)
                if (command == "CNXN") {
                    Timber.i( "  ✓ ADB 握手成功（传统ADB）！")
                    isConnected = true
                    
                    // 启动心跳线程
                    startHeartbeat()
                    
                    Timber.i( "========================================")
                    Timber.i( "✅ 传统 ADB 连接已建立（端口5555）！")
                    Timber.i( "   连接状态: ${isConnected}")
                    Timber.i( "   模式: 传统无线ADB（非TLS）")
                    Timber.i( "========================================")
                    
                    true
                } else {
                    Timber.w( "  ✗ 服务器响应异常: $command")
                    disconnect()
                    false
                }
            } else {
                Timber.w( "  ✗ 未收到服务器响应")
                disconnect()
                false
            }
        } catch (e: Exception) {
            Timber.e( "传统ADB连接失败", e)
            disconnect()
            false
        }
    }
    
    /**
     * 发送 STLS 消息（客户端请求升级到 TLS）
     */
    private fun sendSTLSMessage(output: DataOutputStream) {
        // ADB STLS 消息格式：24字节头
        val message = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        
        // Command: STLS
        message.put("STLS".toByteArray(StandardCharsets.US_ASCII))
        
        // Version: 0x01000000
        message.putInt(0x01000000)
        
        // Max data: 0
        message.putInt(0)
        
        // Data length: 0
        message.putInt(0)
        
        // Data checksum: 0
        message.putInt(0)
        
        // Magic: STLS ^ 0xffffffff
        val stlsInt = ByteBuffer.wrap("STLS".toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        message.putInt(stlsInt xor 0xffffffff.toInt())
        
        // 发送
        output.write(message.array())
        output.flush()
        
        Timber.d( "  STLS 消息已发送")
    }
    
    /**
     * 升级到 TLS 连接
     */
    private fun upgradeToTLS(plainSocket: Socket, context: Context): SSLSocket {
        // 创建信任所有证书的 TrustManager（ADB 使用自签名证书）
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        
        // 生成客户端证书和密钥（使用配对时保存的密钥对）
        Timber.d( "  生成客户端证书...")
        val (keyManager, clientCert) = generateClientCertificate(context)
        
        // 验证 KeyManager
        val keyManagers = arrayOf(keyManager)
        Timber.d( "  KeyManager 数量: ${keyManagers.size}")
        Timber.d( "  证书 Subject: ${clientCert.subjectDN}")
        Timber.d( "  证书 Issuer: ${clientCert.issuerDN}")
        
        // 创建 SSLContext（使用客户端证书）
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers, trustAllCerts, java.security.SecureRandom())
        Timber.d( "  SSLContext 已初始化")
        
        // 创建 SSLSocketFactory
        val sslSocketFactory = sslContext.socketFactory
        
        // 创建 SSLSocket（从现有 Socket）
        val sslSocket = sslSocketFactory.createSocket(
            plainSocket,
            plainSocket.inetAddress.hostAddress,
            plainSocket.port,
            true
        ) as SSLSocket
        
        // 启用 TLS 1.3（如果支持）
        sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        
        // 重要：启用客户端认证
        // 虽然 KeyManager 已配置，但确保 SSL 参数正确
        val sslParams = sslSocket.sslParameters
        // SSL 参数会在握手时自动使用 KeyManager
        
        Timber.d( "  SSL 协议: ${sslSocket.enabledProtocols.joinToString()}")
        Timber.d( "  开始 TLS 握手（使用客户端证书）...")
        
        try {
            sslSocket.startHandshake()
            Timber.d( "  ✓ TLS 握手完成")
            
            // 验证握手后的状态
            val session = sslSocket.session
            Timber.d( "  TLS 会话协议: ${session.protocol}")
            Timber.d( "  TLS 会话密码套件: ${session.cipherSuite}")
            
            // 检查本地证书（应该包含我们生成的证书）
            val localCerts = session.localCertificates
            if (localCerts != null && localCerts.isNotEmpty()) {
                Timber.d( "  ✓ 本地证书数量: ${localCerts.size}")
                localCerts.forEachIndexed { index, cert ->
                    Timber.d( "    证书 $index: ${(cert as X509Certificate).subjectDN}")
                }
            } else {
                Timber.w( "  ⚠️ 未找到本地证书！")
            }
        } catch (e: Exception) {
            Timber.e( "  ✗ TLS 握手失败", e)
            throw e
        }
        
        return sslSocket
    }
    
    /**
     * 生成客户端证书（用于 TLS 客户端认证）
     * 使用与参考应用相同的方式：直接实现 X509ExtendedKeyManager
     * 优先使用配对时保存的密钥对
     */
    private fun generateClientCertificate(context: Context): Pair<X509KeyManager, X509Certificate> {
        try {
            // 1. 尝试加载配对时保存的密钥对
            Timber.d( "    步骤 1: 尝试加载配对时保存的密钥对...")
            val savedKeyPair = AdbKeyManager.loadKeyPair(context)
            
            val keyPair: java.security.KeyPair = if (savedKeyPair != null) {
                Timber.d( "    ✓ 使用配对时保存的密钥对")
                savedKeyPair
            } else {
                Timber.w( "    ⚠️ 未找到保存的密钥对，生成新的密钥对")
                // 如果没有保存的密钥对，生成新的（但这会导致服务器验证失败）
                val keyPairGen = KeyPairGenerator.getInstance("RSA")
                keyPairGen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                keyPairGen.generateKeyPair()
            }
            
            val privateKey = keyPair.private as RSAPrivateKey
            val publicKey = keyPair.public as RSAPublicKey
            Timber.d( "    ✓ RSA 密钥对已准备")
            
            Timber.d( "    步骤 2: 生成自签名证书...")
            // 2. 使用 BouncyCastle 生成自签名证书（与配对时使用的相同逻辑）
            val cert = generateSelfSignedCertificate(keyPair)
            Timber.d( "    ✓ 证书已生成: ${cert.subjectDN}")
            
            Timber.d( "    步骤 3: 创建 X509ExtendedKeyManager...")
            // 3. 直接实现 X509ExtendedKeyManager（与参考应用相同的方式）
            val keyManager = object : X509ExtendedKeyManager() {
                override fun chooseClientAlias(
                    keyType: Array<String>,
                    issuers: Array<java.security.Principal>?,
                    socket: Socket?
                ): String? {
                    val result = if (keyType.contains("RSA")) "adb-client" else null
                    Timber.d( "    chooseClientAlias: keyType=${keyType.joinToString()}, result=$result")
                    return result
                }
                
                override fun chooseServerAlias(
                    keyType: String,
                    issuers: Array<java.security.Principal>?,
                    socket: Socket?
                ): String? = null
                
                override fun getCertificateChain(alias: String): Array<X509Certificate>? {
                    val result = if (alias == "adb-client") arrayOf(cert) else null
                    Timber.d( "    getCertificateChain: alias=$alias, result=${result?.size ?: 0} certs")
                    return result
                }
                
                override fun getClientAliases(
                    keyType: String,
                    issuers: Array<java.security.Principal>?
                ): Array<String>? {
                    val result = if (keyType == "RSA") arrayOf("adb-client") else null
                    Timber.d( "    getClientAliases: keyType=$keyType, result=${result?.joinToString() ?: "null"}")
                    return result
                }
                
                override fun getServerAliases(
                    keyType: String,
                    issuers: Array<java.security.Principal>?
                ): Array<String>? = null
                
                override fun getPrivateKey(alias: String): java.security.PrivateKey? {
                    val result = if (alias == "adb-client") privateKey else null
                    Timber.d( "    getPrivateKey: alias=$alias, result=${if (result != null) "privateKey" else "null"}")
                    return result
                }
            }
            
            Timber.d( "    ✓ X509ExtendedKeyManager 已创建")
            Timber.d( "  ✓ 客户端证书已生成并配置")
            
            return Pair(keyManager, cert)
        } catch (e: Exception) {
            Timber.e( "生成客户端证书失败", e)
            throw RuntimeException("无法生成客户端证书", e)
        }
    }
    
    /**
     * 生成自签名证书（使用 BouncyCastle，与配对时使用的相同逻辑）
     */
    private fun generateSelfSignedCertificate(keyPair: java.security.KeyPair): X509Certificate {
        try {
            // 使用 BouncyCastle 生成 X.509 证书
            // CN 设置为包名，系统会从证书中提取显示名称
            val issuer = org.bouncycastle.asn1.x500.X500Name("CN=com.autobot.server")
            val subject = org.bouncycastle.asn1.x500.X500Name("CN=com.autobot.server")
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
            Timber.e( "生成证书失败", e)
            throw e
        }
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        Timber.i( "断开 ADB 连接...")
        
        isConnected = false
        
        // 停止心跳线程
        heartbeatThread?.interrupt()
        heartbeatThread = null
        
        // 关闭流和 Socket
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Timber.e( "关闭连接异常", e)
        }
        
        outputStream = null
        inputStream = null
        socket = null
        
        Timber.i( "ADB 连接已断开")
    }
    
    /**
     * 发送 CONNECT 消息
     */
    private fun sendConnectMessage() {
        val output = outputStream ?: return
        
        // System identity（必须以 "host::" 开头）
        val identity = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send\u0000"
        val identityBytes = identity.toByteArray(StandardCharsets.US_ASCII)
        
        // ADB CONNECT 消息格式：24字节头 + 数据
        val message = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        
        // Command: CNXN (0x4e584e43)
        message.put("CNXN".toByteArray(StandardCharsets.US_ASCII))
        
        // Version: 0x01000001
        message.putInt(0x01000001)
        
        // Max data: 1MB
        message.putInt(1024 * 1024)
        
        // Data length
        message.putInt(identityBytes.size)
        
        // Data checksum (简单求和)
        var checksum = 0
        for (b in identityBytes) {
            checksum += b.toInt() and 0xFF
        }
        message.putInt(checksum)
        
        // Magic (command ^ 0xffffffff)
        val commandInt = ByteBuffer.wrap("CNXN".toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        message.putInt(commandInt xor 0xffffffff.toInt())
        
        // 发送消息头
        output.write(message.array())
        // 发送数据
        output.write(identityBytes)
        output.flush()
        
        Timber.d( "  ✓ CONNECT 消息已发送 (${identityBytes.size} bytes data)")
    }
    
    /**
     * 接收消息
     */
    private fun receiveMessage(): ByteArray? {
        return try {
            val input = inputStream ?: return null
            
            // 设置超时
            socket?.soTimeout = 5000
            
            // 读取消息头（24 字节）
            val header = ByteArray(24)
            input.readFully(header)
            
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            
            // 解析消息头
            val commandBytes = ByteArray(4)
            buffer.get(commandBytes)
            val command = String(commandBytes, StandardCharsets.US_ASCII)
            
            val arg0 = buffer.int  // Version
            val arg1 = buffer.int  // Max data
            val dataLength = buffer.int
            val dataChecksum = buffer.int
            val magic = buffer.int
            
            Timber.d( "  ✓ 收到响应: $command")
            Timber.d( "     arg0: 0x${arg0.toString(16)}")
            Timber.d( "     arg1: $arg1")
            Timber.d( "     DataLen: $dataLength")
            
            // ✅ 读取数据部分并返回完整消息（头部 + 数据）
            if (dataLength > 0 && dataLength < 8192) {
                val data = ByteArray(dataLength)
                input.readFully(data)
                val dataString = String(data, StandardCharsets.US_ASCII).trim('\u0000')
                Timber.d( "  ✓ 数据: $dataString")
                
                // ✅ 返回完整消息（头部 + 数据）
                val fullMessage = ByteArray(24 + dataLength)
                System.arraycopy(header, 0, fullMessage, 0, 24)
                System.arraycopy(data, 0, fullMessage, 24, dataLength)
                return fullMessage
            }
            
            // 如果没有数据部分，只返回头部
            header
        } catch (e: java.net.SocketTimeoutException) {
            Timber.d( "  等待响应超时（这可能是正常的）")
            null
        } catch (e: java.io.EOFException) {
            Timber.w( "  服务器关闭连接（可能配对协议不完整）")
            null
        } catch (e: Exception) {
            Timber.e( "接收消息失败", e)
            null
        }
    }
    
    /**
     * 启动心跳线程
     */
    private fun startHeartbeat() {
        heartbeatThread = thread(start = true, name = "ADB-Heartbeat") {
            Timber.i( "心跳线程已启动，将每30秒检查一次连接状态")
            
            while (isConnected && !Thread.currentThread().isInterrupted) {
                try {
                    // 每 30 秒检查一次连接状态
                    Thread.sleep(30000)
                    
                    if (!isConnected) {
                        Timber.w( "心跳检查：连接状态为 false，退出心跳线程")
                        break
                    }
                    
                    // 检查连接是否还活着
                    val socketConnected = socket?.isConnected == true
                    val socketClosed = socket?.isClosed == true
                    
                    if (socketConnected && !socketClosed) {
                        Timber.d( "心跳检查：连接正常 (${System.currentTimeMillis()})")
                    } else {
                        Timber.w( "心跳检查：连接已断开 (connected=$socketConnected, closed=$socketClosed)")
                        isConnected = false
                        break
                    }
                } catch (e: InterruptedException) {
                    Timber.d( "心跳线程被中断")
                    break
                } catch (e: Exception) {
                    Timber.e( "心跳检查异常", e)
                    isConnected = false
                    break
                }
            }
            
            Timber.i( "心跳线程已停止")
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return isConnected && socket?.isConnected == true && socket?.isClosed == false
    }
    
    /**
     * 获取连接信息
     */
    fun getConnectionInfo(context: Context): String {
        return if (isConnected()) {
            val port = AdbConfig.getConnectPort(context) ?: "unknown"
            "已连接到 $HOST:$port"
        } else {
            "未连接"
        }
    }
    
    // Local ID 计数器（确保唯一性）
    private var nextLocalId = 1
    
    /**
     * 通过 ADB 连接执行 Shell 命令
     * @param command Shell 命令（不需要 "shell:" 前缀）
     * @return 命令输出或 null 如果执行失败
     * 
     * ⚠️ 重要：此方法必须串行执行，已加锁
     */
    @Synchronized
    fun executeShellCommand(command: String): String? {
        if (!isConnected()) {
            Timber.w( "ADB 未连接，无法执行命令: $command")
            return null
        }
        
        return try {
            Timber.d( "执行 Shell 命令: $command")
            
            // ✅ 使用递增的 Local ID（确保唯一性）
            val localId = nextLocalId++
            if (nextLocalId > Int.MAX_VALUE - 1000) {
                nextLocalId = 1  // 重置（避免溢出）
            }
            
            // 1. 发送 OPEN 消息
            val shellCommand = "shell:$command"
            sendOpenMessage(localId, shellCommand)
            
            // ✅ 添加小延迟，确保消息发送完成
            Thread.sleep(10)
            
            // 2. 等待 OKAY 响应（可能需要跳过残留数据）
            var response: ByteArray? = null
            var responseCommand: String
            var retryCount = 0
            val maxRetries = 3
            
            // ✅ 循环读取，直到收到 OKAY 或超时
            while (retryCount < maxRetries) {
                response = receiveMessage()
                if (response == null || response.size < 24) {
                    Timber.w( "未收到响应或响应太短，重试... ($retryCount/$maxRetries)")
                    Thread.sleep(50)
                    retryCount++
                    continue
                }
                
                responseCommand = String(response, 0, 4, StandardCharsets.US_ASCII)
                Timber.d( "收到响应: $responseCommand (第 ${retryCount + 1} 次)")
                
                if (responseCommand == "OKAY") {
                    break  // 收到正确响应
                } else {
                    Timber.w( "  跳过非 OKAY 响应: $responseCommand（可能是残留数据）")
                    retryCount++
                }
            }
            
            if (response == null || retryCount >= maxRetries) {
                Timber.e( "未收到 OKAY 响应")
                Timber.e( "  命令: $command")
                Timber.e( "  LocalId: $localId")
                return null
            }
            
            // ✅ 正确解析 OKAY 消息
            // ADB 消息格式：
            //   offset 0-3:   command ("OKAY")
            //   offset 4-7:   arg0 (服务器的 local-id，也是我们的 remote-id)
            //   offset 8-11:  arg1 (客户端的 local-id 回显，应该等于我们发送的 localId)
            //   offset 12-15: data_length
            
            val arg0Bytes = ByteArray(4)
            System.arraycopy(response, 4, arg0Bytes, 0, 4)
            val remoteId = ByteBuffer.wrap(arg0Bytes).order(ByteOrder.LITTLE_ENDIAN).int  // arg0 是 remote-id
            
            val arg1Bytes = ByteArray(4)
            System.arraycopy(response, 8, arg1Bytes, 0, 4)
            val localIdEcho = ByteBuffer.wrap(arg1Bytes).order(ByteOrder.LITTLE_ENDIAN).int  // arg1 是 local-id 回显
            
            Timber.d( "  发送 LocalId=$localId, 回显=$localIdEcho, RemoteId=$remoteId")
            
            // ✅ 验证 local-id 回显
            if (localIdEcho != localId) {
                Timber.w( "  警告：local-id 回显不匹配！发送=$localId, 回显=$localIdEcho")
                // 这是一个严重错误，但我们继续尝试
            }
            
            // 3. 读取命令输出
            val output = StringBuilder()
            var continueReading = true
            var timeoutCount = 0
            val maxTimeout = 50  // 最多等待 5 秒
            
            while (continueReading && timeoutCount < maxTimeout) {
                response = receiveMessage()
                if (response == null) {
                    // ✅ 超时时短暂等待
                    Thread.sleep(100)
                    timeoutCount++
                    continue
                }
                
                responseCommand = String(response, 0, 4, StandardCharsets.US_ASCII)
                
                when (responseCommand) {
                    "WRTE" -> {
                        // ✅ WRTE 消息格式：
                        //   offset 0-3:   command ("WRTE")
                        //   offset 4-7:   arg0 (local-id 回显)
                        //   offset 8-11:  arg1 (remote-id)
                        //   offset 12-15: data_length
                        //   offset 16-19: data_checksum
                        //   offset 20-23: magic
                        //   offset 24+:   data
                        
                        val dataLenBytes = ByteArray(4)
                        System.arraycopy(response, 12, dataLenBytes, 0, 4)
                        val dataLen = ByteBuffer.wrap(dataLenBytes).order(ByteOrder.LITTLE_ENDIAN).int
                        
                        Timber.d( "  收到 WRTE (dataLen=$dataLen)")
                        
                        if (dataLen > 0 && response.size >= 24 + dataLen) {
                            // 数据从偏移24开始
                            val data = String(response, 24, dataLen, StandardCharsets.UTF_8)
                            output.append(data)
                            Timber.d( "  收到数据: ${if (data.length > 50) data.substring(0, 50) + "..." else data}")
                        } else if (dataLen > 0) {
                            Timber.w( "  警告：响应大小不足！需要 ${24 + dataLen}，实际 ${response.size}")
                        }
                        
                        // 发送 OKAY 确认
                        sendOkayMessage(localId, remoteId)
                        timeoutCount = 0  // 重置超时计数
                    }
                    "CLSE" -> {
                        // Shell 命令执行完毕
                        Timber.d( "  收到 CLSE，命令执行完毕")
                        continueReading = false
                    }
                    "OKAY" -> {
                        // 可能是对我们 OKAY 确认的响应，忽略
                        Timber.d( "  收到 OKAY（可能是确认响应），继续读取...")
                        timeoutCount = 0  // 重置超时计数
                    }
                    else -> {
                        Timber.w( "  未知响应: $responseCommand，停止读取")
                        continueReading = false
                    }
                }
            }
            
            if (timeoutCount >= maxTimeout) {
                Timber.w( "命令执行超时")
            }
            
            val result = output.toString().trim()
            Timber.d( "命令输出长度: ${result.length} 字符")
            if (result.isNotEmpty()) {
                Timber.d( "命令输出: ${if (result.length > 100) result.substring(0, 100) + "..." else result}")
            } else {
                Timber.d( "命令输出: (空)")
            }
            result
            
        } catch (e: Exception) {
            Timber.e( "执行命令失败", e)
            null
        }
    }
    
    /**
     * 发送 OPEN 消息
     */
    private fun sendOpenMessage(localId: Int, destination: String) {
        val destBytes = destination.toByteArray(StandardCharsets.UTF_8)
        val dataLen = destBytes.size
        
        val message = ByteBuffer.allocate(24 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        
        // Command: OPEN
        message.put("OPEN".toByteArray(StandardCharsets.US_ASCII))
        
        // Local ID
        message.putInt(localId)
        
        // Remote ID (0 for OPEN)
        message.putInt(0)
        
        // Data length
        message.putInt(dataLen)
        
        // Data checksum
        message.putInt(calculateChecksum(destBytes))
        
        // Magic: OPEN ^ 0xffffffff
        val openInt = ByteBuffer.wrap("OPEN".toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        message.putInt(openInt xor 0xffffffff.toInt())
        
        // Destination string
        message.put(destBytes)
        
        outputStream?.write(message.array())
        outputStream?.flush()
        
        Timber.d( "  OPEN 消息已发送 (localId=$localId, dest=$destination)")
    }
    
    /**
     * 发送 OKAY 消息
     */
    private fun sendOkayMessage(localId: Int, remoteId: Int) {
        val message = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        
        // Command: OKAY
        message.put("OKAY".toByteArray(StandardCharsets.US_ASCII))
        
        // Local ID
        message.putInt(localId)
        
        // Remote ID
        message.putInt(remoteId)
        
        // Data length: 0
        message.putInt(0)
        
        // Data checksum: 0
        message.putInt(0)
        
        // Magic: OKAY ^ 0xffffffff
        val okayInt = ByteBuffer.wrap("OKAY".toByteArray()).order(ByteOrder.LITTLE_ENDIAN).int
        message.putInt(okayInt xor 0xffffffff.toInt())
        
        outputStream?.write(message.array())
        outputStream?.flush()
    }
    
    /**
     * 计算数据校验和
     */
    private fun calculateChecksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        return sum
    }
    
    /**
     * 发现 ADB Connect 端口
     * 优先从系统属性读取，失败则通过 mdns 发现
     * @return Connect 端口，如果未发现则返回 null
     */
    private fun discoverConnectPort(context: Context): Int? {
        // 方法 1: 从系统属性读取（最可靠）
        try {
            Timber.i( "方法 1: 从系统属性读取 Connect 端口...")
            val result = Runtime.getRuntime().exec("getprop service.adb.tls.port").inputStream.bufferedReader().readText().trim()
            val port = result.toIntOrNull()
            if (port != null && port > 0) {
                Timber.i( "✓ 从系统属性获取到 Connect 端口: $port")
                return port
            } else {
                Timber.w( "系统属性返回无效端口: '$result'")
            }
        } catch (e: Exception) {
            Timber.w( "读取系统属性失败: ${e.message}")
        }
        
        // 方法 2: mdns 服务发现
        var discoveredPort: Int? = null
        val countDownLatch = java.util.concurrent.CountDownLatch(1)
        
        try {
            Timber.i( "方法 2: 开始 mdns 服务发现: _adb-tls-connect._tcp.")
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
            
            val discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Timber.d( "mdns 发现已启动")
                }
                
                override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    Timber.d( "发现服务: ${serviceInfo.serviceName}")
                    
                    // 尝试解析服务信息
                    val resolveListener = object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                            Timber.w( "解析失败: $errorCode")
                        }
                        
                        override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
                            val port = serviceInfo.port
                            Timber.i( "✓ 解析成功: ${serviceInfo.serviceName}, 端口: $port")
                            discoveredPort = port
                            countDownLatch.countDown()
                        }
                    }
                    
                    try {
                        nsdManager.resolveService(serviceInfo, resolveListener)
                    } catch (e: Exception) {
                        Timber.e( "解析服务异常", e)
                    }
                }
                
                override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    Timber.d( "丢失服务: ${serviceInfo.serviceName}")
                }
                
                override fun onDiscoveryStopped(serviceType: String) {
                    Timber.d( "mdns 发现已停止")
                }
                
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Timber.e( "启动发现失败: $errorCode")
                    countDownLatch.countDown()
                }
                
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Timber.e( "停止发现失败: $errorCode")
                }
            }
            
            nsdManager.discoverServices("_adb-tls-connect._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            
            // 等待最多 5 秒
            val discovered = countDownLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            
            // 停止发现
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Timber.e( "停止发现异常", e)
            }
            
            if (!discovered) {
                Timber.w( "⚠ mdns 发现超时（5秒）")
            }
            
        } catch (e: Exception) {
            Timber.e( "mdns 发现异常", e)
        }
        
        // 方法 3: 扫描常见端口范围（无线调试通常使用 30000-50000）
        if (discoveredPort == null) {
            Timber.i( "方法 3: 扫描无线调试常用端口范围 (37000-38000)...")
            discoveredPort = scanPortRange("127.0.0.1", 37000, 38000, 100)
            
            if (discoveredPort == null) {
                Timber.w( "端口扫描未找到可用端口，尝试扩大范围...")
                discoveredPort = scanPortRange("127.0.0.1", 30000, 50000, 500)
            }
            
            if (discoveredPort != null) {
                Timber.i( "✓ 通过端口扫描找到 Connect 端口: $discoveredPort")
            } else {
                Timber.w( "⚠ 所有方法均失败，无法找到 Connect 端口")
            }
        }
        
        return discoveredPort
    }
    
    /**
     * 扫描端口范围
     */
    private fun scanPortRange(host: String, startPort: Int, endPort: Int, maxAttempts: Int): Int? {
        var attempts = 0
        for (port in startPort..endPort) {
            if (attempts >= maxAttempts) {
                Timber.d( "达到最大扫描次数，停止扫描")
                break
            }
            
            attempts++
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, port), 100)  // 快速超时
                socket.close()
                Timber.i( "找到可连接端口: $port")
                return port
            } catch (e: Exception) {
                // 端口不可用，继续
            }
        }
        return null
    }
}
