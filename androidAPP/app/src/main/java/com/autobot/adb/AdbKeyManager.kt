package com.autobot.adb

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import timber.log.Timber
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB 密钥对管理器
 * 用于保存和加载配对时生成的 RSA 密钥对
 */
object AdbKeyManager {
    private const val PREFS_NAME = "adb_key_pair"
    private const val KEY_PRIVATE_KEY = "private_key"
    private const val KEY_PUBLIC_KEY = "public_key"
    
    /**
     * 保存密钥对
     */
    fun saveKeyPair(context: Context, keyPair: KeyPair): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // 编码密钥
            val privateKeyBytes = keyPair.private.encoded
            val publicKeyBytes = keyPair.public.encoded
            
            // Base64 编码保存
            val privateKeyBase64 = Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP)
            val publicKeyBase64 = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
            
            editor.putString(KEY_PRIVATE_KEY, privateKeyBase64)
            editor.putString(KEY_PUBLIC_KEY, publicKeyBase64)
            editor.apply()
            
            Timber.i( "✓ 密钥对已保存")
            true
        } catch (e: Exception) {
            Timber.e( "✗ 保存密钥对失败", e)
            false
        }
    }
    
    /**
     * 加载密钥对
     */
    fun loadKeyPair(context: Context): KeyPair? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            val privateKeyBase64 = prefs.getString(KEY_PRIVATE_KEY, null)
            val publicKeyBase64 = prefs.getString(KEY_PUBLIC_KEY, null)
            
            if (privateKeyBase64 == null || publicKeyBase64 == null) {
                Timber.w("未找到保存的密钥对")
                return null
            }
            
            // Base64 解码
            val privateKeyBytes = Base64.decode(privateKeyBase64, Base64.NO_WRAP)
            val publicKeyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            
            // 重建密钥对
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            
            val keyPair = KeyPair(publicKey, privateKey)
            Timber.i( "✓ 密钥对已加载")
            return keyPair
        } catch (e: Exception) {
            Timber.e( "✗ 加载密钥对失败", e)
            null
        }
    }
    
    /**
     * 检查是否有保存的密钥对
     */
    fun hasKeyPair(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_PRIVATE_KEY, null)
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null)
        return privateKey != null && publicKey != null
    }
    
    /**
     * 清除保存的密钥对
     */
    fun clearKeyPair(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Timber.i( "✓ 密钥对已清除")
    }
}



