package com.autobot.shell

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shell Server 管理器
 * 负责部署和启动 Shell Server
 */
class ShellServerManager(private val context: Context) {

    companion object {
        private const val TAG = "ShellServerManager"
        private const val SHELL_SERVER_JAR = "shell-server.jar"
        private const val SHELL_SERVER_PORT = 19090
        private const val MAX_RETRY = 3
        private const val RETRY_DELAY = 2000L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 部署并启动 Shell Server
     * @return 是否成功
     */
    suspend fun deployAndStart(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.i("========================================")
            Timber.i("开始部署 Shell Server")
            Timber.i("========================================")

            // 步骤 1: 提取 JAR 文件到外部缓存
            val jarFile = extractJarToCache()
            if (jarFile == null || !jarFile.exists()) {
                Timber.e("✗ 提取 Shell Server JAR 失败")
                return@withContext false
            }
            Timber.i("✓ Shell Server JAR 已提取: ${jarFile.absolutePath}")

            // 步骤 2: 启动 Shell Server（直接从缓存目录运行，使用 ADB）
            if (!startShellServerViaAdb(jarFile)) {
                Timber.e("✗ 启动 Shell Server 失败")
                return@withContext false
            }
            Timber.i("✓ Shell Server 启动命令已执行")

            // 步骤 3: 等待并检查健康状态
            Timber.i("等待 Shell Server 初始化...")
            delay(3000) // 等待 3 秒让 Shell Server 完全启动

            val isHealthy = checkHealth()
            if (isHealthy) {
                Timber.i("========================================")
                Timber.i("✓ Shell Server 部署并启动成功")
                Timber.i("  监听端口: $SHELL_SERVER_PORT")
                Timber.i("  健康检查: http://127.0.0.1:$SHELL_SERVER_PORT/api/hello")
                Timber.i("========================================")
            } else {
                Timber.w("⚠️ Shell Server 健康检查未通过")
                Timber.w("进程可能已启动但端口未就绪，或启动失败")
                Timber.w("请通过 ADB 查看日志: adb shell cat /sdcard/shell-server.log")
            }

            return@withContext isHealthy
        } catch (e: Exception) {
            Timber.e(e, "✗ Shell Server 部署失败")
            return@withContext false
        }
    }

