package com.autobot.shell.core

import android.content.Context

/**
 * Context提供者
 * 在Shell环境下通过反射获取Context
 */
object ContextProvider {
    
    private var context: Context? = null
    
    /**
     * 获取Context（通过反射从ActivityThread获取）
     */
    fun getContext(): Context? {
        if (context != null) {
            return context
        }
        
        return try {
            // 通过反射获取ActivityThread的静态方法
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            val application = currentApplicationMethod.invoke(null)
            
            if (application != null) {
                context = application as Context
                println("✓ Context obtained from ActivityThread")
                context
            } else {
                println("⚠ Failed to get Context from ActivityThread")
                null
            }
        } catch (e: Exception) {
            println("⚠ Error getting Context: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}

