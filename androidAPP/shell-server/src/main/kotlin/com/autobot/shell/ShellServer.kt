package com.autobot.shell

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import com.autobot.shell.core.UiAutomationService
import org.json.JSONObject
import java.io.IOException

/**
 * AutoBot Shell Server
 * 运行在 Shell 权限下，负责执行需要高权限的操作
 *
 * 端口: 19090
 * 主类: com.autobot.shell.ShellServerKt
 *
 * 启动命令:
 * app_process -Djava.class.path=/data/local/tmp/shell-server.dex /data/local/tmp com.autobot.shell.ShellServerKt
 */
private const val PORT = 19090

class ShellServer : NanoHTTPD(PORT) {

    private var uiAutomationService: UiAutomationService? = null
    private var uiAutomationInitialized = false

    init {
        println("=================================")
        println("AutoBot Shell Server v1.0")
        println("=================================")
        println("Starting on port $PORT...")
        println("⚠ UiAutomation will be initialized on first request")
    }
    
    /**
     * 延迟初始化 UiAutomation（避免在 init 块中崩溃）
     */
    private fun ensureUiAutomationInitialized() {
        if (uiAutomationInitialized) {
            return
        }
        
        try {
            println("Initializing UiAutomation service...")
            uiAutomationService = UiAutomationService.getInstance()
            uiAutomationInitialized = uiAutomationService?.initialize() ?: false
            
            if (uiAutomationInitialized) {
                println("✓ UiAutomation service initialized successfully")
            } else {
                println("⚠ UiAutomation service initialization failed")
            }
        } catch (e: Exception) {
            println("⚠ UiAutomation initialization error: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // 减少日志输出，提升性能（只记录非健康检查请求）
        if (uri != "/api/hello") {
        println("Request: $method $uri")
        }

        return try {
            when {
                // 健康检查（不需要 UiAutomation）
                uri == "/api/hello" && method == Method.GET -> {
                    val response = JSONObject()
                    response.put("status", "ok")
                    response.put("message", "Shell Server is running")
                    response.put("port", PORT)
                    newFixedLengthResponse(Status.OK, "application/json", response.toString())
                }

                // UI 层次结构 XML（需要 UiAutomation）
                uri.startsWith("/api/screenXml") && method == Method.GET -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleScreenXml(session)
                }

                // UI 层次结构 JSON（需要 UiAutomation）
                uri.startsWith("/api/screenJson") && method == Method.GET -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleScreenJson(session)
                }

                // 截图 API（需要 UiAutomation）
                uri.startsWith("/api/screenshot") && method == Method.GET -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleScreenshot(session)
                }

                // 点击 API（需要 UiAutomation）
                uri.startsWith("/api/click") && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleClick(session)
                }

                // 滑动 API（需要 UiAutomation）
                uri.startsWith("/api/swipe") && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleSwipe(session)
                }

