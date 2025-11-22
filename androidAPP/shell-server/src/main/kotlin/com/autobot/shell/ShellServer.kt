package com.autobot.shell

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import com.autobot.shell.core.UiAutomationService
import com.autobot.shell.core.DeviceInfoService
import com.autobot.shell.core.InputService
import com.autobot.shell.core.ClipboardService
import com.autobot.shell.core.AppManagementService
import com.autobot.shell.core.ShellCommandService
import com.autobot.shell.core.ContextProvider
import org.json.JSONObject
import org.json.JSONArray
import android.content.Context
import android.os.Looper
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
    
    // 其他服务（不需要Context或延迟初始化）
    private val shellCommandService = ShellCommandService()
    private var deviceInfoService: DeviceInfoService? = null
    private var inputService: InputService? = null
    private var clipboardService: ClipboardService? = null
    private var appManagementService: AppManagementService? = null
    
    // 设备名称存储（简单实现）
    private var deviceDisplayName: String = "AutoBot Device"
    
    // 版本号
    private val version = 12403

    init {
        println("=================================")
        println("AutoBot Shell Server v1.0")
        println("=================================")
        println("Starting on port $PORT...")
        println("⚠ UiAutomation will be initialized on first request")
        println("⚠ Context-dependent services will be initialized on first use")
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
                
                // 初始化InputService（依赖UiAutomationService）
                if (inputService == null) {
                    inputService = InputService(uiAutomationService!!)
                }
            } else {
                println("⚠ UiAutomation service initialization failed")
            }
        } catch (e: Exception) {
            println("⚠ UiAutomation initialization error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 延迟初始化需要Context的服务
     */
    private fun ensureContextServicesInitialized(): Boolean {
        if (deviceInfoService != null) {
            return true
        }
        
        return try {
            val context = ContextProvider.getContext()
            if (context != null) {
                deviceInfoService = DeviceInfoService(context)
                clipboardService = ClipboardService(context)
                appManagementService = AppManagementService(context)
                println("✓ Context-dependent services initialized")
                true
            } else {
                println("⚠ Context not available")
                false
            }
        } catch (e: Exception) {
            println("⚠ Error initializing context services: ${e.message}")
            e.printStackTrace()
            false
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

                // ============ 设备和系统信息 API ============
                
                // 获取设备ID
                uri == "/api/getDeviceId" && method == Method.GET -> {
                    handleGetDeviceId()
                }
                
                // 获取IP地址
                uri == "/api/getIp" && method == Method.GET -> {
                    handleGetIp()
                }
                
                // 获取版本号
                uri == "/api/version" && method == Method.GET -> {
                    handleGetVersion()
                }
                
                // 获取屏幕信息
                uri == "/api/screenInfo" && method == Method.GET -> {
                    handleGetScreenInfo()
                }
                
                // 获取屏幕方向
                uri == "/api/screenRotation" && method == Method.GET -> {
                    handleGetScreenRotation()
                }
                
                // 获取系统信息
                uri == "/api/getSystemInfo" && method == Method.GET -> {
                    handleGetSystemInfo()
                }
                
                // 截图Base64（带前缀）
                uri == "/api/screenShotBase64" && method == Method.GET -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleScreenshotBase64()
                }
                
                // ============ 高级输入操作 API ============
                
                // 长按点击
                uri == "/api/longClick" && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleLongClick(session)
                }
                
                // 长按（指定时长）
                uri == "/api/press" && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handlePress(session)
                }
                
                // 单指手势
                uri == "/api/gesture" && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleGesture(session)
                }
                
                // 输入ASCII字符
                uri == "/api/inputChar" && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleInputChar(session)
                }
                
                // 输入文本（支持中文）
                uri == "/api/inputText" && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleInputText(session)
                }
                
                // 清除文本
                uri == "/api/clearText" && method == Method.GET -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleClearText()
                }
                
                // ============ 剪切板 API ============
                
                // 设置剪切板
                uri == "/api/setClipText" && method == Method.POST -> {
                    handleSetClipText(session)
                }
                
                // 获取剪切板
                uri == "/api/getClipText" && method == Method.GET -> {
                    handleGetClipText()
                }
                
                // ============ 应用管理 API ============
                
                // 获取顶层Activity
                uri == "/api/getTopActivity" && method == Method.GET -> {
                    handleGetTopActivity()
                }
                
                // 获取启动Activity
                uri.startsWith("/api/getStartActivity") && method == Method.GET -> {
                    handleGetStartActivity(session)
                }
                
                // 启动应用
                uri.startsWith("/api/startPackage") && method == Method.GET -> {
                    handleStartPackage(session)
                }
                
                // ============ Shell命令 API ============
                
                // 执行Shell命令
                uri == "/api/execCmd" && method == Method.POST -> {
                    handleExecCmd(session)
                }
                
                // ============ 设备名称 API ============
                
                // 设置设备名称
                uri == "/api/setDisplayName" && method == Method.POST -> {
                    handleSetDisplayName(session)
                }
                
                // 获取设备名称
                uri == "/api/getDisplayName" && method == Method.GET -> {
                    handleGetDisplayName()
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
    
    // ==================== 新增 API 处理函数 ====================
    
    /**
     * 获取设备ID
     * GET /api/getDeviceId
     */
    private fun handleGetDeviceId(): Response {
        return try {
            ensureContextServicesInitialized()
            val deviceId = deviceInfoService?.getDeviceId() ?: "unknown"
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", deviceId)
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取IP地址
     * GET /api/getIp
     */
    private fun handleGetIp(): Response {
        return try {
            ensureContextServicesInitialized()
            val ipList = deviceInfoService?.getIpAddresses() ?: emptyList()
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", JSONArray(ipList))
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取版本号
     * GET /api/version
     */
    private fun handleGetVersion(): Response {
        return try {
            newFixedLengthResponse(Status.OK, "text/plain", version.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取屏幕信息
     * GET /api/screenInfo
     */
    private fun handleGetScreenInfo(): Response {
        return try {
            ensureContextServicesInitialized()
            val screenInfo = deviceInfoService?.getScreenInfo() ?: mapOf<String, Any>()
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", JSONObject(screenInfo))
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取屏幕方向
     * GET /api/screenRotation
     */
    private fun handleGetScreenRotation(): Response {
        return try {
            ensureContextServicesInitialized()
            val rotation = deviceInfoService?.getScreenRotation() ?: 0
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", rotation.toString())
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取系统信息
     * GET /api/getSystemInfo
     */
    private fun handleGetSystemInfo(): Response {
        return try {
            ensureContextServicesInitialized()
            val systemInfo = deviceInfoService?.getSystemInfo() ?: mapOf<String, Any>()
            newFixedLengthResponse(Status.OK, "application/json", JSONObject(systemInfo).toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 截图Base64（带前缀）
     * GET /api/screenShotBase64
     */
    private fun handleScreenshotBase64(): Response {
        return try {
            val base64 = uiAutomationService?.takeScreenshot() ?: ""
            if (base64.isNotEmpty()) {
                val response = JSONObject()
                response.put("code", 1)
                response.put("data", "data:image/jpeg;base64,$base64")
                newFixedLengthResponse(Status.OK, "application/json", response.toString())
            } else {
                errorResponse("截图失败")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 输入操作 API ====================
    
    /**
     * 长按点击
     * POST /api/longClick
     * Body: {"x": 540, "y": 960}
     */
    private fun handleLongClick(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val x = json.getDouble("x").toFloat()
            val y = json.getDouble("y").toFloat()
            
            val success = inputService?.longClick(x, y) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 长按（指定时长）
     * POST /api/press
     * Body: {"x": 540, "y": 960, "duration": 1000}
     */
    private fun handlePress(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val x = json.getDouble("x").toFloat()
            val y = json.getDouble("y").toFloat()
            val duration = json.optLong("duration", 600)
            
            val success = inputService?.press(x, y, duration) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 单指手势
     * POST /api/gesture
     * Body: {"duration": 200, "points": [{"x": 100, "y": 200}, {"x": 300, "y": 400}]}
     */
    private fun handleGesture(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val duration = json.optLong("duration", 200)
            val pointsArray = json.getJSONArray("points")
            
            val points = mutableListOf<InputService.Point>()
            for (i in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(i)
                points.add(InputService.Point(
                    point.getDouble("x").toFloat(),
                    point.getDouble("y").toFloat()
                ))
            }
            
            val success = inputService?.gesture(duration, points) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 输入ASCII字符
     * POST /api/inputChar
     * Body: {"value": "hello world"}
     */
    private fun handleInputChar(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val text = json.getString("value")
            
            val success = inputService?.inputChar(text) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 输入文本（支持中文）
     * POST /api/inputText
     * Body: {"value": "你好世界"}
     */
    private fun handleInputText(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val text = json.getString("value")
            
            val success = inputService?.inputText(text) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 清除文本
     * GET /api/clearText
     */
    private fun handleClearText(): Response {
        return try {
            val success = inputService?.clearText() ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 剪切板 API ====================
    
    /**
     * 设置剪切板
     * POST /api/setClipText
     * Body: {"value": "hello world"}
     */
    private fun handleSetClipText(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val text = json.getString("value")
            
            val success = clipboardService?.setClipText(text) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取剪切板
     * GET /api/getClipText
     */
    private fun handleGetClipText(): Response {
        return try {
            ensureContextServicesInitialized()
            val text = clipboardService?.getClipText() ?: ""
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", text)
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 应用管理 API ====================
    
    /**
     * 获取顶层Activity
     * GET /api/getTopActivity
     */
    private fun handleGetTopActivity(): Response {
        return try {
            ensureContextServicesInitialized()
            val topActivity = appManagementService?.getTopActivity()
            
            val response = JSONObject()
            if (topActivity != null) {
                response.put("code", 1)
                response.put("data", JSONObject(topActivity))
            } else {
                response.put("code", 0)
                response.put("data", null)
                response.put("message", "Failed to get top activity")
            }
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取启动Activity
     * GET /api/getStartActivity?packageName=com.example.app
     */
    private fun handleGetStartActivity(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val packageName = params["packageName"] ?: return errorResponse("Missing packageName parameter")
            
            val startActivity = appManagementService?.getStartActivity(packageName)
            
            val response = JSONObject()
            if (startActivity != null) {
                response.put("code", 1)
                response.put("data", startActivity)
            } else {
                response.put("code", 0)
                response.put("data", null)
                response.put("message", "Failed to get start activity")
            }
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 启动应用
     * GET /api/startPackage?packageName=com.example.app
     */
    private fun handleStartPackage(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val packageName = params["packageName"] ?: return errorResponse("Missing packageName parameter")
            
            val success = appManagementService?.startPackage(packageName) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== Shell命令 API ====================
    
    /**
     * 执行Shell命令
     * POST /api/execCmd
     * Body: {"value": "ls -l", "timeout": 10}
     */
    private fun handleExecCmd(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val command = json.getString("value")
            val timeout = json.optInt("timeout", 10)
            
            val output = shellCommandService.execCommand(command, timeout)
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", output)
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 设备名称 API ====================
    
    /**
     * 设置设备名称
     * POST /api/setDisplayName
     * Body: {"value": "设备001"}
     */
    private fun handleSetDisplayName(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            deviceDisplayName = json.getString("value")
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", "1")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取设备名称
     * GET /api/getDisplayName
     */
    private fun handleGetDisplayName(): Response {
        return try {
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", deviceDisplayName)
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 错误响应辅助函数
     */
    private fun errorResponse(message: String): Response {
        val error = JSONObject()
        error.put("code", 0)
        error.put("data", null)
        error.put("message", message)
        return newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error.toString())
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

