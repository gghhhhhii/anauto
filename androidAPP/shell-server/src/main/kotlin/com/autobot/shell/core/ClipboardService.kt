package com.autobot.shell.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.IBinder
import android.os.Parcel

/**
 * 剪切板服务
 * 参考应用 autobot_3.2.1.apk 的实现方式
 */
class ClipboardService(private val context: Context) {

    private val clipboardManager: ClipboardManager? by lazy {
        try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 设置剪切板文本
     * 尝试多种方式：
     * 1. 通过 IBinder 直接调用系统服务（参考应用的方式，最可靠）
     * 2. 通过 shell 命令
     * 3. 通过 ClipboardManager（如果可用）
     */
    fun setClipText(text: String): Boolean {
        println("🔵 setClipText开始: $text")
        
        // 方法1: 通过 IBinder 直接调用系统服务（参考应用的方式，最可靠）
        val result1 = setClipTextViaBinder(text)
        if (result1 == true) {
            println("✓ setClipTextViaBinder成功")
            return true
        }
        println("✗ setClipTextViaBinder失败")

        // 方法2: 通过 shell 命令
        val result2 = setClipTextViaShell(text)
        if (result2 == true) {
            println("✓ setClipTextViaShell成功")
            return true
        }
        println("✗ setClipTextViaShell失败")

        // 方法3: 通过 ClipboardManager（如果可用）
        return try {
            val clip = ClipData.newPlainText("text", text)
            clipboardManager?.setPrimaryClip(clip)
            val success = clipboardManager != null
            if (success) {
                println("✓ setClipTextViaClipboardManager成功")
            } else {
                println("✗ setClipTextViaClipboardManager失败: clipboardManager is null")
            }
            success
        } catch (e: Exception) {
            println("⚠ setClipText失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 通过 shell 命令设置剪贴板
     * 使用 service call clipboard 命令
     */
    private fun setClipTextViaShell(text: String): Boolean {
        return try {
            // 使用 service call clipboard 命令
            // service call clipboard 1 s16 "text" s16 "actual_text"
            val escapedText = text.replace("\"", "\\\"").replace("$", "\\$")
            val command = "service call clipboard 1 s16 \"text\" s16 \"$escapedText\""
            
            println("🔵 执行shell命令: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            
            println("🔵 shell命令退出码: $exitCode")
            if (output.isNotEmpty()) println("🔵 shell命令输出: $output")
            if (error.isNotEmpty()) println("🔵 shell命令错误: $error")
            
            exitCode == 0
        } catch (e: Exception) {
            println("⚠ setClipTextViaShell失败: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    /**
     * 通过 IBinder 直接调用系统服务设置剪贴板
     * 这是参考应用 autobot_3.2.1.apk 的实现方式
     */
    private fun setClipTextViaBinder(text: String): Boolean {
        return try {
            println("🔵 setClipTextViaBinder开始: $text")
            // 获取 ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "clipboard") as? IBinder
                ?: return false

            // 获取 IClipboard.Stub.asInterface
            val iClipboardStubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterfaceMethod = iClipboardStubClass.getMethod("asInterface", IBinder::class.java)
            val clipboard = asInterfaceMethod.invoke(null, binder)

            // 创建 ClipData
            val clipData = ClipData.newPlainText("text", text)

            // 调用 setPrimaryClip
            // IClipboard.setPrimaryClip(ClipData clip, String callingPackage, int userId)
            val setPrimaryClipMethod = clipboard.javaClass.getMethod(
                "setPrimaryClip",
                ClipData::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )

            // 获取当前用户ID
            val userId = android.os.Process.myUid() / 100000  // 获取用户ID，shell用户通常是0
            
            // 尝试使用不同的 callingPackage
            // 在 shell 进程中，可能需要使用特定的包名或空字符串
            val callingPackageOptions = listOf(
                "com.android.shell",  // shell 进程的包名
                "android",  // 系统包名
                "",  // 空字符串
                null  // null（最后尝试）
            )
            
            println("🔵 尝试调用 setPrimaryClip...")
            var result: Any? = null
            var lastException: Exception? = null
            
            for (callingPackage in callingPackageOptions) {
                try {
                    if (callingPackage != null) {
                        println("🔵 尝试 callingPackage='$callingPackage'")
                        result = setPrimaryClipMethod.invoke(clipboard, clipData, callingPackage, userId)
                        println("✓ 使用 callingPackage='$callingPackage' 成功")
                        break
                    } else {
                        // 尝试使用 transact 方式（最后的手段）
                        println("🔵 所有 callingPackage 都失败，尝试 transact 方式")
                        return setClipTextViaTransact(text)
                    }
                } catch (e: Exception) {
                    println("⚠ callingPackage='$callingPackage' 失败: ${e.message}")
                    lastException = e
                }
            }
            
            if (result == null && lastException != null) {
                throw lastException
            }
            val success = if (result is Boolean) {
                result
            } else {
                // 如果没有返回值或返回void，认为成功
                println("🔵 setPrimaryClip 返回 void，假设成功")
                true
            }
            println("🔵 setClipTextViaBinder结果: $success")
            return success
        } catch (e: Exception) {
            println("⚠ setClipTextViaBinder异常: ${e.message}")
            e.printStackTrace()
            // 如果反射失败，尝试使用 transact 方式
            return setClipTextViaTransact(text)
        }
    }

    /**
     * 通过 transact 直接调用系统服务
     */
    private fun setClipTextViaTransact(text: String): Boolean {
        return try {
            // 获取 ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "clipboard") as? IBinder
                ?: return false

            // 创建 ClipData
            val clipData = ClipData.newPlainText("text", text)

            // 准备 Parcel
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                // 写入接口描述符（必须在最前面）
                data.writeInterfaceToken("android.content.IClipboard")
                
                // 写入 ClipData (使用 Parcelable 标志)
                clipData.writeToParcel(data, 0)
                
                // 写入 callingPackage
                // 尝试使用 shell 进程的包名
                val callingPackage = "com.android.shell"  // shell 进程的包名
                data.writeString(callingPackage)
                
                // 写入 userId (shell用户通常是0)
                val userId = android.os.Process.myUid() / 100000
                data.writeInt(userId)

                // 调用 transact
                // IClipboard.TRANSACTION_setPrimaryClip = 1
                val result = binder.transact(1, data, reply, 0)
                
                if (result) {
                    reply.readException()
                    return true
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
            false
        } catch (e: Exception) {
            println("⚠ setClipTextViaTransact失败: ${e.message}")
            false
        }
    }

    /**
     * 获取剪切板文本
     * 尝试多种方式：
     * 1. 通过 IBinder 直接调用系统服务（参考应用的方式）
     * 2. 通过 shell 命令
     * 3. 通过 ClipboardManager（如果可用）
     */
    fun getClipText(): String {
        println("🔵 getClipText开始")
        
        // 方法1: 通过 IBinder 直接调用系统服务
        val result1 = getClipTextViaBinder()
        if (result1.isNotEmpty()) {
            println("✓ getClipTextViaBinder成功: $result1")
            return result1
        }
        println("✗ getClipTextViaBinder失败或为空")

        // 方法2: 通过 shell 命令
        val result2 = getClipTextViaShell()
        if (result2.isNotEmpty()) {
            println("✓ getClipTextViaShell成功: $result2")
            return result2
        }
        println("✗ getClipTextViaShell失败或为空")

        // 方法3: 通过 ClipboardManager（如果可用）
        return try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                println("✓ getClipTextViaClipboardManager成功: $text")
                text
            } else {
                println("✗ getClipTextViaClipboardManager失败: clip is null or empty")
                ""
            }
        } catch (e: Exception) {
            println("⚠ getClipTextViaClipboardManager异常: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    /**
     * 通过 IBinder 直接调用系统服务获取剪贴板
     * 参考应用 autobot_3.2.1.apk 的实现方式
     */
    private fun getClipTextViaBinder(): String {
        return try {
            println("🔵 getClipTextViaBinder开始")
            // 获取 ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "clipboard") as? IBinder
                ?: run {
                    println("✗ 无法获取 clipboard binder")
                    return ""
                }

            // 获取 IClipboard.Stub.asInterface
            val iClipboardStubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterfaceMethod = iClipboardStubClass.getMethod("asInterface", IBinder::class.java)
            val clipboard = asInterfaceMethod.invoke(null, binder)

            // 尝试不同的方法签名
            val userId = android.os.Process.myUid() / 100000
            println("🔵 userId=$userId")
            
            // 尝试使用不同的 callingPackage（与 setClipText 保持一致）
            val callingPackageOptions = listOf(
                "com.android.shell",  // shell 进程的包名（与 setClipText 一致）
                "android",  // 系统包名
                "",  // 空字符串
                null  // null
            )
            
            // 方法1: getPrimaryClip(String callingPackage, String attributionTag, int userId)
            for (callingPackage in callingPackageOptions) {
                try {
                    println("🔵 尝试方法1: callingPackage='$callingPackage'")
                    val method = clipboard.javaClass.getMethod(
                        "getPrimaryClip",
                        String::class.java,
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    val clipData = method.invoke(clipboard, callingPackage, null, userId) as? ClipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString() ?: ""
                        println("✓ getPrimaryClip(3 params)成功: $text")
                        return text
                    } else {
                        println("⚠ getPrimaryClip(3 params)返回空: clipData=$clipData")
                    }
                } catch (e: Exception) {
                    println("⚠ getPrimaryClip(3 params)失败: ${e.message}")
                }
            }
            
            // 方法2: getPrimaryClip(String callingPackage, int userId)
            for (callingPackage in callingPackageOptions) {
                try {
                    println("🔵 尝试方法2: callingPackage='$callingPackage'")
                    val method = clipboard.javaClass.getMethod(
                        "getPrimaryClip",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    val clipData = method.invoke(clipboard, callingPackage, userId) as? ClipData
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString() ?: ""
                        println("✓ getPrimaryClip(2 params)成功: $text")
                        return text
                    } else {
                        println("⚠ getPrimaryClip(2 params)返回空: clipData=$clipData")
                    }
                } catch (e: Exception) {
                    println("⚠ getPrimaryClip(2 params)失败: ${e.message}")
                }
            }
            
            // 方法3: getPrimaryClip(int userId)
            try {
                println("🔵 尝试方法3: getPrimaryClip(int)")
                val method = clipboard.javaClass.getMethod(
                    "getPrimaryClip",
                    Int::class.javaPrimitiveType
                )
                val clipData = method.invoke(clipboard, userId) as? ClipData
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    println("✓ getPrimaryClip(1 param)成功: $text")
                    return text
                } else {
                    println("⚠ getPrimaryClip(1 param)返回空: clipData=$clipData")
                }
            } catch (e: Exception) {
                println("⚠ getPrimaryClip(1 param)失败: ${e.message}")
            }
            
            ""
        } catch (e: Exception) {
            println("⚠ getClipTextViaBinder失败: ${e.message}")
            e.printStackTrace()
            // 如果反射失败，尝试使用 transact 方式
            return getClipTextViaTransact()
        }
    }

    /**
     * 通过 transact 直接调用系统服务获取剪贴板
     * 参考应用 autobot_3.2.1.apk 的实现方式
     */
    private fun getClipTextViaTransact(): String {
        return try {
            println("🔵 getClipTextViaTransact开始")
            // 获取 ServiceManager
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "clipboard") as? IBinder
                ?: return ""

            // 准备 Parcel
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                // 写入接口描述符（必须在最前面）
                data.writeInterfaceToken("android.content.IClipboard")
                
                // 尝试不同的参数组合
                // 方式1: callingPackage (null), attributionTag (null), userId
                val userId = android.os.Process.myUid() / 100000
                
                // 写入 callingPackage（与 setClipText 保持一致）
                val callingPackage = "com.android.shell"  // shell 进程的包名（与 setClipText 一致）
                data.writeString(callingPackage)
                
                // 写入 attributionTag (使用 null)
                data.writeString(null)
                
                // 写入 userId
                data.writeInt(userId)

                // 调用 transact
                // IClipboard.TRANSACTION_getPrimaryClip = 2
                val result = binder.transact(2, data, reply, 0)
                
                if (result) {
                    // 先读取异常（如果有）
                    reply.readException()
                    
                    // 读取 ClipData
                    val clipData = ClipData.CREATOR.createFromParcel(reply)
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString() ?: ""
                        println("✓ getClipTextViaTransact成功: $text")
                        return text
                    } else {
                        println("⚠ getClipTextViaTransact返回空: clipData=$clipData")
                    }
                } else {
                    println("⚠ getClipTextViaTransact transact 返回 false")
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
            ""
        } catch (e: Exception) {
            println("⚠ getClipTextViaTransact失败: ${e.message}")
            e.printStackTrace()
            ""
        }
    }

    /**
     * 通过 shell 命令获取剪贴板
     */
    private fun getClipTextViaShell(): String {
        return try {
            // 使用 service call clipboard 2 获取剪贴板
            val process = Runtime.getRuntime().exec("sh")
            val output = process.outputStream
            val input = process.inputStream
            
            val command = "service call clipboard 2\n"
            output.write(command.toByteArray())
            output.flush()
            output.close()
            
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                // 解析输出（格式复杂，暂时返回空）
                // service call 的输出格式需要解析 Parcel
                ""
            } else {
                ""
            }
        } catch (e: Exception) {
            println("⚠ getClipTextViaShell失败: ${e.message}")
            ""
        }
    }
}


