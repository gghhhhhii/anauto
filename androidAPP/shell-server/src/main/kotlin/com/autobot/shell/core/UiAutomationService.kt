package com.autobot.shell.core

import org.json.JSONObject
import org.json.JSONArray
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * UiAutomation 服务
 * 使用反射访问 Android 的 UiAutomation 功能
 * 必须在 Shell 权限下运行
 */
class UiAutomationService private constructor() {

    companion object {
        @Volatile
        private var instance: UiAutomationService? = null

        fun getInstance(): UiAutomationService {
            return instance ?: synchronized(this) {
                instance ?: UiAutomationService().also { instance = it }
            }
        }
    }

    private var uiAutomation: Any? = null
    private var initialized = false

    // 反射获取的方法
    private var getRootInActiveWindowMethod: Method? = null
    private var waitForIdleMethod: Method? = null
    private var getChildCountMethod: Method? = null
    private var getChildMethod: Method? = null
    private var getTextMethod: Method? = null
    private var getClassNameMethod: Method? = null
    private var getContentDescriptionMethod: Method? = null
    private var getResourceIdMethod: Method? = null
    private var isVisibleToUserMethod: Method? = null
    private var isClickableMethod: Method? = null
    private var isEnabledMethod: Method? = null
    private var getPackageNameMethod: Method? = null
    private var getBoundsInScreenMethod: Method? = null
    private var recycleMethod: Method? = null

