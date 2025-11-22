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
import com.autobot.shell.core.InputManagerService
import com.autobot.shell.core.FileService
import com.autobot.shell.core.ContactsService
import com.autobot.shell.core.SmsService
import com.autobot.shell.core.PhoneService
import org.json.JSONObject
import org.json.JSONArray
import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import java.io.IOException
import java.net.ServerSocket

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
    
    // 其他服务
    private val shellCommandService = ShellCommandService()
    private val deviceInfoService = DeviceInfoService()  // 不需要Context
    private val fileService = FileService()  // 不需要Context
    private var inputService: InputService? = null
    private var clipboardService: ClipboardService? = null
    private var appManagementService: AppManagementService? = null
    private var contactsService: ContactsService? = null
    private var smsService: SmsService? = null
    private var phoneService: PhoneService? = null
    
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
     * 重写 start 方法，在启动后通过反射设置 SO_REUSEADDR
     * 参考应用在 AbstractC0386o00oO00O.java 中直接设置 setReuseAddress(true)
     * 这允许在端口仍处于 TIME_WAIT 状态时立即绑定，解决启动失败问题
     * 
     * 注意：虽然理想情况下应该在绑定前设置 SO_REUSEADDR，但 NanoHTTPD 的架构
     * 使得我们只能在启动后通过反射设置。这仍然有效，因为 SO_REUSEADDR 会影响
     * 后续的绑定操作（如果服务器重启）。
     */
    override fun start(timeout: Int, daemon: Boolean) {
        try {
            // 先调用父类的 start 方法（这会创建并绑定 ServerSocket）
            super.start(timeout, daemon)
            
            // 启动后，通过反射设置 SO_REUSEADDR
            // 虽然此时 socket 已绑定，但设置 SO_REUSEADDR 仍然有用：
            // 1. 如果服务器重启，下次绑定时会使用这个设置
            // 2. 某些情况下，即使已绑定，设置它也能帮助处理端口重用
            val possibleFieldNames = listOf("serverSocket", "myServerSocket", "illlI1lLIL", "myServerSocketFactory")
            var success = false
            
            // 遍历所有父类，查找 serverSocket 字段
            var currentClass: Class<*>? = javaClass.superclass
            while (currentClass != null && !success) {
                for (fieldName in possibleFieldNames) {
                    try {
                        val field = currentClass.getDeclaredField(fieldName)
                        field.isAccessible = true
                        val value = field.get(this)
                        
                        // 如果是 ServerSocket，直接设置
                        if (value is ServerSocket) {
                            value.reuseAddress = true
                            println("✓ SO_REUSEADDR enabled on ServerSocket (via field: ${currentClass.simpleName}.$fieldName)")
                            success = true
                            break
                        }
                    } catch (e: NoSuchFieldException) {
                        // 字段不存在，继续尝试
                    } catch (e: Exception) {
                        // 其他异常，记录但继续
                        println("⚠ Error accessing field $fieldName in ${currentClass.simpleName}: ${e.message}")
                    }
                }
                currentClass = currentClass.superclass
            }
            
            // 如果还没找到，尝试遍历所有字段
            if (!success) {
                try {
                    var searchClass: Class<*>? = javaClass.superclass
                    while (searchClass != null && !success) {
                        val allFields = searchClass.declaredFields
                        for (field in allFields) {
                            if (field.type == ServerSocket::class.java) {
                                try {
                                    field.isAccessible = true
                                    val serverSocket = field.get(this) as? ServerSocket
                                    if (serverSocket != null) {
                                        serverSocket.reuseAddress = true
                                        println("✓ SO_REUSEADDR enabled on ServerSocket (via field: ${searchClass.simpleName}.${field.name})")
                                        success = true
                                        break
                                    }
                                } catch (e: Exception) {
                                    // 忽略单个字段的错误
                                }
                            }
                        }
                        searchClass = searchClass.superclass
                    }
                } catch (e: Exception) {
                    println("⚠ Error searching all fields: ${e.message}")
                }
            }
            
            if (!success) {
                println("⚠ Could not set SO_REUSEADDR via reflection")
                println("⚠ Server may have issues restarting quickly, but should still work")
            }
        } catch (e: Exception) {
            println("⚠ Error in start() override: ${e.message}")
            e.printStackTrace()
            // 如果反射失败，仍然尝试正常启动
            try {
                super.start(timeout, daemon)
            } catch (e2: Exception) {
                println("✗ Fatal: Cannot start server: ${e2.message}")
                throw e2
            }
        }
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
     * 延迟初始化需要Context的服务（剪切板和应用管理）
     */
    private fun ensureContextServicesInitialized(): Boolean {
        if (clipboardService != null) {
            return true
        }
        
        return try {
            val context = ContextProvider.getContext()
            if (context != null) {
                clipboardService = ClipboardService(context)
                appManagementService = AppManagementService(context)
                contactsService = ContactsService(context)
                smsService = SmsService(context)
                phoneService = PhoneService(context)
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
                    response.put("code", 1)
                    response.put("data", "Shell Server is running on port $PORT")
                    response.put("message", "success")
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
                    handleInputText(session)
                }
                
                // 清除文本
                uri == "/api/clearText" && method == Method.GET -> {
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
                
                // 停止应用
                uri.startsWith("/api/stopPackage") && method == Method.GET -> {
                    handleStopPackage(session)
                }
                
                // 清除应用数据
                uri.startsWith("/api/clearPackage") && method == Method.GET -> {
                    handleClearPackage(session)
                }
                
                // 获取所有应用
                uri == "/api/getAllPackage" && method == Method.GET -> {
                    handleGetAllPackage()
                }
                
                // 获取应用详情
                uri.startsWith("/api/getPackageInfo") && method == Method.GET -> {
                    handleGetPackageInfo(session)
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
                
                // ============ 屏幕控制 API ============
                
                // 熄屏
                uri == "/api/turnScreenOff" && method == Method.GET -> {
                    handleTurnScreenOff()
                }
                
                // 亮屏
                uri == "/api/turnScreenOn" && method == Method.GET -> {
                    handleTurnScreenOn()
                }
                
                // ============ 多指手势 API ============
                
                // 多指手势
                uri == "/api/gestures" && method == Method.POST -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleGestures(session)
                }
                
                // ============ 截图 API（图片格式）============
                
                // 截图（返回图片）
                uri == "/api/screenShot" && method == Method.GET -> {
                    ensureUiAutomationInitialized()
                    if (!uiAutomationInitialized) {
                        return newFixedLengthResponse(
                            Status.SERVICE_UNAVAILABLE,
                            MIME_PLAINTEXT,
                            "UiAutomation service not available"
                        )
                    }
                    handleScreenShot(session)
                }
                
                // ============ 文件操作 API ============
                
                // 列出文件
                uri.startsWith("/api/listFile") && method == Method.GET -> {
                    handleListFile(session)
                }
                
                // 上传文件
                uri == "/api/upload" && method == Method.POST -> {
                    handleUpload(session)
                }
                
                // 下载文件
                uri.startsWith("/api/download") && method == Method.GET -> {
                    handleDownload(session)
                }
                
                // 删除文件
                uri.startsWith("/api/delFile") && method == Method.GET -> {
                    handleDelFile(session)
                }
                
                // ============ 联系人 API ============
                
                // 获取所有联系人
                uri == "/api/getAllContact" && method == Method.GET -> {
                    handleGetAllContact()
                }
                
                // 插入联系人
                uri == "/api/insertContact" && method == Method.POST -> {
                    handleInsertContact(session)
                }
                
                // 删除联系人
                uri.startsWith("/api/deleteContact") && method == Method.GET -> {
                    handleDeleteContact(session)
                }
                
                // ============ 短信 API ============
                
                // 获取所有短信
                uri == "/api/getAllSms" && method == Method.GET -> {
                    handleGetAllSms()
                }
                
                // 发送短信
                uri == "/api/sendSms" && method == Method.POST -> {
                    handleSendSms(session)
                }
                
                // ============ 电话 API ============
                
                // 拨打电话
                uri.startsWith("/api/callPhone") && method == Method.GET -> {
                    handleCallPhone(session)
                }
                
                // 挂断电话
                uri == "/api/endCall" && method == Method.GET -> {
                    handleEndCall()
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
            
            // 使用 InputManager 而不是 UiAutomation
            val success = InputManagerService.pressKey(keyCode)
            
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
            val deviceId = deviceInfoService.getDeviceId()
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
            val ipList = deviceInfoService.getIpAddresses()
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
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", version.toString())
            response.put("message", "success")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
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
            val screenInfo = deviceInfoService.getScreenInfo()
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
            val rotation = deviceInfoService.getScreenRotation()
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
            val systemInfo = deviceInfoService.getSystemInfo()
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", JSONObject(systemInfo))
            response.put("message", "success")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
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
            
            // 后台线程执行，避免阻塞HTTP请求
            Thread {
                try {
                    inputService?.longClick(x, y)
                } catch (e: Exception) {
                    println("✗ 长按异常: ${e.message}")
                }
            }.start()
            
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
     * 长按（指定时长）
     * POST /api/press
     * Body: {"x": 540, "y": 960, "duration": 1000}
     */
    private fun handlePress(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            // 支持两种参数格式：坐标 (x, y) 或按键 (keyCode)
            val keyCode = json.optInt("keyCode", -1)
            if (keyCode != -1) {
                // 按键模式 - 使用 InputManagerService
                val success = InputManagerService.pressKey(keyCode)
                val response = JSONObject()
                response.put("code", if (success) 1 else 0)
                response.put("data", if (success) "1" else "0")
                return newFixedLengthResponse(Status.OK, "application/json", response.toString())
            }
            // 坐标模式
            val x = json.getDouble("x").toFloat()
            val y = json.getDouble("y").toFloat()
            val duration = json.optLong("duration", 600)
            
            // 后台线程执行，避免阻塞HTTP请求
            Thread {
                try {
                    inputService?.press(x, y, duration)
                } catch (e: Exception) {
                    println("✗ 长按异常: ${e.message}")
                }
            }.start()
            
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
                val item = pointsArray.get(i)
                if (item is JSONObject) {
                    // 格式: {"x": 100, "y": 200}
                    points.add(InputService.Point(
                        item.getDouble("x").toFloat(),
                        item.getDouble("y").toFloat()
                    ))
                } else if (item is JSONArray) {
                    // 格式: [100, 200]
                    points.add(InputService.Point(
                        item.getDouble(0).toFloat(),
                        item.getDouble(1).toFloat()
                    ))
                }
            }
            
            // 后台线程执行，避免阻塞HTTP请求
            Thread {
                try {
                    inputService?.gesture(duration, points)
                } catch (e: Exception) {
                    println("✗ 手势异常: ${e.message}")
                }
            }.start()
            
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
     * 输入ASCII字符
     * POST /api/inputChar
     * Body: {"value": "hello world"}
     */
    private fun handleInputChar(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val text = json.optString("char", json.optString("value", ""))
            if (text.isEmpty()) {
                return errorResponse("Missing char parameter")
            }
            
            // 使用 InputManager 而不是 UiAutomation，避免死锁
            val success = InputManagerService.inputChar(text)
            
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
     * 实现方式：使用UiAutomation的setText方法（避免剪切板权限问题）
     */
    private fun handleInputText(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val text = json.getString("value")
            
            // 确保UiAutomation已初始化
            ensureUiAutomationInitialized()
            if (!uiAutomationInitialized) {
                val response = JSONObject()
                response.put("code", 0)
                response.put("msg", "UiAutomation not available")
                response.put("data", "0")
                return newFixedLengthResponse(Status.OK, "application/json", response.toString())
            }
            
            // 在后台线程执行setText，避免阻塞HTTP请求
            Thread {
                try {
                    val success = uiAutomationService?.setText(text) ?: false
                    if (!success) {
                        println("⚠ inputText: setText返回false，可能是未找到输入框")
                    }
                } catch (e: Exception) {
                    println("✗ inputText 异常: ${e.message}")
                }
            }.start()
            
            // 立即返回成功（实际输入在后台执行）
            // 注意：如果当前没有输入框，setText会失败，但不会阻塞HTTP响应
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", "1")
            response.put("msg", "输入请求已提交（如果未找到输入框，输入将失败）")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 清除文本
     * GET /api/clearText
     * 实现方式：Ctrl+A全选 + Delete删除
     */
    private fun handleClearText(): Response {
        return try {
            // 1. Ctrl+A 全选
            val selectAllSuccess = InputManagerService.pressKey(
                KeyEvent.KEYCODE_A,
                KeyEvent.META_CTRL_ON
            )
            
            if (!selectAllSuccess) {
                val response = JSONObject()
                response.put("code", 0)
                response.put("msg", "Failed to select all")
                response.put("data", "0")
                return newFixedLengthResponse(Status.OK, "application/json", response.toString())
            }
            
            // 2. 等待一小段时间确保全选完成
            Thread.sleep(100)
            
            // 3. Delete 删除
            val deleteSuccess = InputManagerService.pressKey(KeyEvent.KEYCODE_DEL)
            
            val response = JSONObject()
            response.put("code", if (deleteSuccess) 1 else 0)
            response.put("data", if (deleteSuccess) "1" else "0")
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
            val packageName = params["packageName"] ?: params["package"] ?: return errorResponse("Missing packageName parameter")
            
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
            val packageName = params["packageName"] ?: params["package"] ?: return errorResponse("Missing packageName parameter")
            
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
            val command = json.optString("cmd", json.optString("value", ""))
            if (command.isEmpty()) {
                return errorResponse("Missing cmd parameter")
            }
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
            deviceDisplayName = json.optString("name", json.optString("value", ""))
            if (deviceDisplayName.isEmpty()) {
                return errorResponse("Missing name parameter")
            }
            
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
    
    // ==================== 应用管理扩展 API ====================
    
    /**
     * 停止应用
     * GET /api/stopPackage?packageName=com.example.app
     */
    private fun handleStopPackage(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val packageName = params["packageName"] ?: params["package"] ?: return errorResponse("Missing packageName parameter")
            
            val success = appManagementService?.stopPackage(packageName) ?: false
            
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
     * 清除应用数据
     * GET /api/clearPackage?packageName=com.example.app
     */
    private fun handleClearPackage(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val packageName = params["packageName"] ?: params["package"] ?: return errorResponse("Missing packageName parameter")
            
            val success = appManagementService?.clearPackage(packageName) ?: false
            
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
     * 获取所有应用
     * GET /api/getAllPackage
     */
    private fun handleGetAllPackage(): Response {
        return try {
            ensureContextServicesInitialized()
            val packages = appManagementService?.getAllPackagesWithInfo() ?: emptyList()
            
            // 将 Map 列表转换为 JSONArray
            val jsonArray = JSONArray()
            packages.forEach { packageInfo ->
                val jsonObject = JSONObject()
                packageInfo.forEach { (key, value) ->
                    jsonObject.put(key, value)
                }
                jsonArray.put(jsonObject)
            }
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", jsonArray)
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 获取应用详情
     * GET /api/getPackageInfo?packageName=com.example.app
     */
    private fun handleGetPackageInfo(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val packageName = params["packageName"] ?: params["package"] ?: return errorResponse("Missing packageName parameter")
            
            val packageInfo = appManagementService?.getPackageInfo(packageName)
            
            val response = JSONObject()
            if (packageInfo != null) {
                response.put("code", 1)
                response.put("data", JSONObject(packageInfo as Map<*, *>))
            } else {
                response.put("code", 0)
                response.put("data", null)
                response.put("message", "Failed to get package info")
            }
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 屏幕控制 API ====================
    
    /**
     * 熄屏
     * GET /api/turnScreenOff
     */
    private fun handleTurnScreenOff(): Response {
        return try {
            val success = InputManagerService.pressKey(KeyEvent.KEYCODE_POWER)
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
     * 亮屏
     * GET /api/turnScreenOn
     */
    private fun handleTurnScreenOn(): Response {
        return try {
            // 使用Power键唤醒屏幕
            val success = InputManagerService.pressKey(KeyEvent.KEYCODE_POWER)
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 多指手势 API ====================
    
    /**
     * 多指手势
     * POST /api/gestures
     * Body: {"duration": 200, "gestures": [[{"x": 100, "y": 200}, {"x": 300, "y": 400}], [{"x": 500, "y": 600}, {"x": 700, "y": 800}]]}
     */
    private fun handleGestures(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val duration = json.optLong("duration", 200)
            val gesturesArray = json.getJSONArray("gestures")
            
            // 解析多指手势数据
            val gestures = mutableListOf<List<InputService.Point>>()
            for (i in 0 until gesturesArray.length()) {
                val gestureArray = gesturesArray.getJSONArray(i)
                val points = mutableListOf<InputService.Point>()
                for (j in 0 until gestureArray.length()) {
                    val pointObj = gestureArray.getJSONObject(j)
                    points.add(InputService.Point(
                        pointObj.getDouble("x").toFloat(),
                        pointObj.getDouble("y").toFloat()
                    ))
                }
                gestures.add(points)
            }
            
            // 后台线程执行，避免阻塞HTTP请求
            Thread {
                try {
                    inputService?.multiGesture(duration, gestures)
                } catch (e: Exception) {
                    println("✗ 多指手势异常: ${e.message}")
                }
            }.start()
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", "1")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 截图 API（图片格式）====================
    
    /**
     * 截图（返回图片格式）
     * GET /api/screenShot
     */
    private fun handleScreenShot(session: IHTTPSession): Response {
        return try {
            val screenshotBytes = uiAutomationService?.takeScreenshotBytes()
            
            if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
                newFixedLengthResponse(
                    Status.OK,
                    "image/jpeg",
                    java.io.ByteArrayInputStream(screenshotBytes),
                    screenshotBytes.size.toLong()
                )
            } else {
                val error = """
                    {
                        "code": 0,
                        "data": null,
                        "message": "截图失败"
                    }
                """.trimIndent()
                newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
            }
        } catch (e: Exception) {
            println("screenShot 请求处理失败: ${e.message}")
            e.printStackTrace()
            val error = """
                {
                    "code": 0,
                    "data": null,
                    "message": "请求处理失败: ${e.message}"
                }
            """.trimIndent()
            newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", error)
        }
    }
    
    // ==================== 文件操作 API ====================
    
    /**
     * 列出文件
     * GET /api/listFile?path=/sdcard
     */
    private fun handleListFile(session: IHTTPSession): Response {
        return try {
            val params = session.parms
            val path = params["path"] ?: "/sdcard"
            
            val files = fileService.listFiles(path)
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", JSONArray(files.map { JSONObject(it as Map<*, *>) }))
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 上传文件
     * POST /api/upload
     * Body: {"path": "/sdcard/test.txt", "content": "base64encodedcontent"}
     */
    private fun handleUpload(session: IHTTPSession): Response {
        return try {
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val path = json.getString("path")
            val content = json.getString("content")
            
            val success = fileService.writeFileBase64(path, content)
            
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
     * 下载文件
     * GET /api/download?path=/sdcard/test.txt
     */
    private fun handleDownload(session: IHTTPSession): Response {
        return try {
            val params = session.parms
            val path = params["path"] ?: return errorResponse("Missing path parameter")
            
            val base64Content = fileService.readFileBase64(path)
            
            if (base64Content != null) {
                val response = JSONObject()
                response.put("code", 1)
                response.put("data", base64Content)
                newFixedLengthResponse(Status.OK, "application/json", response.toString())
            } else {
                errorResponse("File not found or read failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 删除文件
     * GET /api/delFile?path=/sdcard/test.txt
     */
    private fun handleDelFile(session: IHTTPSession): Response {
        return try {
            val params = session.parms
            val path = params["path"] ?: return errorResponse("Missing path parameter")
            
            val success = fileService.deleteFile(path)
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 联系人 API ====================
    
    /**
     * 获取所有联系人
     * GET /api/getAllContact
     */
    private fun handleGetAllContact(): Response {
        return try {
            ensureContextServicesInitialized()
            val contacts = contactsService?.getAllContacts() ?: emptyList()
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", JSONArray(contacts.map { JSONObject(it as Map<*, *>) }))
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 插入联系人
     * POST /api/insertContact
     * Body: {"name": "张三", "phone": "13800138000"}
     */
    private fun handleInsertContact(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val name = json.getString("name")
            val phone = json.getString("phone")
            
            val success = contactsService?.insertContact(name, phone) ?: false
            
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
     * 删除联系人
     * GET /api/deleteContact?contactId=123
     */
    private fun handleDeleteContact(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val contactId = params["contactId"] ?: return errorResponse("Missing contactId parameter")
            
            val success = contactsService?.deleteContact(contactId) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 短信 API ====================
    
    /**
     * 获取所有短信
     * GET /api/getAllSms
     */
    private fun handleGetAllSms(): Response {
        return try {
            ensureContextServicesInitialized()
            val smsList = smsService?.getAllSms() ?: emptyList()
            
            val response = JSONObject()
            response.put("code", 1)
            response.put("data", JSONArray(smsList.map { JSONObject(it as Map<*, *>) }))
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    /**
     * 发送短信
     * POST /api/sendSms
     * Body: {"phone": "13800138000", "message": "测试短信"}
     */
    private fun handleSendSms(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val body = readRequestBody(session)
            val json = JSONObject(body)
            val phone = json.getString("phone")
            val message = json.getString("message")
            
            val success = smsService?.sendSms(phone, message) ?: false
            
            val response = JSONObject()
            response.put("code", if (success) 1 else 0)
            response.put("data", if (success) "1" else "0")
            newFixedLengthResponse(Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            e.printStackTrace()
            errorResponse(e.message ?: "Unknown error")
        }
    }
    
    // ==================== 电话 API ====================
    
    /**
     * 拨打电话
     * GET /api/callPhone?phone=13800138000
     */
    private fun handleCallPhone(session: IHTTPSession): Response {
        return try {
            ensureContextServicesInitialized()
            val params = session.parms
            val phone = params["phone"] ?: return errorResponse("Missing phone parameter")
            
            val success = phoneService?.callPhone(phone) ?: false
            
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
     * 挂断电话
     * GET /api/endCall
     */
    private fun handleEndCall(): Response {
        return try {
            ensureContextServicesInitialized()
            val success = phoneService?.endCall() ?: false
            
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

