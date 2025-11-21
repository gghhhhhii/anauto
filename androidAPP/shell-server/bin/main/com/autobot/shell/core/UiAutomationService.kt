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
     * 创建 UiAutomationConnection
     */
    private fun createUiAutomationConnection(): Any? {
        return try {
            // 使用反射调用 ServiceManager.getService("accessibility")
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val accessibilityService = getServiceMethod.invoke(null, "accessibility")

            if (accessibilityService == null) {
                println("✗ 无法获取 accessibility 服务")
                return null
            }

            // 获取 IAccessibilityManager
            val iAccessibilityManagerClass = Class.forName("android.view.accessibility.IAccessibilityManager\$Stub")
            val asInterfaceMethod = iAccessibilityManagerClass.getMethod("asInterface", Class.forName("android.os.IBinder"))
            val accessibilityManager = asInterfaceMethod.invoke(null, accessibilityService)

            // 注册 UiAutomationConnection
            val iUiAutomationConnectionClass = Class.forName("android.app.IUiAutomationConnection")
            val registerMethod = accessibilityManager.javaClass.getMethod(
                "registerUiTestAutomationService",
                Class.forName("android.os.IBinder"),
                iUiAutomationConnectionClass,
                Class.forName("android.accessibilityservice.AccessibilityServiceInfo"),
                Int::class.javaPrimitiveType
            )

            // 创建一个空的 IUiAutomationConnection 代理
            val connectionProxy = Proxy.newProxyInstance(
                iUiAutomationConnectionClass.classLoader,
                arrayOf(iUiAutomationConnectionClass)
            ) { proxy, method, args ->
                // 简单返回 null 或默认值
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }

            // 注册
            val binderClass = Class.forName("android.os.Binder")
            val binderConstructor = binderClass.getConstructor()
            val binder = binderConstructor.newInstance()

            registerMethod.invoke(accessibilityManager, binder, connectionProxy, null, 0)

            println("✓ UiAutomationConnection 已创建")
            connectionProxy
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
     */
    fun dumpXML(waitForIdle: Boolean, visibleOnly: Boolean): String {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化")
            return ""
        }

        return try {
            // 等待 UI 稳定
            if (waitForIdle) {
                waitForIdleMethod?.invoke(uiAutomation, 1000L, 3000L)
            }

            // 获取根节点
            val rootNode = getRootInActiveWindowMethod?.invoke(uiAutomation)
            if (rootNode == null) {
                println("✗ 无法获取根节点")
                return ""
            }

            // 生成 XML
            val xml = buildXmlString(rootNode, 0, visibleOnly)

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
     */
    fun dumpJSON(waitForIdle: Boolean, visibleOnly: Boolean): String {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化")
            return "{}"
        }

        return try {
            // 等待 UI 稳定
            if (waitForIdle) {
                waitForIdleMethod?.invoke(uiAutomation, 1000L, 3000L)
            }

            // 获取根节点
            val rootNode = getRootInActiveWindowMethod?.invoke(uiAutomation)
            if (rootNode == null) {
                println("✗ 无法获取根节点")
                return "{}"
            }

            // 生成 JSON
            val json = buildJsonObject(rootNode, visibleOnly)

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
     * 构建 XML 字符串
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

