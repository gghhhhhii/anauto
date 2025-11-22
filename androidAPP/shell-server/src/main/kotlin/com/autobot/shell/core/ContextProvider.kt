package com.autobot.shell.core

import android.content.Context

/**
 * Context提供者（参考参考应用的实现方式）
 * 在Shell环境下通过反射从ActivityThread获取SystemContext
 */
object ContextProvider {
    
    private var context: Context? = null
    private var activityThread: Any? = null
    
    init {
        try {
            // 0. 准备主Looper（必须在创建ActivityThread之前）
            try {
                android.os.Looper.prepareMainLooper()
                println("✓ Main Looper prepared")
            } catch (e: Exception) {
                // 可能已经准备过了，忽略
                println("⚠ Main Looper already prepared or failed: ${e.message}")
            }
            
            // 1. 获取ActivityThread类
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            
            // 2. 创建ActivityThread实例（通过私有构造函数）
            val constructor = activityThreadClass.getDeclaredConstructor()
            constructor.isAccessible = true
            activityThread = constructor.newInstance()
            
            // 3. 设置为currentActivityThread（可选，但参考应用这样做了）
            try {
                val sCurrentActivityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread")
                sCurrentActivityThreadField.isAccessible = true
                sCurrentActivityThreadField.set(null, activityThread)
            } catch (e: Exception) {
                println("⚠ Failed to set sCurrentActivityThread: ${e.message}")
            }
            
            println("✓ ActivityThread instance created")
        } catch (e: Exception) {
            println("⚠ Error creating ActivityThread: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 获取Context（通过getSystemContext方法）
     */
    fun getContext(): Context? {
        if (context != null) {
            return context
        }
        
        return try {
            if (activityThread == null) {
                println("⚠ ActivityThread not initialized")
                return null
            }
            
            // 通过getSystemContext()方法获取Context
            val activityThreadClass = activityThread!!.javaClass
            val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
            getSystemContextMethod.isAccessible = true
            
            context = getSystemContextMethod.invoke(activityThread) as? Context
            
            if (context != null) {
                println("✓ Context obtained from ActivityThread.getSystemContext()")
                println("  Package: ${context?.packageName}")
            } else {
                println("⚠ getSystemContext() returned null")
            }
            
            context
        } catch (e: Exception) {
            println("⚠ Error getting Context: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 备用方法：尝试通过currentApplication获取
     */
    private fun getContextViaCurrentApplication(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
            currentApplicationMethod.invoke(null) as? Context
        } catch (e: Exception) {
            null
        }
    }
}