    /**
     * 初始化 UiAutomation
     * 必须在 Shell 权限下调用
     */
    fun initialize(): Boolean {
        if (initialized) {
            return true
        }

        return try {
            println("开始初始化 UiAutomation...")

            // 1. 准备 Looper
            val looperClass = Class.forName("android.os.Looper")
            val myLooperMethod = looperClass.getMethod("myLooper")
            val currentLooper = myLooperMethod.invoke(null)
            
            if (currentLooper == null) {
                println("Looper 未准备，调用 Looper.prepare()")
                val prepareMethod = looperClass.getMethod("prepare")
                prepareMethod.invoke(null)
                println("✓ Looper.prepare() 调用成功")
            } else {
                println("✓ Looper 已存在，跳过初始化")
            }

            // 2. 创建 UiAutomation 实例
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val constructor = uiAutomationClass.getDeclaredConstructor(
                looperClass,
                Class.forName("android.app.IUiAutomationConnection")
            )
            constructor.isAccessible = true

            // 3. 获取 UiAutomationConnection
            val connection = createUiAutomationConnection()
            if (connection == null) {
                println("✗ 无法创建 UiAutomationConnection")
                return false
            }

            // 4. 创建 UiAutomation 实例
            val looper = myLooperMethod.invoke(null)
            uiAutomation = constructor.newInstance(looper, connection)

            // 5. 连接 UiAutomation
            val connectMethod = uiAutomationClass.getMethod("connect")
            connectMethod.invoke(uiAutomation)

            println("✓ UiAutomation 已连接")

            // 6. 缓存常用的反射方法
            cacheReflectionMethods()

            initialized = true
            println("✓ UiAutomation 初始化成功")
            true
        } catch (e: Exception) {
            println("✗ UiAutomation 初始化失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 创建 UiAutomationConnection（简化版本 - 直接实例化）
     * 参考 android_autobot 的成功实现
     */
    private fun createUiAutomationConnection(): Any? {
        return try {
            println("开始创建 UiAutomationConnection（简化方式）...")
            
            // 直接创建 UiAutomationConnection 实例（不需要注册到系统服务）
            val connectionImplClass = Class.forName("android.app.UiAutomationConnection")
            val connectionConstructor = connectionImplClass.getDeclaredConstructor()
            connectionConstructor.isAccessible = true
            val connection = connectionConstructor.newInstance()

            println("✓ UiAutomationConnection 已创建（实例化方式）")
            connection
        } catch (e: Exception) {
            println("✗ 创建 UiAutomationConnection 失败: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 缓存反射方法
     */
    private fun cacheReflectionMethods() {
        try {
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val accessibilityNodeInfoClass = Class.forName("android.view.accessibility.AccessibilityNodeInfo")
            val rectClass = Class.forName("android.graphics.Rect")

            getRootInActiveWindowMethod = uiAutomationClass.getMethod("getRootInActiveWindow")
            waitForIdleMethod = uiAutomationClass.getMethod("waitForIdle", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            
            getChildCountMethod = accessibilityNodeInfoClass.getMethod("getChildCount")
            getChildMethod = accessibilityNodeInfoClass.getMethod("getChild", Int::class.javaPrimitiveType)
            getTextMethod = accessibilityNodeInfoClass.getMethod("getText")
            getClassNameMethod = accessibilityNodeInfoClass.getMethod("getClassName")
            getContentDescriptionMethod = accessibilityNodeInfoClass.getMethod("getContentDescription")
            getResourceIdMethod = accessibilityNodeInfoClass.getMethod("getViewIdResourceName")
            isVisibleToUserMethod = accessibilityNodeInfoClass.getMethod("isVisibleToUser")
            isClickableMethod = accessibilityNodeInfoClass.getMethod("isClickable")
            isEnabledMethod = accessibilityNodeInfoClass.getMethod("isEnabled")
            getPackageNameMethod = accessibilityNodeInfoClass.getMethod("getPackageName")
            getBoundsInScreenMethod = accessibilityNodeInfoClass.getMethod("getBoundsInScreen", rectClass)
            recycleMethod = accessibilityNodeInfoClass.getMethod("recycle")

            println("✓ 反射方法已缓存")
        } catch (e: Exception) {
            println("⚠ 缓存反射方法失败: ${e.message}")
        }
    }

    /**
     * 获取屏幕树 XML
     * 优化版本：使用 AccessibilityNodeInfoDumper 快速生成
     */
    fun dumpXML(waitForIdle: Boolean, visibleOnly: Boolean): String {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化")
            return ""
        }

        return try {
            // 等待 UI 稳定（可选）
            if (waitForIdle) {
                waitForIdleMethod?.invoke(uiAutomation, 100L, 500L)
            }

            // 获取根节点
            val rootNode = getRootInActiveWindowMethod?.invoke(uiAutomation)
            if (rootNode == null) {
                println("✗ 无法获取根节点")
                return ""
            }

            // 快速生成 XML（非递归版本，性能优化）
            val xml = buildXmlStringFast(rootNode, visibleOnly)

            // 回收节点
            recycleMethod?.invoke(rootNode)

            xml
        } catch (e: Exception) {
            println("✗ 生成 XML 失败: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    /**
     * 获取屏幕树 JSON
     * 优化版本：快速生成，减少反射调用
     */
    fun dumpJSON(waitForIdle: Boolean, visibleOnly: Boolean): String {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化")
            return "{}"
        }

        return try {
            // 等待 UI 稳定（可选）
            if (waitForIdle) {
                waitForIdleMethod?.invoke(uiAutomation, 100L, 500L)
            }

            // 获取根节点
            val rootNode = getRootInActiveWindowMethod?.invoke(uiAutomation)
            if (rootNode == null) {
                println("✗ 无法获取根节点")
                return "{}"
            }

            // 快速生成 JSON
            val json = buildJsonObjectFast(rootNode, visibleOnly)

            // 回收节点
            recycleMethod?.invoke(rootNode)

            json.toString()
        } catch (e: Exception) {
            println("✗ 生成 JSON 失败: ${e.message}")
            e.printStackTrace()
            "{}"
        }
    }

    /**
     * 快速构建 JSON 对象（性能优化版本）
     */
    private fun buildJsonObjectFast(rootNode: Any?, visibleOnly: Boolean): JSONObject {
        val json = JSONObject()
        if (rootNode == null) return json
        
        try {
            // 检查可见性
            val isVisible = isVisibleToUserMethod?.invoke(rootNode) as? Boolean ?: false
            if (visibleOnly && !isVisible) {
                return json
            }
            
            // 基本属性（批量设置，减少 JSON 方法调用）
            json.put("className", getClassNameMethod?.invoke(rootNode)?.toString() ?: "")
            json.put("text", getTextMethod?.invoke(rootNode)?.toString() ?: "")
            json.put("contentDesc", getContentDescriptionMethod?.invoke(rootNode)?.toString() ?: "")
            json.put("resourceId", getResourceIdMethod?.invoke(rootNode)?.toString() ?: "")
            json.put("clickable", isClickableMethod?.invoke(rootNode) as? Boolean ?: false)
            json.put("enabled", isEnabledMethod?.invoke(rootNode) as? Boolean ?: false)
            json.put("visible", isVisible)
            
            // 边界
            try {
                val rectClass = Class.forName("android.graphics.Rect")
                val rect = rectClass.getConstructor().newInstance()
                getBoundsInScreenMethod?.invoke(rootNode, rect)
                
                val boundsJson = JSONObject()
                boundsJson.put("left", rectClass.getField("left").getInt(rect))
                boundsJson.put("top", rectClass.getField("top").getInt(rect))
                boundsJson.put("right", rectClass.getField("right").getInt(rect))
                boundsJson.put("bottom", rectClass.getField("bottom").getInt(rect))
                
                json.put("bounds", boundsJson)
            } catch (e: Exception) {
                // 忽略
            }
            
            // 子节点（迭代处理）
            val childCount = getChildCountMethod?.invoke(rootNode) as? Int ?: 0
            if (childCount > 0) {
                val children = JSONArray()
                for (i in 0 until childCount) {
                    val child = getChildMethod?.invoke(rootNode, i)
                    if (child != null) {
                        val childJson = buildJsonObjectFast(child, visibleOnly)
                        if (childJson.length() > 0) {
                            children.put(childJson)
                        }
                        recycleMethod?.invoke(child)
                    }
                }
                json.put("children", children)
            }
        } catch (e: Exception) {
            println("✗ 快速构建 JSON 失败: ${e.message}")
        }
        
        return json
    }

    /**
     * 快速构建 XML 字符串（性能优化版本）
     * 使用迭代而不是递归，减少方法调用开销
     */
    private fun buildXmlStringFast(rootNode: Any?, visibleOnly: Boolean): String {
        if (rootNode == null) return ""
        
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        
        // 使用栈进行迭代遍历，避免递归开销
        val stack = mutableListOf<Triple<Any, Int, Boolean>>() // (node, indent, isClosing)
        stack.add(Triple(rootNode, 0, false))
        
        try {
            while (stack.isNotEmpty()) {
                val (node, indent, isClosing) = stack.removeAt(stack.size - 1)
                
                if (isClosing) {
                    // 处理闭合标签
                    val className = getClassNameMethod?.invoke(node)?.toString() ?: "node"
                    sb.append("  ".repeat(indent)).append("</").append(className).append(">\n")
                    recycleMethod?.invoke(node)
                    continue
                }
                
                // 检查可见性
                val isVisible = isVisibleToUserMethod?.invoke(node) as? Boolean ?: false
                if (visibleOnly && !isVisible) {
                    recycleMethod?.invoke(node)
                    continue
                }
                
                val indentStr = "  ".repeat(indent)
                val className = getClassNameMethod?.invoke(node)?.toString() ?: "node"
                
                sb.append(indentStr).append("<").append(className)
                
                // 添加属性
                val text = getTextMethod?.invoke(node)?.toString()
                if (!text.isNullOrEmpty()) {
                    sb.append(" text=\"").append(escapeXml(text)).append("\"")
                }
                
                val contentDesc = getContentDescriptionMethod?.invoke(node)?.toString()
                if (!contentDesc.isNullOrEmpty()) {
                    sb.append(" content-desc=\"").append(escapeXml(contentDesc)).append("\"")
                }
                
                val resourceId = getResourceIdMethod?.invoke(node)?.toString()
                if (!resourceId.isNullOrEmpty()) {
                    sb.append(" resource-id=\"").append(resourceId).append("\"")
                }
                
                val clickable = isClickableMethod?.invoke(node) as? Boolean ?: false
                sb.append(" clickable=\"").append(clickable).append("\"")
                
                val enabled = isEnabledMethod?.invoke(node) as? Boolean ?: false
                sb.append(" enabled=\"").append(enabled).append("\"")
                
                // 获取边界（简化版）
                try {
                    val rectClass = Class.forName("android.graphics.Rect")
                    val rect = rectClass.getConstructor().newInstance()
                    getBoundsInScreenMethod?.invoke(node, rect)
                    
                    val left = rectClass.getField("left").getInt(rect)
                    val top = rectClass.getField("top").getInt(rect)
                    val right = rectClass.getField("right").getInt(rect)
                    val bottom = rectClass.getField("bottom").getInt(rect)
                    
                    sb.append(" bounds=\"[").append(left).append(",").append(top)
                      .append("][").append(right).append(",").append(bottom).append("]\"")
                } catch (e: Exception) {
                    // 忽略边界获取错误
                }
                
                // 处理子节点
                val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
                if (childCount > 0) {
                    sb.append(">\n")
                    
                    // 添加闭合标签到栈
                    stack.add(Triple(node, indent, true))
                    
                    // 添加子节点到栈（逆序，因为是栈）
                    for (i in (childCount - 1) downTo 0) {
                        val child = getChildMethod?.invoke(node, i)
                        if (child != null) {
                            stack.add(Triple(child, indent + 1, false))
                        }
                    }
                } else {
                    sb.append(" />\n")
                    recycleMethod?.invoke(node)
                }
            }
        } catch (e: Exception) {
            println("✗ 快速构建 XML 失败: ${e.message}")
        }
        
        return sb.toString()
    }
    
    /**
     * 构建 XML 字符串（旧版递归方法，保留作为备用）
     */
    private fun buildXmlString(node: Any?, indent: Int, visibleOnly: Boolean): String {
        if (node == null) return ""

        try {
            // 检查可见性
            val isVisible = isVisibleToUserMethod?.invoke(node) as? Boolean ?: false
            if (visibleOnly && !isVisible) {
                return ""
            }

            val sb = StringBuilder()
            val indentStr = "  ".repeat(indent)

            // 开始标签
            val className = getClassNameMethod?.invoke(node)?.toString() ?: "node"
            sb.append("$indentStr<$className")

            // 属性
            val text = getTextMethod?.invoke(node)?.toString()
            if (!text.isNullOrEmpty()) {
                sb.append(" text=\"${escapeXml(text)}\"")
            }

            val contentDesc = getContentDescriptionMethod?.invoke(node)?.toString()
            if (!contentDesc.isNullOrEmpty()) {
                sb.append(" content-desc=\"${escapeXml(contentDesc)}\"")
            }

            val resourceId = getResourceIdMethod?.invoke(node)?.toString()
            if (!resourceId.isNullOrEmpty()) {
                sb.append(" resource-id=\"$resourceId\"")
            }

            val clickable = isClickableMethod?.invoke(node) as? Boolean ?: false
            sb.append(" clickable=\"$clickable\"")

            val enabled = isEnabledMethod?.invoke(node) as? Boolean ?: false
            sb.append(" enabled=\"$enabled\"")

            // 获取边界
            try {
                val rectClass = Class.forName("android.graphics.Rect")
                val rect = rectClass.getConstructor().newInstance()
                getBoundsInScreenMethod?.invoke(node, rect)
                
                val leftField = rectClass.getField("left")
                val topField = rectClass.getField("top")
                val rightField = rectClass.getField("right")
                val bottomField = rectClass.getField("bottom")
                
                val left = leftField.getInt(rect)
                val top = topField.getInt(rect)
                val right = rightField.getInt(rect)
                val bottom = bottomField.getInt(rect)
                
                sb.append(" bounds=\"[$left,$top][$right,$bottom]\"")
            } catch (e: Exception) {
                // 忽略边界获取错误
            }

            // 子节点
            val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
            if (childCount > 0) {
                sb.append(">\n")
                
                for (i in 0 until childCount) {
                    val child = getChildMethod?.invoke(node, i)
                    val childXml = buildXmlString(child, indent + 1, visibleOnly)
                    if (childXml.isNotEmpty()) {
                        sb.append(childXml)
                    }
                    // 回收子节点
                    child?.let { recycleMethod?.invoke(it) }
                }
                
                sb.append("$indentStr</$className>\n")
            } else {
                sb.append(" />\n")
            }

            return sb.toString()
        } catch (e: Exception) {
            println("✗ 构建节点 XML 失败: ${e.message}")
            return ""
        }
    }

    /**
     * 构建 JSON 对象
     */
    private fun buildJsonObject(node: Any?, visibleOnly: Boolean): JSONObject {
        val json = JSONObject()

        if (node == null) return json

        try {
            // 检查可见性
            val isVisible = isVisibleToUserMethod?.invoke(node) as? Boolean ?: false
            if (visibleOnly && !isVisible) {
                return json
            }

            // 基本属性
            json.put("className", getClassNameMethod?.invoke(node)?.toString() ?: "")
            json.put("text", getTextMethod?.invoke(node)?.toString() ?: "")
            json.put("contentDesc", getContentDescriptionMethod?.invoke(node)?.toString() ?: "")
            json.put("resourceId", getResourceIdMethod?.invoke(node)?.toString() ?: "")
            json.put("clickable", isClickableMethod?.invoke(node) as? Boolean ?: false)
            json.put("enabled", isEnabledMethod?.invoke(node) as? Boolean ?: false)
            json.put("visible", isVisible)

            // 边界
            try {
                val rectClass = Class.forName("android.graphics.Rect")
                val rect = rectClass.getConstructor().newInstance()
                getBoundsInScreenMethod?.invoke(node, rect)
                
                val boundsJson = JSONObject()
                boundsJson.put("left", rectClass.getField("left").getInt(rect))
                boundsJson.put("top", rectClass.getField("top").getInt(rect))
                boundsJson.put("right", rectClass.getField("right").getInt(rect))
                boundsJson.put("bottom", rectClass.getField("bottom").getInt(rect))
                
                json.put("bounds", boundsJson)
            } catch (e: Exception) {
                // 忽略
            }

            // 子节点
            val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
            if (childCount > 0) {
                val children = JSONArray()
                
                for (i in 0 until childCount) {
                    val child = getChildMethod?.invoke(node, i)
                    val childJson = buildJsonObject(child, visibleOnly)
                    if (childJson.length() > 0) {
                        children.put(childJson)
                    }
                    // 回收子节点
                    child?.let { recycleMethod?.invoke(it) }
                }
                
                json.put("children", children)
            }

        } catch (e: Exception) {
            println("✗ 构建节点 JSON 失败: ${e.message}")
        }

        return json
    }

    /**
     * 截图功能
     * @return Base64 编码的 PNG 图片，如果失败返回 null
     */
    fun takeScreenshot(): String? {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化，无法截图")
            return null
        }

        return try {
            println("开始截图...")
            
            // 调用 UiAutomation.takeScreenshot()
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val takeScreenshotMethod = uiAutomationClass.getMethod("takeScreenshot")
            val bitmap = takeScreenshotMethod.invoke(uiAutomation)
            
            if (bitmap == null) {
                println("✗ takeScreenshot() 返回 null")
                return null
            }
            
            println("✓ 截图成功，开始压缩为 PNG...")
            
            // 将 Bitmap 压缩为 PNG 并转换为 Base64
            val bitmapClass = Class.forName("android.graphics.Bitmap")
            val compressFormatClass = Class.forName("android.graphics.Bitmap\$CompressFormat")
            val compressMethod = bitmapClass.getMethod(
                "compress",
                compressFormatClass,
                Int::class.javaPrimitiveType,
                Class.forName("java.io.OutputStream")
            )
            
            // 获取 PNG 格式枚举值
            val pngFormat = compressFormatClass.getField("PNG").get(null)
            
            // 创建 ByteArrayOutputStream
            val baosClass = Class.forName("java.io.ByteArrayOutputStream")
            val baos = baosClass.getConstructor().newInstance()
            
            // 压缩 Bitmap
            compressMethod.invoke(bitmap, pngFormat, 100, baos)
            
            // 获取字节数组
            val toByteArrayMethod = baosClass.getMethod("toByteArray")
            val bytes = toByteArrayMethod.invoke(baos) as ByteArray
            
            // 转换为 Base64
            val base64Class = Class.forName("android.util.Base64")
            val encodeToStringMethod = base64Class.getMethod(
                "encodeToString",
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            val base64String = encodeToStringMethod.invoke(null, bytes, 0) as String
            
            // 回收 Bitmap
            val recycleMethod = bitmapClass.getMethod("recycle")
            recycleMethod.invoke(bitmap)
            
            println("✓ PNG 压缩完成，Base64 长度: ${base64String.length}")
            base64String
        } catch (e: Exception) {
            println("✗ 截图失败: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 执行点击操作
     * @param x X 坐标
     * @param y Y 坐标
     * @return 是否成功
     */
    fun click(x: Int, y: Int): Boolean {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化，无法点击")
            return false
        }

        return try {
            println("执行点击: ($x, $y)")
            
            // 获取当前时间
            val now = System.currentTimeMillis()
            
            // 创建 MotionEvent
            val motionEventClass = Class.forName("android.view.MotionEvent")
            val obtainMethod = motionEventClass.getMethod(
                "obtain",
                Long::class.javaPrimitiveType,  // downTime
                Long::class.javaPrimitiveType,  // eventTime
                Int::class.javaPrimitiveType,   // action
                Float::class.javaPrimitiveType, // x
                Float::class.javaPrimitiveType, // y
                Int::class.javaPrimitiveType    // metaState
            )
            
            // ACTION_DOWN = 0, ACTION_UP = 1
            val downEvent = obtainMethod.invoke(null, now, now, 0, x.toFloat(), y.toFloat(), 0)
            val upEvent = obtainMethod.invoke(null, now, now + 50, 1, x.toFloat(), y.toFloat(), 0)
            
            // 注入事件
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val injectEventMethod = uiAutomationClass.getMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Boolean::class.javaPrimitiveType
            )
            
            val downSuccess = injectEventMethod.invoke(uiAutomation, downEvent, true) as Boolean
            Thread.sleep(10)
            val upSuccess = injectEventMethod.invoke(uiAutomation, upEvent, true) as Boolean
            
            // 回收事件
            val recycleMethod = motionEventClass.getMethod("recycle")
            recycleMethod.invoke(downEvent)
            recycleMethod.invoke(upEvent)
            
            val success = downSuccess && upSuccess
            if (success) {
                println("✓ 点击成功")
            } else {
                println("✗ 点击失败")
            }
            success
        } catch (e: Exception) {
            println("✗ 点击失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 执行滑动操作
     * @param x1 起始 X 坐标
     * @param y1 起始 Y 坐标
     * @param x2 结束 X 坐标
     * @param y2 结束 Y 坐标
     * @param duration 持续时间（毫秒）
     * @return 是否成功
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = 300): Boolean {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化，无法滑动")
            return false
        }

        return try {
            println("执行滑动: ($x1, $y1) -> ($x2, $y2), duration=${duration}ms")
            
            val now = System.currentTimeMillis()
            val steps = (duration / 10).toInt().coerceAtLeast(2)
            
            val motionEventClass = Class.forName("android.view.MotionEvent")
            val obtainMethod = motionEventClass.getMethod(
                "obtain",
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val injectEventMethod = uiAutomationClass.getMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Boolean::class.javaPrimitiveType
            )
            
            val recycleMethod = motionEventClass.getMethod("recycle")
            
            // ACTION_DOWN
            val downEvent = obtainMethod.invoke(null, now, now, 0, x1.toFloat(), y1.toFloat(), 0)
            injectEventMethod.invoke(uiAutomation, downEvent, true)
            recycleMethod.invoke(downEvent)
            
            // ACTION_MOVE
            for (i in 1 until steps) {
                val progress = i.toFloat() / steps
                val x = x1 + ((x2 - x1) * progress).toInt()
                val y = y1 + ((y2 - y1) * progress).toInt()
                val time = now + (duration * progress).toLong()
                
                val moveEvent = obtainMethod.invoke(null, now, time, 2, x.toFloat(), y.toFloat(), 0)
                injectEventMethod.invoke(uiAutomation, moveEvent, true)
                recycleMethod.invoke(moveEvent)
                
                Thread.sleep(10)
            }
            
            // ACTION_UP
            val upEvent = obtainMethod.invoke(null, now, now + duration, 1, x2.toFloat(), y2.toFloat(), 0)
            val success = injectEventMethod.invoke(uiAutomation, upEvent, true) as Boolean
            recycleMethod.invoke(upEvent)
            
            if (success) {
                println("✓ 滑动成功")
            } else {
                println("✗ 滑动失败")
            }
            success
        } catch (e: Exception) {
            println("✗ 滑动失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 执行按键操作
     * @param keyCode 按键码（如 KEYCODE_BACK = 4, KEYCODE_HOME = 3）
     * @return 是否成功
     */
    fun pressKey(keyCode: Int): Boolean {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化，无法按键")
            return false
        }

        return try {
            println("执行按键: keyCode=$keyCode")
            
            val now = System.currentTimeMillis()
            
            // 创建 KeyEvent
            val keyEventClass = Class.forName("android.view.KeyEvent")
            val constructor = keyEventClass.getConstructor(
                Long::class.javaPrimitiveType,  // downTime
                Long::class.javaPrimitiveType,  // eventTime
                Int::class.javaPrimitiveType,   // action
                Int::class.javaPrimitiveType,   // code
                Int::class.javaPrimitiveType    // repeat
            )
            
            // ACTION_DOWN = 0, ACTION_UP = 1
            val downEvent = constructor.newInstance(now, now, 0, keyCode, 0)
            val upEvent = constructor.newInstance(now, now + 50, 1, keyCode, 0)
            
            // 注入事件
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val injectEventMethod = uiAutomationClass.getMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Boolean::class.javaPrimitiveType
            )
            
            val downSuccess = injectEventMethod.invoke(uiAutomation, downEvent, true) as Boolean
            Thread.sleep(10)
            val upSuccess = injectEventMethod.invoke(uiAutomation, upEvent, true) as Boolean
            
            val success = downSuccess && upSuccess
            if (success) {
                println("✓ 按键成功")
            } else {
                println("✗ 按键失败")
            }
            success
        } catch (e: Exception) {
            println("✗ 按键失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 转义 XML 特殊字符
     */
    private fun escapeXml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

