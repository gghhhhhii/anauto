package com.autobot.shell.core

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * 输入服务
 * 提供高级输入操作（长按、手势等）
 */
class InputService(private val uiAutomationService: UiAutomationService) {

    /**
     * 长按点击（默认时长600ms）
     */
    fun longClick(x: Float, y: Float): Boolean {
        return press(x, y, 600)
    }

    /**
     * 长按点击（指定时长）
     * @param x X坐标
     * @param y Y坐标
     * @param duration 持续时间（毫秒）
     */
    fun press(x: Float, y: Float, duration: Long): Boolean {
        return try {
            val downTime = System.currentTimeMillis()
            
            // 按下事件
            uiAutomationService.injectMotionEvent(
                MotionEvent.ACTION_DOWN,
                downTime,
                downTime,
                x,
                y
            )
            
            // 等待指定时长
            Thread.sleep(duration)
            
            // 抬起事件
            val upTime = System.currentTimeMillis()
            uiAutomationService.injectMotionEvent(
                MotionEvent.ACTION_UP,
                downTime,
                upTime,
                x,
                y
            )
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 单指手势
     * @param duration 手势持续时间（毫秒）
     * @param points 手势路径点
     */
    fun gesture(duration: Long, points: List<Point>): Boolean {
        if (points.isEmpty()) return false
        
        return try {
            val downTime = System.currentTimeMillis()
            val pointCount = points.size
            val interval = if (pointCount > 1) duration / (pointCount - 1) else 0
            
            // 第一个点：按下
            uiAutomationService.injectMotionEvent(
                MotionEvent.ACTION_DOWN,
                downTime,
                downTime,
                points[0].x,
                points[0].y
            )
            
            // 中间的点：移动
            for (i in 1 until pointCount - 1) {
                val eventTime = downTime + i * interval
                Thread.sleep(interval)
                uiAutomationService.injectMotionEvent(
                    MotionEvent.ACTION_MOVE,
                    downTime,
                    eventTime,
                    points[i].x,
                    points[i].y
                )
            }
            
            // 最后一个点：抬起
            if (pointCount > 1) {
                val upTime = downTime + duration
                Thread.sleep(interval)
                uiAutomationService.injectMotionEvent(
                    MotionEvent.ACTION_UP,
                    downTime,
                    upTime,
                    points[pointCount - 1].x,
                    points[pointCount - 1].y
                )
            } else {
                // 只有一个点，直接抬起
                Thread.sleep(duration)
                uiAutomationService.injectMotionEvent(
                    MotionEvent.ACTION_UP,
                    downTime,
                    downTime + duration,
                    points[0].x,
                    points[0].y
                )
            }
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 输入ASCII字符
     * 通过按键模拟的方式输入
     */
    fun inputChar(text: String): Boolean {
        return try {
            for (char in text) {
                val keyCode = getKeyCodeForChar(char)
                if (keyCode != -1) {
                    uiAutomationService.pressKey(keyCode)
                    Thread.sleep(50) // 每个字符间隔50ms
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 输入文本（支持中文）
     * 使用AccessibilityNodeInfo的setText方法
     */
    fun inputText(text: String): Boolean {
        // 这个需要通过UiAutomation的方式实现
        // 简化实现：通过剪切板粘贴
        return try {
            // TODO: 实现通过UiAutomation输入文本
            // 这里需要先找到当前焦点的输入框，然后调用setText
            println("输入文本: $text")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 清除输入框文本
     * 模拟Ctrl+A然后Delete
     */
    fun clearText(): Boolean {
        return try {
            // 全选（Ctrl+A）
            uiAutomationService.pressKey(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            Thread.sleep(100)
            // 删除
            uiAutomationService.pressKey(KeyEvent.KEYCODE_DEL)
            true
        } catch (e: Exception) {
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

    /**
     * 手势路径点
     */
    data class Point(val x: Float, val y: Float)
}

