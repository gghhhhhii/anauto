package com.autobot

import android.app.Application
import android.os.Build
import timber.log.Timber
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * AutoBot 应用程序类
 */
class AutoBotApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化 Hidden API Bypass（Android 9+ 需要绕过隐藏 API 限制）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                Timber.d("Hidden API Bypass initialized")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize Hidden API Bypass")
            }
        }

        // 初始化日志（始终启用，方便调试）
        Timber.plant(Timber.DebugTree())

        Timber.d("AutoBot Application Started")
    }
}

