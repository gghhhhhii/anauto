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
    
    // 缓存的根节点（参考应用的优化）
    @Volatile
    private var cachedRootNode: Any? = null

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
    
    // 补充的反射方法
    private var isCheckableMethod: Method? = null
    private var isCheckedMethod: Method? = null
    private var isFocusableMethod: Method? = null
    private var isFocusedMethod: Method? = null
    private var isScrollableMethod: Method? = null
    private var isLongClickableMethod: Method? = null
    private var isPasswordMethod: Method? = null
    private var isSelectedMethod: Method? = null

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
            
            // 7. 注册事件监听器，实时更新根节点缓存（参考应用的优化）
            registerAccessibilityEventListener()

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
            
            // 补充的方法
            isCheckableMethod = accessibilityNodeInfoClass.getMethod("isCheckable")
            isCheckedMethod = accessibilityNodeInfoClass.getMethod("isChecked")
            isFocusableMethod = accessibilityNodeInfoClass.getMethod("isFocusable")
            isFocusedMethod = accessibilityNodeInfoClass.getMethod("isFocused")
            isScrollableMethod = accessibilityNodeInfoClass.getMethod("isScrollable")
            isLongClickableMethod = accessibilityNodeInfoClass.getMethod("isLongClickable")
            isPasswordMethod = accessibilityNodeInfoClass.getMethod("isPassword")
            isSelectedMethod = accessibilityNodeInfoClass.getMethod("isSelected")

            println("✓ 反射方法已缓存")
        } catch (e: Exception) {
            println("⚠ 缓存反射方法失败: ${e.message}")
        }
    }
    
    /**
     * 注册 AccessibilityEvent 监听器，实时更新根节点缓存
     * 这是参考应用的核心优化：每次窗口变化时自动更新缓存
     */
    private fun registerAccessibilityEventListener() {
        try {
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val listenerClass = Class.forName("android.app.UiAutomation\$OnAccessibilityEventListener")
            
            // 创建监听器代理
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onAccessibilityEvent" && args != null && args.isNotEmpty()) {
                    val event = args[0]
                    val eventClass = Class.forName("android.view.accessibility.AccessibilityEvent")
                    val getEventTypeMethod = eventClass.getMethod("getEventType")
                    val eventType = getEventTypeMethod.invoke(event) as Int
                    
                    // TYPE_WINDOW_STATE_CHANGED (32) 或 TYPE_WINDOW_CONTENT_CHANGED (8)
                    // 参考应用的事件类型
                    if (eventType == 32 || eventType == 8 || eventType == 2048) {
                        // 获取并缓存新的根节点
                        try {
                            val newRootNode = getRootInActiveWindowMethod?.invoke(uiAutomation)
                            if (newRootNode != null) {
                                // 回收旧的缓存节点
                                cachedRootNode?.let { oldNode ->
                                    try {
                                        recycleMethod?.invoke(oldNode)
                                    } catch (e: Exception) {
                                        // 忽略回收错误
                                    }
                                }
                                cachedRootNode = newRootNode
                            }
                        } catch (e: Exception) {
                            // 忽略更新错误
                        }
                    }
                }
                null
            }
            
            // 注册监听器
            val setOnAccessibilityEventListenerMethod = uiAutomationClass.getMethod(
                "setOnAccessibilityEventListener",
                listenerClass
            )
            setOnAccessibilityEventListenerMethod.invoke(uiAutomation, listener)
            
            println("✓ AccessibilityEvent 监听器已注册（根节点自动缓存）")
        } catch (e: Exception) {
            println("⚠ 注册事件监听器失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 获取根节点（带缓存回退机制）
     * 参考应用的实现：如果获取失败，使用缓存的根节点
     */
    private fun getRootNodeWithCache(): Any? {
        return try {
            // 首先尝试获取新的根节点
            val rootNode = getRootInActiveWindowMethod?.invoke(uiAutomation)
            if (rootNode != null) {
                rootNode
            } else {
                // 获取失败，使用缓存的根节点
                println("⚠ 获取根节点失败，使用缓存")
                cachedRootNode
            }
        } catch (e: Exception) {
            // 异常时也使用缓存
            println("⚠ 获取根节点异常，使用缓存: ${e.message}")
            cachedRootNode
        }
    }

    /**
     * 获取屏幕树 XML
     * 优化版本：参考原生 uiautomator dump 实现 + 根节点缓存
     */
    fun dumpXML(waitForIdle: Boolean, visibleOnly: Boolean): String {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化")
            return ""
        }

        return try {
            // ⚡ 参考应用的优化：默认不等待 UI 空闲，使用缓存的根节点
            if (waitForIdle) {
                waitForIdleMethod?.invoke(uiAutomation, 100L, 500L)
            }

            // 获取根节点（带缓存回退）
            val rootNode = getRootNodeWithCache()
            if (rootNode == null) {
                println("✗ 无法获取根节点（缓存也为空）")
                return ""
            }

            // 使用 XmlSerializer 快速生成 XML（参考 uiautomator 实现）
            val xml = dumpNodeToXml(rootNode, visibleOnly)

            // ⚠️ 不回收根节点（如果是缓存的，不能回收）
            // 参考应用也不回收根节点

            xml
        } catch (e: Exception) {
            println("✗ 生成 XML 失败: ${e.message}")
            e.printStackTrace()
            ""
        }
    }
    
    /**
     * 使用 XmlSerializer 导出节点树（参考 uiautomator 实现）
     */
    private fun dumpNodeToXml(rootNode: Any?, visibleOnly: Boolean): String {
        val writer = java.io.StringWriter()
        val serializer = android.util.Xml.newSerializer()
        
        try {
            serializer.setOutput(writer)
            serializer.startDocument("UTF-8", true)
            serializer.startTag("", "hierarchy")
            
            // 序列化节点树
            serializeNode(rootNode, serializer, visibleOnly, 0)
            
            serializer.endTag("", "hierarchy")
            serializer.endDocument()
            serializer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return writer.toString()
    }
    
    /**
     * 递归序列化节点（参考 uiautomator 实现，立即释放子节点）
     */
    private fun serializeNode(node: Any?, serializer: org.xmlpull.v1.XmlSerializer, visibleOnly: Boolean, depth: Int) {
        if (node == null || depth > 200) return
        
        // 检查可见性（在打开tag之前检查，避免XML结构不匹配）
        val isVisible = try {
            isVisibleToUserMethod?.invoke(node) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
        if (visibleOnly && !isVisible) return
        
        var tagOpened = false
        try {
            serializer.startTag("", "node")
            tagOpened = true
            
            // 序列化属性
            serializer.attribute("", "index", depth.toString())
            serializer.attribute("", "text", getTextMethod?.invoke(node)?.toString() ?: "")
            serializer.attribute("", "resource-id", getResourceIdMethod?.invoke(node)?.toString() ?: "")
            serializer.attribute("", "class", getClassNameMethod?.invoke(node)?.toString() ?: "")
            serializer.attribute("", "package", getPackageNameMethod?.invoke(node)?.toString() ?: "")
            serializer.attribute("", "content-desc", getContentDescriptionMethod?.invoke(node)?.toString() ?: "")
            serializer.attribute("", "checkable", (isCheckableMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "checked", (isCheckedMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "clickable", (isClickableMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "enabled", (isEnabledMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "focusable", (isFocusableMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "focused", (isFocusedMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "scrollable", (isScrollableMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "long-clickable", (isLongClickableMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "password", (isPasswordMethod?.invoke(node) as? Boolean ?: false).toString())
            serializer.attribute("", "selected", (isSelectedMethod?.invoke(node) as? Boolean ?: false).toString())
            
            // Bounds
            try {
                val bounds = getBoundsInScreenMethod?.invoke(node)
                if (bounds != null) {
                    val rect = bounds as android.graphics.Rect
                    serializer.attribute("", "bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
                } else {
                    serializer.attribute("", "bounds", "[0,0][0,0]")
                }
            } catch (e: Exception) {
                serializer.attribute("", "bounds", "[0,0][0,0]")
            }
            
            // 递归处理子节点
            try {
                val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
                for (i in 0 until childCount) {
                    try {
                        val child = getChildMethod?.invoke(node, i)
                        if (child != null) {
                            serializeNode(child, serializer, visibleOnly, depth + 1)
                            // ⚡ 立即释放子节点（关键优化！）
                            try {
                                recycleMethod?.invoke(child)
                            } catch (e: Exception) {
                                // 忽略回收错误
                            }
                        }
                    } catch (e: Exception) {
                        // 忽略单个子节点的错误，继续处理其他子节点
                        println("⚠ 处理子节点 $i 时出错: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                println("⚠ 获取子节点时出错: ${e.message}")
            }
            
            serializer.endTag("", "node")
            tagOpened = false
        } catch (e: Exception) {
            // 如果tag已打开，尝试关闭它
            if (tagOpened) {
                try {
                    serializer.endTag("", "node")
                } catch (e2: Exception) {
                    // 忽略关闭错误
                }
            }
            println("✗ 序列化节点失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 获取屏幕树 JSON
     * 优化版本：参考原生 uiautomator dump 实现 + 根节点缓存
     */
    fun dumpJSON(waitForIdle: Boolean, visibleOnly: Boolean): String {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化")
            return "{}"
        }

        return try {
            // ⚡ 参考应用的优化：默认不等待 UI 空闲，使用缓存的根节点
            if (waitForIdle) {
                waitForIdleMethod?.invoke(uiAutomation, 100L, 500L)
            }

            // 获取根节点（带缓存回退）
            val rootNode = getRootNodeWithCache()
            if (rootNode == null) {
                println("✗ 无法获取根节点（缓存也为空）")
                return "{}"
            }

            // 递归生成 JSON（立即释放子节点）
            // 注意：对于根节点（depth=0），我们总是包含它（不检查可见性），只对子节点应用visibleOnly过滤
            val json = nodeToJson(rootNode, visibleOnly, 0)

            // ⚠️ 不回收根节点（如果是缓存的，不能回收）
            // 参考应用也不回收根节点

            // 确保返回的JSON不为空
            if (json.length() == 0) {
                println("⚠ 生成的JSON为空，返回默认结构")
                return "{}"
            }

            json.toString()
        } catch (e: Exception) {
            println("✗ 生成 JSON 失败: ${e.message}")
            e.printStackTrace()
            "{}"
        }
    }
    
    /**
     * 递归将节点转换为 JSON（参考 uiautomator 实现，立即释放子节点）
     */
    private fun nodeToJson(node: Any?, visibleOnly: Boolean, depth: Int): JSONObject {
        val json = JSONObject()
        if (node == null || depth > 200) return json
        
        // 检查可见性（在填充属性之前检查，避免不必要的处理）
        // 注意：根节点（depth=0）总是包含，不检查可见性
        val isVisible = try {
            isVisibleToUserMethod?.invoke(node) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
        // 根节点（depth=0）总是包含，只对子节点应用visibleOnly过滤
        if (visibleOnly && depth > 0 && !isVisible) return json
        
        try {
            // 基本属性
            json.put("text", try { getTextMethod?.invoke(node)?.toString() ?: "" } catch (e: Exception) { "" })
            json.put("resourceId", try { getResourceIdMethod?.invoke(node)?.toString() ?: "" } catch (e: Exception) { "" })
            json.put("className", try { getClassNameMethod?.invoke(node)?.toString() ?: "" } catch (e: Exception) { "" })
            json.put("packageName", try { getPackageNameMethod?.invoke(node)?.toString() ?: "" } catch (e: Exception) { "" })
            json.put("contentDesc", try { getContentDescriptionMethod?.invoke(node)?.toString() ?: "" } catch (e: Exception) { "" })
            json.put("checkable", try { isCheckableMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("checked", try { isCheckedMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("clickable", try { isClickableMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("enabled", try { isEnabledMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("focusable", try { isFocusableMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("focused", try { isFocusedMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("scrollable", try { isScrollableMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("longClickable", try { isLongClickableMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("password", try { isPasswordMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("selected", try { isSelectedMethod?.invoke(node) as? Boolean ?: false } catch (e: Exception) { false })
            json.put("visible", isVisible)
            
            // Bounds
            try {
                val bounds = getBoundsInScreenMethod?.invoke(node)
                if (bounds != null) {
                    val rect = bounds as android.graphics.Rect
                    val boundsJson = JSONObject()
                    boundsJson.put("left", rect.left)
                    boundsJson.put("top", rect.top)
                    boundsJson.put("right", rect.right)
                    boundsJson.put("bottom", rect.bottom)
                    json.put("bounds", boundsJson)
                }
            } catch (e: Exception) {
                // 忽略bounds错误
            }
            
            // 处理子节点
            try {
                val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
                if (childCount > 0) {
                    val children = JSONArray()
                    for (i in 0 until childCount) {
                        try {
                            val child = getChildMethod?.invoke(node, i)
                            if (child != null) {
                                val childJson = nodeToJson(child, visibleOnly, depth + 1)
                                // 只有当子节点JSON不为空时才添加（避免添加不可见节点的空JSON）
                                if (childJson.length() > 0) {
                                    children.put(childJson)
                                }
                                // ⚡ 立即释放子节点（关键优化！）
                                try {
                                    recycleMethod?.invoke(child)
                                } catch (e: Exception) {
                                    // 忽略回收错误
                                }
                            }
                        } catch (e: Exception) {
                            // 忽略单个子节点的错误，继续处理其他子节点
                            println("⚠ 处理子节点 $i 时出错: ${e.message}")
                        }
                    }
                    // 只有当有子节点时才添加children数组
                    if (children.length() > 0) {
                        json.put("children", children)
                    }
                }
            } catch (e: Exception) {
                println("⚠ 获取子节点时出错: ${e.message}")
            }
        } catch (e: Exception) {
            println("✗ 序列化节点为JSON失败: ${e.message}")
            e.printStackTrace()
        }
        
        return json
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
     * 截图功能（返回字节数组）
     * @return JPEG 格式的字节数组，如果失败返回 null
     */
    fun takeScreenshotBytes(): ByteArray? {
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
            
            println("✓ 截图成功，开始压缩为 JPEG...")
            
            // 将 Bitmap 压缩为 JPEG 并返回字节数组
            val bitmapClass = Class.forName("android.graphics.Bitmap")
            val compressFormatClass = Class.forName("android.graphics.Bitmap\$CompressFormat")
            val compressMethod = bitmapClass.getMethod(
                "compress",
                compressFormatClass,
                Int::class.javaPrimitiveType,
                Class.forName("java.io.OutputStream")
            )
            
            // 获取 JPEG 格式枚举值
            val jpegFormat = compressFormatClass.getField("JPEG").get(null)
            
            // 创建 ByteArrayOutputStream
            val baosClass = Class.forName("java.io.ByteArrayOutputStream")
            val baos = baosClass.getConstructor().newInstance()
            
            // 压缩 Bitmap（质量85%）
            compressMethod.invoke(bitmap, jpegFormat, 85, baos)
            
            // 获取字节数组
            val toByteArrayMethod = baosClass.getMethod("toByteArray")
            val bytes = toByteArrayMethod.invoke(baos) as ByteArray
            
            // 回收 Bitmap
            val recycleMethod = bitmapClass.getMethod("recycle")
            recycleMethod.invoke(bitmap)
            
            println("✓ JPEG 压缩完成，大小: ${bytes.size} bytes")
            bytes
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
            
            // 异步模式：直接注入事件，不依赖返回值
            injectEventMethod.invoke(uiAutomation, downEvent, false)
            Thread.sleep(10)
            injectEventMethod.invoke(uiAutomation, upEvent, false)
            
            // 回收事件
            val recycleMethod = motionEventClass.getMethod("recycle")
            recycleMethod.invoke(downEvent)
            recycleMethod.invoke(upEvent)
            
            println("✓ 点击已注入")
            true
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
            
            // 使用SystemClock.uptimeMillis()作为downTime（参考应用的做法）
            val systemClockClass = Class.forName("android.os.SystemClock")
            val uptimeMillisMethod = systemClockClass.getMethod("uptimeMillis")
            val downTime = uptimeMillisMethod.invoke(null) as Long
            
            // 计算步数（参考应用：duration/10，但至少2步）
            val steps = if (duration == 0L) 1 else (duration / 10).toInt().coerceAtLeast(2)
            
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
            
            // ACTION_DOWN (参考应用：使用同步模式true)
            val eventTime = uptimeMillisMethod.invoke(null) as Long
            val downEvent = obtainMethod.invoke(null, downTime, eventTime, 0, x1.toFloat(), y1.toFloat(), 1)
            val downSuccess = injectEventMethod.invoke(uiAutomation, downEvent, true) as Boolean
            recycleMethod.invoke(downEvent)
            
            if (!downSuccess) {
                println("✗ ACTION_DOWN 失败")
                return false
            }
            
            // ACTION_MOVE (参考应用：循环中sleep 5ms，使用同步模式)
            var allMoveSuccess = true
            for (i in 1 until steps) {
                val progress = i.toFloat() / steps
                val x = x1 + ((x2 - x1) * progress).toInt()
                val y = y1 + ((y2 - y1) * progress).toInt()
                val moveEventTime = uptimeMillisMethod.invoke(null) as Long
                
                val moveEvent = obtainMethod.invoke(null, downTime, moveEventTime, 2, x.toFloat(), y.toFloat(), 1)
                val moveSuccess = injectEventMethod.invoke(uiAutomation, moveEvent, true) as Boolean
                recycleMethod.invoke(moveEvent)
                
                if (!moveSuccess) {
                    allMoveSuccess = false
                    break
                }
                
                // 参考应用：sleep 5ms
                Thread.sleep(5)
            }
            
            // ACTION_UP (参考应用：使用同步模式true)
            val upEventTime = uptimeMillisMethod.invoke(null) as Long
            val upEvent = obtainMethod.invoke(null, downTime, upEventTime, 1, x2.toFloat(), y2.toFloat(), 1)
            val upSuccess = injectEventMethod.invoke(uiAutomation, upEvent, true) as Boolean
            recycleMethod.invoke(upEvent)
            
            val success = downSuccess && allMoveSuccess && upSuccess
            
            if (success) {
                println("✓ 滑动成功")
            } else {
                println("✗ 滑动失败 (down=$downSuccess, move=$allMoveSuccess, up=$upSuccess)")
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
            
            // 异步模式：直接注入事件，不依赖返回值
            injectEventMethod.invoke(uiAutomation, downEvent, false)
            // 注意：不使用Thread.sleep，因为在某些线程环境下可能导致死锁
            injectEventMethod.invoke(uiAutomation, upEvent, false)
            
            println("✓ 按键已注入: keyCode=$keyCode")
            true
        } catch (e: Exception) {
            println("✗ 按键失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 执行按键操作（支持Meta键）
     * @param keyCode 按键码
     * @param metaState Meta键状态（如 META_CTRL_ON）
     * @return 是否成功
     */
    fun pressKey(keyCode: Int, metaState: Int): Boolean {
        if (!initialized || uiAutomation == null) {
            println("✗ UiAutomation 未初始化，无法按键")
            return false
        }

        return try {
            println("执行按键: keyCode=$keyCode, metaState=$metaState")
            
            val now = System.currentTimeMillis()
            
            // 创建 KeyEvent
            val keyEventClass = Class.forName("android.view.KeyEvent")
            val constructor = keyEventClass.getConstructor(
                Long::class.javaPrimitiveType,  // downTime
                Long::class.javaPrimitiveType,  // eventTime
                Int::class.javaPrimitiveType,   // action
                Int::class.javaPrimitiveType,   // code
                Int::class.javaPrimitiveType,   // repeat
                Int::class.javaPrimitiveType    // metaState
            )
            
            // ACTION_DOWN = 0, ACTION_UP = 1
            val downEvent = constructor.newInstance(now, now, 0, keyCode, 0, metaState)
            val upEvent = constructor.newInstance(now, now + 50, 1, keyCode, 0, metaState)
            
            // 注入事件
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val injectEventMethod = uiAutomationClass.getMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Boolean::class.javaPrimitiveType
            )
            
            // 异步模式：直接注入事件，不依赖返回值
            injectEventMethod.invoke(uiAutomation, downEvent, false)
            Thread.sleep(10)
            injectEventMethod.invoke(uiAutomation, upEvent, false)
            
            true
        } catch (e: Exception) {
            println("✗ 按键失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 注入触摸事件（用于手势）
     * @param action 动作类型（ACTION_DOWN, ACTION_MOVE, ACTION_UP）
     * @param downTime 按下时间
     * @param eventTime 事件时间
     * @param x X坐标
     * @param y Y坐标
     * @return 是否成功
     */
    fun injectMotionEvent(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float): Boolean {
        if (!initialized || uiAutomation == null) {
            return false
        }

        return try {
            // 创建 MotionEvent
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
            
            val event = obtainMethod.invoke(
                null,
                downTime,
                eventTime,
                action,
                x,
                y,
                0  // metaState
            )
            
            // 设置 Source 为触摸屏
            val setSourceMethod = motionEventClass.getMethod("setSource", Int::class.javaPrimitiveType)
            setSourceMethod.invoke(event, 0x00001002) // SOURCE_TOUCHSCREEN
            
            // 注入事件
            val uiAutomationClass = Class.forName("android.app.UiAutomation")
            val injectEventMethod = uiAutomationClass.getMethod(
                "injectInputEvent",
                Class.forName("android.view.InputEvent"),
                Boolean::class.javaPrimitiveType
            )
            
            val success = injectEventMethod.invoke(uiAutomation, event, false) as Boolean
            
            // 回收事件
            val recycleMethod = motionEventClass.getMethod("recycle")
            recycleMethod.invoke(event)
            
            success
        } catch (e: Exception) {
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
    
    /**
     * 设置文本（使用UiAutomation的setText方法）
     * @param text 要输入的文本
     * @return 是否成功
     */
    fun setText(text: String): Boolean {
        if (!initialized || uiAutomation == null) {
            println("✗ setText: UiAutomation not initialized")
            return false
        }
        
        return try {
            println("✓ setText: 开始设置文本: $text")
            
            // 获取根节点
            val rootNode = getRootNodeWithCache()
            if (rootNode == null) {
                println("✗ setText: 无法获取根节点")
                return false
            }
            println("✓ setText: 根节点已获取")
            
            // 先统计所有EditText节点（用于调试）
            val allEditTexts = findAllEditTexts(rootNode)
            println("✓ setText: 找到 ${allEditTexts.size} 个EditText节点")
            
            // 查找当前焦点的EditText节点
            val focusedNode = findFocusedEditText(rootNode)
            if (focusedNode == null) {
                println("✗ setText: 未找到焦点的EditText节点")
                if (allEditTexts.isNotEmpty()) {
                    println("⚠ setText: 尝试使用第一个EditText节点")
                    // 尝试使用第一个EditText
                    val firstEditText = allEditTexts[0]
                    // 先点击获取焦点
                    val performClickMethod = Class.forName("android.view.accessibility.AccessibilityNodeInfo")
                        .getMethod("performAction", Int::class.javaPrimitiveType)
                    performClickMethod.invoke(firstEditText, 16) // ACTION_CLICK
                    Thread.sleep(300)
                    // 使用第一个EditText
                    val result = performSetText(firstEditText, text)
                    recycleMethod?.invoke(firstEditText)
                    return result
                }
                return false
            }
            println("✓ setText: 找到焦点EditText节点")
            
            // 执行setText
            val result = performSetText(focusedNode, text)
            
            // 回收节点
            recycleMethod?.invoke(focusedNode)
            
            result
        } catch (e: Exception) {
            println("✗ setText失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 执行setText操作
     */
    private fun performSetText(node: Any?, text: String): Boolean {
        return try {
            
            // 使用performAction(ACTION_SET_TEXT)设置文本
            // ACTION_SET_TEXT = 2097152 (API 21+)
            val actionSetText = 2097152
            val bundleClass = Class.forName("android.os.Bundle")
            val bundleConstructor = bundleClass.getDeclaredConstructor()
            bundleConstructor.isAccessible = true
            val bundle = bundleConstructor.newInstance()
            
            // 设置文本参数
            val putCharSequenceMethod = bundleClass.getMethod(
                "putCharSequence",
                String::class.java,
                CharSequence::class.java
            )
            putCharSequenceMethod.invoke(bundle, "ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE", text)
            println("✓ setText: Bundle参数已设置")
            
            // 执行ACTION_SET_TEXT
            val performActionMethod = Class.forName("android.view.accessibility.AccessibilityNodeInfo")
                .getMethod(
                    "performAction",
                    Int::class.javaPrimitiveType,
                    bundleClass
                )
            val result = performActionMethod.invoke(node, actionSetText, bundle) as Boolean
            println("✓ performSetText: performAction返回: $result")
            
            result
        } catch (e: Exception) {
            println("✗ performSetText失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 查找所有EditText节点（用于调试）
     */
    private fun findAllEditTexts(node: Any?): MutableList<Any> {
        val result = mutableListOf<Any>()
        if (node == null) {
            return result
        }
        
        try {
            val className = getClassNameMethod?.invoke(node) as? String
            if (className != null && className.contains("EditText")) {
                result.add(node)
            }
            
            val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
            for (i in 0 until childCount) {
                val child = getChildMethod?.invoke(node, i)
                result.addAll(findAllEditTexts(child))
            }
        } catch (e: Exception) {
            // 忽略异常
        }
        
        return result
    }
    
    /**
     * 递归查找当前焦点的EditText节点
     */
    private fun findFocusedEditText(node: Any?): Any? {
        if (node == null) {
            return null
        }
        
        return try {
            // 检查是否是EditText且获得焦点
            val className = getClassNameMethod?.invoke(node) as? String
            val isFocused = isFocusedMethod?.invoke(node) as? Boolean ?: false
            val isEditable = isEnabledMethod?.invoke(node) as? Boolean ?: false
            val isVisible = isVisibleToUserMethod?.invoke(node) as? Boolean ?: false
            
            if (className != null && className.contains("EditText")) {
                println("  [调试] 找到EditText: $className, 焦点=$isFocused, 可编辑=$isEditable, 可见=$isVisible")
                
                if (isFocused) {
                    println("✓ findFocusedEditText: 找到焦点EditText: $className")
                    return node
                } else if (isEditable && isVisible) {
                    // 如果没有焦点，但可编辑且可见，也尝试使用（可能需要先点击）
                    println("⚠ findFocusedEditText: 找到可编辑EditText（无焦点）: $className，尝试点击获取焦点")
                    // 先尝试点击以获取焦点
                    val performClickMethod = Class.forName("android.view.accessibility.AccessibilityNodeInfo")
                        .getMethod("performAction", Int::class.javaPrimitiveType)
                    val actionClick = 16 // ACTION_CLICK
                    val clickResult = performClickMethod.invoke(node, actionClick) as Boolean
                    println("  [调试] 点击结果: $clickResult")
                    Thread.sleep(300) // 等待焦点切换
                    // 再次检查是否获得焦点
                    val nowFocused = isFocusedMethod?.invoke(node) as? Boolean ?: false
                    if (nowFocused) {
                        println("✓ findFocusedEditText: EditText已获得焦点")
                        return node
                    } else {
                        println("⚠ findFocusedEditText: 点击后仍未获得焦点，但继续尝试使用")
                        // 即使没有焦点，也尝试使用（某些情况下可能仍然有效）
                        return node
                    }
                }
            }
            
            // 递归查找子节点
            val childCount = getChildCountMethod?.invoke(node) as? Int ?: 0
            for (i in 0 until childCount) {
                val child = getChildMethod?.invoke(node, i)
                val result = findFocusedEditText(child)
                if (result != null) {
                    return result
                }
            }
            
            null
        } catch (e: Exception) {
            println("✗ findFocusedEditText异常: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}