    /**
     * 从 assets 提取 JAR 到外部缓存目录
     */
    private fun extractJarToCache(): File? {
        return try {
            val jarFile = File(context.externalCacheDir, SHELL_SERVER_JAR)
            
            // 如果已存在，先删除
            if (jarFile.exists()) {
                jarFile.delete()
            }

            // 从 assets 复制
            context.assets.open("shell-server/$SHELL_SERVER_JAR").use { input ->
                FileOutputStream(jarFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 设置权限 (r-x--x--x = 511 = 0777 八进制)
            try {
                Runtime.getRuntime().exec("chmod 755 ${jarFile.absolutePath}").waitFor()
            } catch (e: Exception) {
                Timber.w(e, "设置文件权限失败（可能不影响）")
            }

            jarFile
        } catch (e: Exception) {
            Timber.e(e, "提取 JAR 文件失败")
            null
        }
    }

    /**
     * 通过 ADB 连接启动 Shell Server
     * 必须使用 ADB shell 权限来执行 app_process 命令
     */
    private suspend fun startShellServerViaAdb(jarFile: File): Boolean {
        return try {
            // 获取 ADB 连接管理器
            val adbManager = com.autobot.adb.AdbConnectionManager.getInstance()
            
            // 如果未连接，尝试连接
            if (!adbManager.isConnected()) {
                Timber.i("ADB 未连接，尝试自动建立连接...")
                try {
                    adbManager.connect(context)
                    delay(2000) // 等待连接建立
                    
                    if (!adbManager.isConnected()) {
                        Timber.e("✗ ADB 自动连接失败")
                        Timber.e("请先在主界面点击「开始配对」按钮建立 ADB 连接")
                        return false
                    }
                    Timber.i("✓ ADB 自动连接成功")
                } catch (e: Exception) {
                    Timber.e(e, "ADB 自动连接失败")
                    return false
                }
            }
            
            Timber.i("ADB 已连接，准备启动 Shell Server")
            
            // 先尝试停止已有的 Shell Server
            stopShellServerViaAdb(adbManager)

            // 日志文件路径（外部存储，方便查看）
            val logPath = "/sdcard/shell-server.log"
            
            // 启动命令（使用 setsid 完全守护化）
            // 1. setsid: 创建新会话，进程成为会话首进程，脱离控制终端
            // 2. sh -c '...': 在子 shell 中执行，确保后台运行不受父进程影响
            // 3. </dev/null: 重定向 stdin
            // 4. >$logPath 2>&1: 重定向 stdout 和 stderr 到日志文件
            // 5. & 结尾: 后台运行
            // 6. 外层 &: 确保整个 setsid 命令也是后台
            val command = "setsid sh -c 'app_process -Djava.class.path=${jarFile.absolutePath} " +
                    "${jarFile.parent} com.autobot.shell.ShellServerKt $SHELL_SERVER_PORT " +
                    "</dev/null >$logPath 2>&1 &' &"

            Timber.i("启动命令: $command")
            Timber.i("💡 日志文件: $logPath")
            
            // 通过 ADB 执行命令
            val result = adbManager.executeShellCommand(command)
            
            if (result != null) {
                Timber.i("Shell Server 启动命令已通过 ADB 执行")
                Timber.d("命令输出: $result")
                
                // 启动命令执行成功（后台运行），不依赖 ps 检查
                // 将在后续的健康检查中验证是否真正启动
                return true
            } else {
                Timber.e("通过 ADB 执行启动命令失败")
                // 查看日志
                val logResult = adbManager.executeShellCommand("cat $logPath")
                if (logResult != null) {
                    Timber.e("Shell Server 日志:\n$logResult")
                }
                return false
            }
        } catch (e: Exception) {
            Timber.e(e, "启动 Shell Server 失败")
            false
        }
    }
    
    /**
     * 通过 ADB 停止 Shell Server
     */
    private fun stopShellServerViaAdb(adbManager: com.autobot.adb.AdbConnectionManager) {
        try {
            val result = adbManager.executeShellCommand("pkill -f $SHELL_SERVER_JAR")
            Timber.d("停止 Shell Server: $result")
        } catch (e: Exception) {
            Timber.w(e, "停止 Shell Server 失败（可能没有运行）")
        }
    }

    /**
     * 停止 Shell Server
     */
    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val adbManager = com.autobot.adb.AdbConnectionManager.getInstance()
            
            if (!adbManager.isConnected()) {
                Timber.w("ADB 未连接，无法停止 Shell Server")
                // 尝试直接 kill（不保证成功）
                try {
                    Runtime.getRuntime().exec("pkill -f $SHELL_SERVER_JAR").waitFor()
                    Timber.i("✓ 已发送停止命令（本地方式）")
                    true
                } catch (e: Exception) {
                    Timber.e(e, "停止失败")
                    false
                }
            } else {
                stopShellServerViaAdb(adbManager)
                Timber.i("✓ Shell Server 已停止")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "停止 Shell Server 失败")
            false
        }
    }

    /**
     * 检查 Shell Server 健康状态
     */
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        var retryCount = 0
        while (retryCount < MAX_RETRY) {
            try {
                val request = Request.Builder()
                    .url("http://127.0.0.1:$SHELL_SERVER_PORT/api/hello")
                    .get()
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                Timber.d("健康检查响应: HTTP ${response.code}, Body: $responseBody")

                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    Timber.i("✓ Shell Server 健康检查通过")
                    return@withContext true
                }
            } catch (e: Exception) {
                Timber.w("Shell Server 健康检查失败 (尝试 ${retryCount + 1}/$MAX_RETRY): ${e.message}")
            }

            retryCount++
            if (retryCount < MAX_RETRY) {
                delay(RETRY_DELAY)
            }
        }

        Timber.e("✗ Shell Server 健康检查失败，已达最大重试次数")
        return@withContext false
    }

    /**
     * 获取屏幕 XML（测试用）
     */
    suspend fun getScreenXml(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://127.0.0.1:$SHELL_SERVER_PORT/api/screenXml")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                Timber.e("获取屏幕 XML 失败: HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "获取屏幕 XML 失败")
            null
        }
    }
}

