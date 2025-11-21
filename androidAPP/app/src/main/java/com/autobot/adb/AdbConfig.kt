package com.autobot.adb

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.util.*

/**
 * ADB 配置管理
 * 负责 ADB 密钥的生成、存储和读取
 */
object AdbConfig {

    private const val PRIVATE_KEY_FILE = "adb_private.key"
    private const val PUBLIC_KEY_FILE = "adb_public.key"
    
    const val ADB_CONNECT_TIMEOUT_MS = 10000
    const val ADB_PAIR_TIMEOUT_MS = 15000

    /**
     * 获取或生成 ADB 密钥对
     */
    fun getOrCreateKeyPair(context: Context): KeyPair {
        val privateKeyFile = File(context.filesDir, PRIVATE_KEY_FILE)
        val publicKeyFile = File(context.filesDir, PUBLIC_KEY_FILE)

        // 尝试加载现有密钥
        if (privateKeyFile.exists() && publicKeyFile.exists()) {
            try {
                val privateKey = loadPrivateKey(privateKeyFile)
                val publicKey = loadPublicKey(publicKeyFile)
                if (privateKey != null && publicKey != null) {
                    Timber.d("使用已有 ADB 密钥对")
                    return KeyPair(publicKey, privateKey)
                }
            } catch (e: Exception) {
                Timber.w(e, "加载已有密钥失败，将生成新密钥")
            }
        }

        // 生成新密钥对
        Timber.d("生成新的 ADB 密钥对")
        val keyPair = generateKeyPair()
        
        // 保存密钥
        savePrivateKey(privateKeyFile, keyPair.private)
        savePublicKey(publicKeyFile, keyPair.public)

        return keyPair
    }

    /**
     * 生成 RSA 密钥对
     */
    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    /**
     * 保存私钥
     */
    private fun savePrivateKey(file: File, privateKey: PrivateKey) {
        try {
            val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
            file.writeText(encoded)
            Timber.d("私钥已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "保存私钥失败")
        }
    }

    /**
     * 保存公钥
     */
    private fun savePublicKey(file: File, publicKey: PublicKey) {
        try {
            val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
            file.writeText(encoded)
            Timber.d("公钥已保存: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "保存公钥失败")
        }
    }

    /**
     * 加载私钥
     */
    private fun loadPrivateKey(file: File): PrivateKey? {
        return try {
            val encoded = file.readText()
            val keyBytes = Base64.getDecoder().decode(encoded)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            Timber.e(e, "加载私钥失败")
            null
        }
    }

    /**
     * 加载公钥
     */
    private fun loadPublicKey(file: File): PublicKey? {
        return try {
            val encoded = file.readText()
            val keyBytes = Base64.getDecoder().decode(encoded)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
            keyFactory.generatePublic(keySpec)
        } catch (e: Exception) {
            Timber.e(e, "加载公钥失败")
            null
        }
    }

    /**
     * 获取设备名称
     */
    fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    /**
     * 标记配对成功
     */
    fun markPairingSuccess(context: Context) {
        context.getSharedPreferences("adb_config", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_paired", true)
            .putLong("paired_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * 检查是否已配对
     */
    fun isPaired(context: Context): Boolean {
        return context.getSharedPreferences("adb_config", Context.MODE_PRIVATE)
            .getBoolean("is_paired", false)
    }

    /**
     * 保存 Connect 端口
     */
    fun saveConnectPort(context: Context, port: Int) {
        context.getSharedPreferences("adb_config", Context.MODE_PRIVATE)
            .edit()
            .putInt("connect_port", port)
            .apply()
    }

    /**
     * 获取 Connect 端口
     */
    fun getConnectPort(context: Context): Int? {
        val port = context.getSharedPreferences("adb_config", Context.MODE_PRIVATE)
            .getInt("connect_port", -1)
        return if (port > 0) port else null
    }
}

