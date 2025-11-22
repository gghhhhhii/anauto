package com.autobot.shell.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪切板服务
 */
class ClipboardService(private val context: Context) {

    private val clipboardManager: ClipboardManager by lazy {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    /**
     * 设置剪切板文本
     */
    fun setClipText(text: String): Boolean {
        return try {
            val clip = ClipData.newPlainText("text", text)
            clipboardManager.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取剪切板文本
     */
    fun getClipText(): String {
        return try {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).text?.toString() ?: ""
            } else {
                ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}

