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
        private const val MAX_RETRY = 5  // 增加重试次数，但使用递增延迟（优化启动速度）
        private const val INITIAL_RETRY_DELAY = 300L  // 初始延迟很短（300ms）
        private const val MAX_RETRY_DELAY = 1000L  // 最大延迟（1秒）
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)  // 减少连接超时（优化响应速度）
        .readTimeout(5, TimeUnit.SECONDS)  // 减少读取超时（优化响应速度）
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

            // 步骤 3: 立即开始健康检查（使用快速重试策略）
            Timber.i("开始健康检查（快速重试策略）...")
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
                // 自动诊断问题
                readShellServerLog()
                checkShellServerProcess()
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
                    delay(1000) // 减少等待时间（优化启动速度）
                    
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
            
            // 参考应用方式：简单停止旧服务器，不等待端口释放
            // 依赖 SO_REUSEADDR 允许立即绑定（即使端口处于 TIME_WAIT 状态）
            adbManager.executeShellCommand("pkill -9 -f 'com.autobot.shell.ShellServerKt' 2>/dev/null")
            // 短暂延迟，让进程有时间终止（但不等待端口释放）
            delay(200)
            
            // 将JAR文件推送到/data/local/tmp/（参考应用的方式）
            val remoteJarPath = "/data/local/tmp/shell-server.jar"
            val localJarPath = jarFile.absolutePath
            val localFileSize = jarFile.length()
            
            // 检查远程文件是否存在且大小是否匹配（优化：避免不必要的复制）
            val remoteFileInfo = adbManager.executeShellCommand("ls -l $remoteJarPath 2>/dev/null | awk '{print \$5}'")
            val remoteFileSize = remoteFileInfo?.trim()?.toLongOrNull()
            
            if (remoteFileSize != null && remoteFileSize == localFileSize) {
                Timber.i("✓ JAR文件已存在且大小匹配，跳过复制（本地: ${localFileSize} bytes, 远程: ${remoteFileSize} bytes）")
                // 确保权限正确（可能被其他操作修改）
                adbManager.executeShellCommand("chmod 700 $remoteJarPath")
                adbManager.executeShellCommand("chown 2000 $remoteJarPath")
                adbManager.executeShellCommand("chgrp 2000 $remoteJarPath")
            } else {
                // 文件不存在或大小不匹配，需要复制
                if (remoteFileSize != null) {
                    Timber.i("JAR文件大小不匹配，需要更新（本地: ${localFileSize} bytes, 远程: ${remoteFileSize} bytes）")
                } else {
                    Timber.i("JAR文件不存在，需要复制（本地: ${localFileSize} bytes）")
                }
                
                // 先删除旧的JAR文件
                adbManager.executeShellCommand("rm -f $remoteJarPath")
                
                // 使用cp命令复制文件（应用缓存目录应该可以通过shell访问）
                val copyResult = adbManager.executeShellCommand("cp '$localJarPath' '$remoteJarPath'")
                if (copyResult == null) {
                    Timber.e("✗ cp命令失败，无法复制JAR文件")
                    return false
                }
                
                // 按照参考应用的方式设置文件权限和所有者
                // chmod 700 = rwx------ (只有所有者可读写执行)
                // chown 2000 = shell 用户
                // chgrp 2000 = shell 组
                adbManager.executeShellCommand("chmod 700 $remoteJarPath")
                adbManager.executeShellCommand("chown 2000 $remoteJarPath")
                adbManager.executeShellCommand("chgrp 2000 $remoteJarPath")
                Timber.i("✓ JAR文件已复制到设备: $remoteJarPath")
            }
            
            // 参考应用的启动方式：直接执行命令，不检查端口和进程
            val workingDir = "/data/local/tmp"
            val startCommand = "nohup app_process -Djava.class.path=$remoteJarPath $workingDir com.autobot.shell.ShellServerKt > /dev/null 2>&1 &"
            Timber.i("执行启动命令...")
            Timber.d("命令: $startCommand")

            val result = adbManager.executeShellCommand(startCommand)
            Timber.d("启动命令执行结果: ${result?.take(100)}")
            
            // 参考应用的方式：等待1秒
            Timber.d("等待 Shell Server 启动...")
            delay(1000)
            
            Timber.i("✓ Shell Server 启动命令已执行（参考应用方式：只检查命令执行，不验证进程和端口）")
            
            return true
        } catch (e: Exception) {
            Timber.e(e, "启动 Shell Server 失败")
            false
        }
    }
    
    /**
     * 通过 ADB 停止 Shell Server
     * 参考应用方式：简单停止，不等待端口释放，依赖 SO_REUSEADDR 允许立即重启
     */
    private suspend fun stopShellServerViaAdb(adbManager: com.autobot.adb.AdbConnectionManager) = withContext(Dispatchers.IO) {
        try {
            // 参考应用方式：只发送停止命令，不等待结果
            adbManager.executeShellCommand("pkill -9 -f 'com.autobot.shell.ShellServerKt' 2>/dev/null")
            Timber.d("✓ Shell Server 停止命令已执行（SO_REUSEADDR 允许立即重启）")
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
                // 等待进程完全终止
                delay(1000)  // 减少等待时间（优化停止速度）
                
                // 断开 ADB 连接（参考应用的行为）
                Timber.i("断开 ADB 连接...")
                adbManager.disconnect()
                
                Timber.i("✓ Shell Server 已停止，ADB 连接已断开")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "停止 Shell Server 失败")
            false
        }
    }

    /**
     * 检查 Shell Server 健康状态
     * 参考应用方式：快速重试，因为启动命令已经等待了 1 秒
     */
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        var retryCount = 0
        // 参考应用方式：启动命令已等待 1 秒，健康检查立即开始
        // 使用渐进式重试策略
        val delays = listOf(0L, 300L, 500L, 700L, 1000L)
        
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
                    Timber.i("✓ Shell Server 健康检查通过 (尝试 ${retryCount + 1})")
                    return@withContext true
                }
            } catch (e: Exception) {
                if (retryCount < MAX_RETRY - 1) {
                    // 只在非最后一次尝试时输出debug日志
                    Timber.d("健康检查失败 (尝试 ${retryCount + 1}/$MAX_RETRY): ${e.message}")
                } else {
                    Timber.w("健康检查失败 (尝试 ${retryCount + 1}/$MAX_RETRY): ${e.message}")
                }
            }

            retryCount++
            if (retryCount < MAX_RETRY) {
                // 使用预定义的延迟序列
                val delayTime = if (retryCount - 1 < delays.size) {
                    delays[retryCount - 1]
                } else {
                    MAX_RETRY_DELAY
                }
                delay(delayTime)
            }
        }

        Timber.e("✗ Shell Server 健康检查失败，已达最大重试次数")
        
        return@withContext false
    }
    
    /**
     * 读取 Shell Server 日志文件
     */
    private suspend fun readShellServerLog() = withContext(Dispatchers.IO) {
        try {
            val adbManager = com.autobot.adb.AdbConnectionManager.getInstance()
            if (adbManager.isConnected()) {
                val logPath = "/sdcard/shell-server.log"
                Timber.i("正在读取 Shell Server 日志: $logPath")
                val logContent = adbManager.executeShellCommand("cat $logPath 2>/dev/null")
                if (logContent != null && logContent.isNotEmpty()) {
                    Timber.e("========================================")
                    Timber.e("Shell Server 日志内容:")
                    Timber.e("========================================")
                    // 按行输出日志，方便阅读
                    logContent.lines().forEach { line ->
                        Timber.e("  $line")
                    }
                    Timber.e("========================================")
                } else {
                    Timber.w("日志文件为空或不存在（输出已重定向到 /dev/null）")
                }
            } else {
                Timber.w("ADB 未连接，无法读取日志")
            }
        } catch (e: Exception) {
            Timber.e(e, "读取 Shell Server 日志失败")
        }
    }
    
    /**
     * 检查 Shell Server 进程状态
     */
    private suspend fun checkShellServerProcess() = withContext(Dispatchers.IO) {
        try {
            val adbManager = com.autobot.adb.AdbConnectionManager.getInstance()
            if (adbManager.isConnected()) {
                Timber.i("正在检查 Shell Server 进程状态...")
                
                // 检查进程（可能失败，因为 app_process 在 ps 中可能不显示完整参数）
                val psResult = adbManager.executeShellCommand("ps -A | grep -E 'app_process.*shell-server|com.autobot.shell.ShellServerKt' | grep -v grep")
                if (psResult != null && psResult.isNotEmpty()) {
                    Timber.i("✓ 发现 Shell Server 进程:")
                    psResult.lines().forEach { line ->
                        Timber.i("  $line")
                    }
                } else {
                    Timber.w("✗ 未发现 Shell Server 进程（可能进程名称被截断，这是正常的）")
                }
                
                // 检查端口（更可靠的方法：检查 LISTEN 状态）
                val listenCheck = adbManager.executeShellCommand("netstat -tuln | grep :$SHELL_SERVER_PORT | grep LISTEN")
                if (listenCheck != null && listenCheck.isNotEmpty()) {
                    Timber.i("✓ 端口 $SHELL_SERVER_PORT 处于 LISTEN 状态:")
                    Timber.i("  $listenCheck")
                } else {
                    // 也检查其他状态的连接（CLOSING 等）
                    val allConnections = adbManager.executeShellCommand("netstat -tuln | grep :$SHELL_SERVER_PORT")
                    if (allConnections != null && allConnections.isNotEmpty()) {
                        Timber.w("⚠ 端口 $SHELL_SERVER_PORT 有连接但非 LISTEN 状态:")
                        Timber.w("  $allConnections")
                    } else {
                        Timber.w("✗ 端口 $SHELL_SERVER_PORT 未被占用，服务器可能未启动")
                    }
                }
                
                // 检查 JAR 文件
                val jarCheck = adbManager.executeShellCommand("ls -l /data/local/tmp/shell-server.jar 2>/dev/null")
                if (jarCheck != null && jarCheck.isNotEmpty()) {
                    Timber.i("✓ JAR 文件存在:")
                    Timber.i("  $jarCheck")
                } else {
                    Timber.e("✗ JAR 文件不存在或无法访问")
                }
            } else {
                Timber.w("ADB 未连接，无法检查进程状态")
            }
        } catch (e: Exception) {
            Timber.e(e, "检查 Shell Server 进程状态失败")
        }
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

