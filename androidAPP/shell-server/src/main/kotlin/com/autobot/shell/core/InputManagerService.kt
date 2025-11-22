package com.autobot.shell.core

import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * InputManager 输入服务
 * 使用 InputManager.injectInputEvent 而不是 UiAutomation.injectInputEvent
 * 这是参考应用避免死锁的关键！
 */
object InputManagerService {
    private var inputManager: Any? = null
    private var injectInputEventMethod: java.lang.reflect.Method? = null
    private var setSourceMethod: java.lang.reflect.Method? = null
    private val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    
    // 注入模式常量
    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0  // 异步模式，不等待
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1  // 等待结果
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2  // 等待完成
    
    // 输入源常量
    private const val SOURCE_KEYBOARD = 257  // InputDevice.SOURCE_KEYBOARD
    
    init {
        initializeInputManager()
    }
    
    private fun initializeInputManager() {
        try {
            // 1. 获取 InputManager 类
            val inputManagerClass = try {
                Class.forName("android.hardware.input.InputManager")
            } catch (e: ClassNotFoundException) {
                // 如果找不到隐藏类，使用公开类
                Class.forName("android.view.InputManager")
            }
            
            // 2. 获取 InputManager 实例
            val getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance")
            getInstanceMethod.isAccessible = true
            inputManager = getInstanceMethod.invoke(null)
            
            // 3. 获取 injectInputEvent 方法
            injectInputEventMethod = inputManagerClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            
            // 4. 获取 InputEvent.setSource 方法（参考应用的关键步骤）
            setSourceMethod = InputEvent::class.java.getMethod(
                "setSource",
                Int::class.javaPrimitiveType
            )
            
            println("✓ InputManager initialized successfully")
        } catch (e: Exception) {
            println("✗ Failed to initialize InputManager: ${e.message}")
            e.printStackTrace()
            inputManager = null
            injectInputEventMethod = null
        }
    }
    
    /**
     * 注入 InputEvent（异步模式）
     * @param event InputEvent（KeyEvent 或 MotionEvent）
     * @return 是否成功
     */
    fun injectInputEvent(event: InputEvent, source: Int = SOURCE_KEYBOARD): Boolean {
        if (inputManager == null || injectInputEventMethod == null) {
            println("⚠ InputManager not available")
            return false
        }
        
        return try {
            // 关键步骤1：设置输入源（参考应用的做法）
            if (setSourceMethod != null && source != 0) {
                setSourceMethod!!.invoke(event, source)
            }
            
            // 关键步骤2：使用异步模式（mode = 0），不会阻塞
            val result = injectInputEventMethod!!.invoke(
                inputManager,
                event,
                INJECT_INPUT_EVENT_MODE_ASYNC
            ) as Boolean
            result
        } catch (e: Exception) {
            println("✗ Failed to inject input event: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 按键操作
     * @param keyCode 按键码
     * @param metaState Meta键状态（如 KeyEvent.META_CTRL_ON）
     * @return 是否成功
     */
    fun pressKey(keyCode: Int, metaState: Int = 0): Boolean {
        return try {
            val now = System.currentTimeMillis()
            
            // 创建 KeyEvent
            val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, metaState)
            val upEvent = KeyEvent(now, now + 50, KeyEvent.ACTION_UP, keyCode, metaState)
            
            // 注入事件
            val downSuccess = injectInputEvent(downEvent)
            val upSuccess = injectInputEvent(upEvent)
            
            downSuccess && upSuccess
        } catch (e: Exception) {
            println("✗ Failed to press key $keyCode: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 粘贴操作（Ctrl+V）
     * @return 是否成功
     */
    fun paste(): Boolean {
        return pressKey(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
    }
    
    /**
     * 输入ASCII字符（使用KeyCharacterMap，参考应用的方式）
     * @param text 要输入的文本
     * @return 是否成功
     */
    fun inputChar(text: String): Boolean {
        return try {
            for (char in text) {
                // 使用 KeyCharacterMap.getEvents() 生成KeyEvent数组（参考应用的方式）
                val events = keyCharacterMap.getEvents(char.toString().toCharArray())
                if (events != null) {
                    for (keyEvent in events) {
                        if (!injectInputEvent(keyEvent, SOURCE_KEYBOARD)) {
                            return false
                        }
                    }
                } else {
                    // 如果getEvents返回null，回退到手动方式
                    val keyCode = getKeyCodeForChar(char)
                    if (keyCode != -1) {
                        if (!pressKey(keyCode)) {
                            return false
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            println("✗ Failed to input char: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 获取字符对应的KeyCode
     */
    private fun getKeyCodeForChar(char: Char): Int {
        return when (char) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (char - 'a')
            in 'A'..'Z' -> KeyEvent.KEYCODE_A + (char - 'A')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (char - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            '.' -> KeyEvent.KEYCODE_PERIOD
            ',' -> KeyEvent.KEYCODE_COMMA
            '-' -> KeyEvent.KEYCODE_MINUS
            '+' -> KeyEvent.KEYCODE_PLUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '@' -> KeyEvent.KEYCODE_AT
            '/' -> KeyEvent.KEYCODE_SLASH
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            '\n' -> KeyEvent.KEYCODE_ENTER
            else -> -1
        }
    }
}

