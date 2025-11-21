package com.autobot

import android.app.Application
import timber.log.Timber

/**
 * AutoBot 应用程序类
 */
class AutoBotApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化日志（始终启用，方便调试）
        Timber.plant(Timber.DebugTree())

        Timber.d("AutoBot Application Started")
    }
}

