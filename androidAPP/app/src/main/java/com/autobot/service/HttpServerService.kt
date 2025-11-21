package com.autobot.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.autobot.R
import com.autobot.adb.AdbConfig
import com.autobot.adb.AdbConnectionManager
import com.autobot.http.HttpServer
import com.autobot.shell.ShellServerManager
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.NetworkInterface

/**
 * HTTP 服务器前台服务
 */
class HttpServerService : Service() {

    companion object {
        private const val TAG = "HttpServerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "autobot_service_channel"
        private const val DEFAULT_PORT = 7777

        // 广播 Action
        const val ACTION_SERVICE_STATUS_CHANGED = "com.autobot.SERVICE_STATUS_CHANGED"

        // Extra 键
        const val EXTRA_HTTP_RUNNING = "http_running"
        const val EXTRA_ADB_CONNECTED = "adb_connected"
        const val EXTRA_SHELL_RUNNING = "shell_running"
        const val EXTRA_SERVER_URL = "server_url"

        /**
         * 启动服务
         */
        fun start(context: Context, port: Int = DEFAULT_PORT) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                putExtra("port", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, HttpServerService::class.java))
        }
    }

    private var httpServer: HttpServer? = null
    private var adbConnectionManager: AdbConnectionManager? = null
    private var shellServerManager: ShellServerManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isHttpRunning = false
    private var isAdbConnected = false
    private var isShellRunning = false

    override fun onCreate() {
        super.onCreate()
        Timber.d("服务创建")

        // 创建通知渠道
        createNotificationChannel()

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("服务启动")

        val port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT

        // 启动 HTTP 服务器
        startHttpServer(port)

        // 初始化 ADB 连接管理器
        initAdbConnection()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("服务销毁")

        // 停止 HTTP 服务器
        httpServer?.stopServer()
        httpServer = null

        // 断开 ADB 连接
        adbConnectionManager?.disconnect()
        adbConnectionManager = null

        // 取消协程
        scope.cancel()

        isHttpRunning = false
        isAdbConnected = false
        isShellRunning = false

        // 广播状态变化
        broadcastStatus()
    }

    /**
     * 启动 HTTP 服务器
     */
    private fun startHttpServer(port: Int) {
        try {
            httpServer = HttpServer(applicationContext, port)
            httpServer?.startServer()

            isHttpRunning = true
            Timber.d("HTTP 服务器已启动: 端口 $port")

            // 更新通知
            val notification = createNotification()
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)

            // 广播状态
            broadcastStatus()
        } catch (e: Exception) {
            Timber.e(e, "HTTP 服务器启动失败")
            isHttpRunning = false
            broadcastStatus()
        }
    }

    /**
     * 初始化 ADB 连接并启动 Shell Server
     */
    private fun initAdbConnection() {
        adbConnectionManager = AdbConnectionManager.getInstance()
        shellServerManager = ShellServerManager(applicationContext)

        scope.launch {
            try {
                Timber.i("========================================")
                Timber.i("开始初始化服务")
                Timber.i("========================================")

                // 步骤 1: 检查是否已配对
                if (!AdbConfig.isPaired(applicationContext)) {
                    Timber.w("⚠️ 设备尚未配对，请先完成无线调试配对")
                    broadcastStatus()
                    return@launch
                }

                // 步骤 2: 建立 ADB TLS 连接
                Timber.i("步骤 1/2: 建立 ADB TLS 连接...")
                val connected = withContext(Dispatchers.IO) {
                    adbConnectionManager?.connect(applicationContext) ?: false
                }
                
                if (connected) {
                    Timber.i("✓ ADB 连接成功")
                    isAdbConnected = true
                    broadcastStatus()
                } else {
                    Timber.e("✗ ADB 连接失败")
                    isAdbConnected = false
                    broadcastStatus()
                    return@launch
                }

                // 步骤 3: 通过 ADB 部署并启动 Shell Server
                Timber.i("步骤 2/2: 部署并启动 Shell Server...")
                val shellStarted = deployAndStartShellServerViaAdb()
                
                if (shellStarted) {
                    Timber.i("✓ Shell Server 启动成功")
                    isShellRunning = true
                } else {
                    Timber.e("✗ Shell Server 启动失败")
                    isShellRunning = false
                }

                // 更新状态
                broadcastStatus()

                Timber.i("========================================")
                if (isAdbConnected && isShellRunning) {
                    Timber.i("✓ 所有服务初始化完成")
                    Timber.i("✓ Shell Server 运行在: http://127.0.0.1:19090")
                } else {
                    Timber.w("⚠️ 部分服务启动失败")
                }
                Timber.i("========================================")

            } catch (e: Exception) {
                Timber.e(e, "服务初始化失败")
                isAdbConnected = false
                isShellRunning = false
                broadcastStatus()
            }
        }
    }

    /**
     * 通过 ADB 连接部署并启动 Shell Server
     */
    private suspend fun deployAndStartShellServerViaAdb(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 提取 JAR 到外部缓存
            val jarFile = java.io.File(applicationContext.externalCacheDir, "shell-server.jar")
            if (jarFile.exists()) {
                jarFile.delete()
            }
            
            applicationContext.assets.open("shell-server/shell-server.jar").use { input ->
                java.io.FileOutputStream(jarFile).use { output ->
                    input.copyTo(output)
                }
            }
            Timber.i("  ✓ Shell Server JAR 已提取: ${jarFile.absolutePath}")

            // 2. 通过 ADB 复制 JAR 到 /data/local/tmp
            Timber.i("  复制 JAR 到 /data/local/tmp...")
            val copyResult = adbConnectionManager?.executeShellCommand("cp ${jarFile.absolutePath} /data/local/tmp/shell-server.jar")
            if (copyResult == null) {
                Timber.e("  ✗ 复制 JAR 失败: 无响应")
                return@withContext false
            }
            Timber.i("  ✓ JAR 已复制")

            // 3. 设置文件权限
            adbConnectionManager?.executeShellCommand("chmod 700 /data/local/tmp/shell-server.jar")
            adbConnectionManager?.executeShellCommand("chown 2000:2000 /data/local/tmp/shell-server.jar")

            // 4. 启动 Shell Server
            Timber.i("  启动 Shell Server...")
            
            // 先杀掉旧的 Shell Server 进程
            adbConnectionManager?.executeShellCommand("pkill -f shell-server.jar")
            delay(500)
            
            // 启动新的 Shell Server（输出日志到文件以便调试）
            val startCommand = "nohup app_process -Djava.class.path=/data/local/tmp/shell-server.jar " +
                    "/data/local/tmp com.autobot.shell.ShellServerKt 19090 > /sdcard/shell-server.log 2>&1 &"
            val startResult = adbConnectionManager?.executeShellCommand(startCommand)
            if (startResult == null) {
                Timber.e("  ✗ 启动命令失败: 无响应")
                return@withContext false
            }
            Timber.i("  ✓ Shell Server 启动命令已执行")
            Timber.i("  💡 日志文件: /sdcard/shell-server.log")

            // 5. 等待并检查健康状态
            delay(2000)
            val isHealthy = shellServerManager?.checkHealth() ?: false
            
            return@withContext isHealthy
        } catch (e: Exception) {
            Timber.e(e, "部署 Shell Server 失败")
            return@withContext false
        }
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, com.autobot.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val status = buildString {
            if (isHttpRunning) {
                append("HTTP: ✓  ")
                val serverUrl = getServerUrl()
                if (serverUrl != null) {
                    append("$serverUrl  ")
                }
            }
            if (isAdbConnected) append("ADB: ✓  ")
            if (isShellRunning) append("Shell: ✓")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status.ifEmpty { "正在启动..." })
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * 广播服务状态
     */
    private fun broadcastStatus() {
        val intent = Intent(ACTION_SERVICE_STATUS_CHANGED).apply {
            putExtra(EXTRA_HTTP_RUNNING, isHttpRunning)
            putExtra(EXTRA_ADB_CONNECTED, isAdbConnected)
            putExtra(EXTRA_SHELL_RUNNING, isShellRunning)
            putExtra(EXTRA_SERVER_URL, getServerUrl())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * 获取服务器 URL
     */
    private fun getServerUrl(): String? {
        if (!isHttpRunning) return null

        val ip = getWifiIpAddress() ?: return null
        return "http://$ip:${httpServer?.listeningPort ?: 7777}"
    }

    /**
     * 获取 WiFi IP 地址
     */
    private fun getWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "获取 IP 地址失败")
        }
        return null
    }
}