                // 按键 API（需要 UiAutomation）
                uri.startsWith("/api/pressKey") && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handlePressKey(session)
                }

                // 404 Not Found
                else -> {
                    val error = """
                        {
                            "code": 0,
                            "data": null,
                            "message": "Not Found: $uri"
                        }
                    """.trimIndent()
                    newFixedLengthResponse(Status.NOT_FOUND, "application/json", error)
                }
            }
        } catch (e: Exception) {
            println("Request handling exception: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": null,
                    "message": "Internal Server Error: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 处理屏幕 XML 请求
     * GET /api/screenXml?isWait=0|1
     * 默认 isWait=0（不等待，快速响应）
     */
    private fun handleScreenXml(session: IHTTPSession): Response {
        return try {
            val params = session.parameters
            // 默认 0（不等待），参考应用通常不等待
            val isWait = params["isWait"]?.firstOrNull()?.toIntOrNull() ?: 0
            val waitForIdle = isWait == 1

            // 只在需要时输出，减少日志
            if (waitForIdle) {
            println("收到 screenXml 请求: isWait=$waitForIdle")
            }

            val xml = uiAutomationService?.dumpXML(waitForIdle = waitForIdle, visibleOnly = true) ?: ""

            if (xml.isNotEmpty()) {
                val response = """
                    {
                        "code": 1,
                        "data": ${escapeJson(xml)},
                        "message": "success"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.OK, "application/json", response)
            } else {
                val error = """
                    {
                        "code": 0,
                        "data": "",
                        "message": "获取 UI 层次结构失败"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
            }
        } catch (e: Exception) {
            println("screenXml 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": "",
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 处理屏幕 JSON 请求
     * GET /api/screenJson?isWait=0|1
     * 默认 isWait=0（不等待，快速响应）
     */
    private fun handleScreenJson(session: IHTTPSession): Response {
        return try {
            val params = session.parameters
            // 默认 0（不等待），参考应用通常不等待
            val isWait = params["isWait"]?.firstOrNull()?.toIntOrNull() ?: 0
            val waitForIdle = isWait == 1

            // 只在需要时输出，减少日志
            if (waitForIdle) {
            println("收到 screenJson 请求: isWait=$waitForIdle")
            }

            val json = uiAutomationService?.dumpJSON(waitForIdle = waitForIdle, visibleOnly = true) ?: "{}"

            if (json.isNotEmpty() && json != "{}") {
                val response = """
                    {
                        "code": 1,
                        "data": $json,
                        "message": "success"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.OK, "application/json", response)
            } else {
                val error = """
                    {
                        "code": 0,
                        "data": {},
                        "message": "获取 UI 层次结构失败"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
            }
        } catch (e: Exception) {
            println("screenJson 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": {},
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 处理截图请求
     * GET /api/screenshot
     */
    private fun handleScreenshot(session: IHTTPSession): Response {
        return try {
            println("收到 screenshot 请求")

            val base64Image = uiAutomationService?.takeScreenshot()

            if (base64Image != null) {
                val response = """
                    {
                        "code": 1,
                        "data": "$base64Image",
                        "message": "success"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.OK, "application/json", response)
            } else {
                val error = """
                    {
                        "code": 0,
                        "data": "",
                        "message": "截图失败"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
            }
        } catch (e: Exception) {
            println("screenshot 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": "",
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 处理点击请求
     * POST /api/click
     * Body: {"x": 100, "y": 200}
     */
    private fun handleClick(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val x = json.getInt("x")
            val y = json.getInt("y")

            println("收到 click 请求: x=$x, y=$y")

            val success = uiAutomationService?.click(x, y) ?: false

            val response = """
                {
                    "code": ${if (success) 1 else 0},
                    "data": $success,
                    "message": "${if (success) "success" else "点击失败"}"
                }
            """.trimIndent()
            newFixedLengthResponse(
                if (success) Status.OK else Status.INTERNAL_ERROR,
                "application/json",
                response
            )
        } catch (e: Exception) {
            println("click 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": false,
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 处理滑动请求
     * POST /api/swipe
     * Body: {"x1": 100, "y1": 200, "x2": 300, "y2": 400, "duration": 300}
     */
    private fun handleSwipe(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val x1 = json.getInt("x1")
            val y1 = json.getInt("y1")
            val x2 = json.getInt("x2")
            val y2 = json.getInt("y2")
            val duration = json.optLong("duration", 300)

            println("收到 swipe 请求: ($x1,$y1)->($x2,$y2), duration=$duration")

            val success = uiAutomationService?.swipe(x1, y1, x2, y2, duration) ?: false

            val response = """
                {
                    "code": ${if (success) 1 else 0},
                    "data": $success,
                    "message": "${if (success) "success" else "滑动失败"}"
                }
            """.trimIndent()
            newFixedLengthResponse(
                if (success) Status.OK else Status.INTERNAL_ERROR,
                "application/json",
                response
            )
        } catch (e: Exception) {
            println("swipe 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": false,
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 处理按键请求
     * POST /api/pressKey
     * Body: {"keyCode": 4}  // 4 = BACK, 3 = HOME, 82 = MENU
     */
    private fun handlePressKey(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val keyCode = json.getInt("keyCode")

            println("收到 pressKey 请求: keyCode=$keyCode")

            val success = uiAutomationService?.pressKey(keyCode) ?: false

            val response = """
                {
                    "code": ${if (success) 1 else 0},
                    "data": $success,
                    "message": "${if (success) "success" else "按键失败"}"
                }
            """.trimIndent()
            newFixedLengthResponse(
                if (success) Status.OK else Status.INTERNAL_ERROR,
                "application/json",
                response
            )
        } catch (e: Exception) {
            println("pressKey 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": false,
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }

    /**
     * 读取 POST 请求的 Body
     */
    private fun readRequestBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) {
            return "{}"
        }

        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        return body["postData"] ?: "{}"
    }

    /**
     * 转义 JSON 字符串
     */
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .let { "\"$it\"" }
    }
}

/**
 * Main 函数
 */
fun main(args: Array<String>) {
    try {
        println("=== Shell Server Main ===")
        println("Starting AutoBot Shell Server...")
        
        // 创建并启动服务器
        // 注意：Looper 会在 UiAutomationService.initialize() 中初始化
        val server = ShellServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        println("✓ Shell Server started on port $PORT")
        println("✓ Health check: http://127.0.0.1:$PORT/api/hello")
        println("Server running...")

        // 保持进程运行
        val keepAliveThread = Thread {
            try {
                while (true) {
                    Thread.sleep(30000) // 每30秒唤醒一次
                }
            } catch (e: InterruptedException) {
                println("Keep-alive thread interrupted")
            }
        }
        keepAliveThread.isDaemon = false
        keepAliveThread.name = "AutoBotServerKeepAlive"
        keepAliveThread.start()
        println("✓ Keep-alive thread started")

        // 阻塞主线程
        keepAliveThread.join()

    } catch (e: IOException) {
        System.err.println("FATAL ERROR: Cannot start server")
        e.printStackTrace()
        System.exit(1)
    } catch (e: Exception) {
        System.err.println("FATAL ERROR: ${e.message}")
        e.printStackTrace()
        System.exit(1)
    }
}

