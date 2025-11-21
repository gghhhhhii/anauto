package com.autobot.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.autobot.R
import com.autobot.adb.AdbConfig
import com.autobot.adb.AdbPairingClient
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * ADB 无线调试配对服务
 * 通过通知栏输入配对码
 */
class WirelessDebugPairingService : Service() {
    
    companion object {
        private const val TAG = "PairingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "adb_pairing_channel"
        const val ACTION_PAIR = "com.autobot.ACTION_PAIR"
        const val KEY_PAIRING_CODE = "pairing_code"
        
        var lastPairingResult: String? = null
            private set
        var isRunning = false
            private set
        
        fun startPairing(context: Context) {
            val intent = Intent(context, WirelessDebugPairingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    private lateinit var notificationManager: NotificationManager
    private lateinit var adbPairingManager: AdbPairingManager
    private lateinit var adbConnectManager: AdbPairingManager
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var discoveredService: NsdServiceInfo? = null
    private var discoveredConnectPort: Int? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("Service onCreate")

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        adbPairingManager = AdbPairingManager(this, AdbPairingManager.PAIRING_SERVICE)
        adbConnectManager = AdbPairingManager(this, AdbPairingManager.CONNECT_SERVICE)

        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand: action=${intent?.action}")

        startForeground()

        when (intent?.action) {
            ACTION_PAIR -> {
                val pairingCode = intent.getStringExtra(KEY_PAIRING_CODE)
                if (pairingCode != null) {
                    Timber.i("✓ 收到配对请求")
                    handlePairing(pairingCode)
                }
            }
            else -> {
                startServiceDiscovery()
            }
        }

        return START_STICKY
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("初始化中...", null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("初始化中...", null))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB 配对服务",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "ADB 无线配对通知"
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String, remoteInput: RemoteInput?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADB 无线配对")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)

        if (remoteInput != null) {
            val pairIntent = Intent(this, PairingReceiver::class.java).apply {
                action = ACTION_PAIR
            }
            val pairPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                pairIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val action = NotificationCompat.Action.Builder(
                0,
                "配对",
                pairPendingIntent
            ).addRemoteInput(remoteInput).build()

            builder.addAction(action)
        }

        return builder.build()
    }

    private fun updateNotification(content: String, remoteInput: RemoteInput? = null) {
        val notification = createNotification(content, remoteInput)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun startServiceDiscovery() {
        updateNotification("正在搜索 ADB 配对服务...")
        
        Timber.i("========================================")
        Timber.i("同时发现 Pairing 和 Connect 服务")
        Timber.i("========================================")
    
        serviceScope.launch {
            // 发现 Connect 服务
            Timber.i("启动 Connect 服务发现...")
            adbConnectManager.startDiscovery { serviceInfo ->
                discoveredConnectPort = serviceInfo.port
                Timber.i("✓ 发现 ADB Connect 端口: ${serviceInfo.port}")
            }
            
            delay(300)
            
            // 发现 Pairing 服务
            Timber.i("启动 Pairing 服务发现...")
            adbPairingManager.startDiscovery { serviceInfo ->
                discoveredService = serviceInfo
                val hostString = serviceInfo.host.toString()
                val pairingPort = serviceInfo.port
                Timber.i("✓ 发现 ADB 配对服务: $hostString:$pairingPort")

                // 显示输入框
                val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
                    .setLabel("配对码")
                    .build()

                updateNotification(
                    "已发现配对服务\n" +
                            "地址: $hostString:$pairingPort\n\n" +
                            "请在开发者选项中生成配对码，然后在下方输入",
                    remoteInput
                )
            }
        }
    }

    private fun handlePairing(pairingCode: String) {
        Timber.d("开始配对，配对码: $pairingCode")
        updateNotification("正在配对...")

        serviceScope.launch {
            val result = performPairing(pairingCode)
            
            // 直接在此处理配对结果
            lastPairingResult = result.message
            
            if (result.success) {
                Timber.i("✓ 配对成功: ${result.message}")
                
                // 标记配对成功
                AdbConfig.markPairingSuccess(this@WirelessDebugPairingService)
                
                // 保存 Connect 端口
                Timber.d("检查 Connect 端口状态: discoveredConnectPort=$discoveredConnectPort")
                
                if (discoveredConnectPort != null) {
                    AdbConfig.saveConnectPort(this@WirelessDebugPairingService, discoveredConnectPort!!)
                    Timber.i("✓ 已保存 Connect 端口: $discoveredConnectPort")
                    updateNotification("✓ 配对成功！\nConnect 端口: $discoveredConnectPort")
                } else {
                    Timber.w("⚠ Connect 端口未发现，等待 10 秒...")
                    updateNotification("✓ 配对成功！\n等待 Connect 端口...")
                    delay(10000)
                    
                    Timber.d("10秒后重新检查: discoveredConnectPort=$discoveredConnectPort")
                    if (discoveredConnectPort != null) {
                        AdbConfig.saveConnectPort(this@WirelessDebugPairingService, discoveredConnectPort!!)
                        Timber.i("✓ 已保存 Connect 端口: $discoveredConnectPort")
                        updateNotification("✓ 配对成功！\nConnect 端口: $discoveredConnectPort")
                    } else {
                        Timber.e("✗ Connect 端口始终未发现！")
                        updateNotification("✓ 配对成功！\n⚠ Connect 端口未发现")
                    }
                }
                
                adbConnectManager.stopDiscovery()
                delay(3000)
                stopSelf()
            } else {
                Timber.e("✗ 配对失败: ${result.message}")
                updateNotification("✗ 配对失败\n${result.message}")
                delay(3000)
                stopSelf()
            }
        }
    }

    private suspend fun performPairing(pairingCode: String): PairingResult {
        return withContext(Dispatchers.IO) {
            try {
                val service = discoveredService
                if (service == null) {
                    Timber.e("✗ 未发现 ADB 服务")
                    return@withContext PairingResult(
                        success = false,
                        message = "未发现 ADB 配对服务\n请确保无线调试已开启"
                    )
                }
                
                // 使用 127.0.0.1 连接本地 ADB
                val hostString = "127.0.0.1"
                val port = service.port
                
                Timber.i("========================================")
                Timber.i("使用 TLS + SPAKE2+ 协议")
                Timber.i("目标: $hostString:$port")
                if (discoveredConnectPort != null) {
                    Timber.i("服务端口: $discoveredConnectPort")
                }
                Timber.i("========================================")
                
                val client = AdbPairingClient(
                    hostString, 
                    port, 
                    pairingCode, 
                    discoveredConnectPort,
                    onKeyPairGenerated = { keyPair ->
                        Timber.i("保存配对密钥对...")
                        val saved = com.autobot.adb.AdbKeyManager.saveKeyPair(this@WirelessDebugPairingService, keyPair)
                        if (saved) {
                            Timber.i("✓ 密钥对已保存")
                        } else {
                            Timber.e("✗ 密钥对保存失败")
                        }
                    }
                )
                val success = client.pair()
                
                if (success) {
                    Timber.i("✓ 配对成功！")
                    return@withContext PairingResult(
                        success = true,
                        message = "配对成功！"
                    )
                } else {
                    Timber.e("✗ 配对失败")
                    return@withContext PairingResult(
                        success = false,
                        message = "配对失败\n请检查配对码是否正确"
                    )
                }
                
            } catch (e: Exception) {
                Timber.e(e, "✗ 配对异常")
                PairingResult(
                    success = false,
                    message = "配对异常: ${e.message}"
                )
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        adbPairingManager.stopDiscovery()
        adbConnectManager.stopDiscovery()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

data class PairingResult(
    val success: Boolean,
    val message: String
)

