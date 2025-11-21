package top.tntok.autobot.adb

import timber.log.Timber

/**
 * SPAKE2+ 配对认证上下文
 * 
 * 通过 JNI 调用 libadb.so 中的 SPAKE2+ 实现
 * 
 * 注意：包名必须是 top.tntok.autobot.adb 以匹配 libadb.so 中的 JNI 函数签名
 */
class PairingAuthCtx private constructor(private val nativeHandle: Long) {
    
    companion object {
        private const val TAG = "PairingAuthCtx"
        
        init {
            try {
                System.loadLibrary("adb")
                Timber.i("✓ libadb.so 加载成功")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "✗ 加载 libadb.so 失败")
                throw e
            }
        }
        
        /**
         * 创建配对认证上下文
         */
        @JvmStatic
        fun create(isClient: Boolean, password: ByteArray): PairingAuthCtx? {
            return try {
                val handle = nativeConstructor(isClient, password)
                if (handle == 0L) {
                    Timber.e("✗ nativeConstructor 返回 null")
                    null
                } else {
                    Timber.i("✓ SPAKE2 上下文创建成功，handle=$handle")
                    PairingAuthCtx(handle)
                }
            } catch (e: Exception) {
                Timber.e(e, "✗ 创建 PairingAuthCtx 失败")
                null
            }
        }
        
        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
    
    val message: ByteArray by lazy {
        try {
            val msg = nativeMsg(nativeHandle)
            Timber.i("✓ 获取 SPAKE2 消息成功，大小=${msg.size} bytes")
            msg
        } catch (e: Exception) {
            Timber.e(e, "✗ 获取 SPAKE2 消息失败")
            ByteArray(0)
        }
    }
    
    fun initCipher(theirMsg: ByteArray): Boolean {
        return try {
            val result = nativeInitCipher(nativeHandle, theirMsg)
            if (result) {
                Timber.i("✓ 密钥初始化成功")
            } else {
                Timber.e("✗ 密钥初始化失败")
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "✗ initCipher 异常")
            false
        }
    }
    
    fun encrypt(data: ByteArray): ByteArray? {
        return try {
            val encrypted = nativeEncrypt(nativeHandle, data)
            if (encrypted != null) {
                Timber.d("✓ 加密成功: ${data.size} → ${encrypted.size} bytes")
            } else {
                Timber.e("✗ 加密失败")
            }
            encrypted
        } catch (e: Exception) {
            Timber.e(e, "✗ encrypt 异常")
            null
        }
    }
    
    fun decrypt(data: ByteArray): ByteArray? {
        return try {
            val decrypted = nativeDecrypt(nativeHandle, data)
            if (decrypted != null) {
                Timber.d("✓ 解密成功: ${data.size} → ${decrypted.size} bytes")
            } else {
                Timber.e("✗ 解密失败")
            }
            decrypted
        } catch (e: Exception) {
            Timber.e(e, "✗ decrypt 异常")
            null
        }
    }
    
    fun destroy() {
        try {
            nativeDestroy(nativeHandle)
            Timber.i("✓ SPAKE2 上下文已销毁")
        } catch (e: Exception) {
            Timber.e(e, "✗ destroy 异常")
        }
    }
    
    private external fun nativeMsg(handle: Long): ByteArray
    private external fun nativeInitCipher(handle: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(handle: Long, data: ByteArray): ByteArray?
    private external fun nativeDecrypt(handle: Long, data: ByteArray): ByteArray?
    private external fun nativeDestroy(handle: Long)
}

