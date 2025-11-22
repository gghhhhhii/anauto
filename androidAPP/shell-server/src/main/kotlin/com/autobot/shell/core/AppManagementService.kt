package com.autobot.shell.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 应用管理服务
 */
class AppManagementService(private val context: Context) {

    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    /**
     * 获取顶层Activity信息
     */
    fun getTopActivity(): Map<String, String>? {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.getRunningTasks(1)
            if (tasks.isNotEmpty()) {
                val topActivity = tasks[0].topActivity
                if (topActivity != null) {
                    val packageName = topActivity.packageName
                    val className = topActivity.className
                    val shortClassName = topActivity.shortClassName
                    
                    // 获取启动类
                    val mainIntent = Intent(Intent.ACTION_MAIN, null)
                    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    mainIntent.`package` = packageName
                    val resolveInfo = packageManager.queryIntentActivities(mainIntent, 0)
                    val main = if (resolveInfo.isNotEmpty()) {
                        resolveInfo[0].activityInfo.name
                    } else {
                        "unknown"
                    }
                    
                    return mapOf(
                        "packageName" to packageName,
                        "className" to className,
                        "shortClassName" to shortClassName,
                        "main" to main
                    )
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 根据包名获取启动类
     */
    fun getStartActivity(packageName: String): String? {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
            mainIntent.`package` = packageName
            val resolveInfo = packageManager.queryIntentActivities(mainIntent, 0)
            if (resolveInfo.isNotEmpty()) {
                resolveInfo[0].activityInfo.name
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 根据包名启动应用
     * 使用monkey命令（参考应用的方式），避免权限问题
     */
    fun startPackage(packageName: String): Boolean {
        return try {
            // 方法1: 使用monkey命令（参考应用的方式，不需要Activity权限）
            val process = Runtime.getRuntime().exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                return true
            }
            
            // 方法2: 如果monkey失败，尝试使用am start命令
            val process2 = Runtime.getRuntime().exec("am start -n $packageName/.MainActivity")
            val exitCode2 = process2.waitFor()
            if (exitCode2 == 0) {
                return true
            }
            
            // 方法3: 尝试获取启动Intent（如果context可用）
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return true
                }
            } catch (e: Exception) {
                // 忽略，继续尝试其他方法
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 根据包名停止应用
     * 注意：需要通过Shell命令执行
     */
    fun stopPackage(packageName: String): Boolean {
        return try {
            Runtime.getRuntime().exec("am force-stop $packageName")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 根据包名清除应用数据
     * 注意：需要通过Shell命令执行
     */
    fun clearPackage(packageName: String): Boolean {
        return try {
            Runtime.getRuntime().exec("pm clear $packageName")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取所有应用包名列表（仅包名）
     */
    fun getAllPackages(): List<String> {
        return try {
            val packages = packageManager.getInstalledPackages(0)
            packages.map { it.packageName }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 获取所有应用的完整信息（包括图标、标签、版本等）
     * 返回格式与参考应用一致
     */
    fun getAllPackagesWithInfo(): List<Map<String, Any>> {
        return try {
            val packages = packageManager.getInstalledPackages(
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_META_DATA
            )
            
            packages.mapNotNull { packageInfo ->
                try {
                    val applicationInfo = packageInfo.applicationInfo
                    
                    // 获取应用标签
                    val label = packageManager.getApplicationLabel(applicationInfo).toString()
                    
                    // 暂时禁用图标处理，避免启动时崩溃
                    val iconBase64 = ""
                    
                    // 获取安装时间和更新时间
                    val firstInstallTime = packageInfo.firstInstallTime
                    val lastUpdateTime = packageInfo.lastUpdateTime
                    
                    // 获取版本信息
                    val versionName = packageInfo.versionName ?: "unknown"
                    val versionCode = packageInfo.longVersionCode
                    
                    // 获取SDK版本
                    val minSdkVersion = applicationInfo.minSdkVersion
                    val targetSdkVersion = applicationInfo.targetSdkVersion
                    
                    mapOf(
                        "packageName" to packageInfo.packageName,
                        "label" to label,
                        "icon" to iconBase64,
                        "versionName" to versionName,
                        "versionCode" to versionCode,
                        "firstInstallTime" to firstInstallTime,
                        "lastUpdateTime" to lastUpdateTime,
                        "minSdkVersion" to minSdkVersion,
                        "targetSdkVersion" to targetSdkVersion
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 将 Drawable 转换为 Base64 编码的图片字符串
     */
    private fun drawableToBase64(drawable: Drawable): String {
        return try {
            // 限制图标大小，避免内存问题（最大 256x256）
            val maxSize = 256
            val width = drawable.intrinsicWidth.coerceIn(1, maxSize)
            val height = drawable.intrinsicHeight.coerceIn(1, maxSize)
            
            val bitmap = when (drawable) {
                is BitmapDrawable -> {
                    val original = drawable.bitmap
                    if (original.width <= maxSize && original.height <= maxSize) {
                        original
                    } else {
                        // 缩放大图标
                        Bitmap.createScaledBitmap(original, width, height, true)
                    }
                }
                else -> {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                    bitmap
                }
            }
            
            // 压缩为 PNG 格式（更兼容）
            val outputStream = ByteArrayOutputStream()
            try {
                // 尝试使用 WebP（如果支持）
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, outputStream)
                val imageBytes = outputStream.toByteArray()
                val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                "data:image/webp;base64,$base64"
            } catch (e: Exception) {
                // 如果 WebP 失败，使用 PNG
                outputStream.reset()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val imageBytes = outputStream.toByteArray()
                val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                "data:image/png;base64,$base64"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 获取应用详细信息
     */
    fun getPackageInfo(packageName: String): Map<String, Any>? {
        return try {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS
            )
            
            val applicationInfo = packageInfo.applicationInfo
            
            mapOf(
                "packageInfo" to mapOf(
                    "packageName" to packageInfo.packageName,
                    "versionName" to (packageInfo.versionName ?: "unknown"),
                    "versionCode" to packageInfo.longVersionCode,
                    "lastUpdateTime" to packageInfo.lastUpdateTime
                ),
                "applicationInfo" to mapOf(
                    "dataDir" to applicationInfo.dataDir,
                    "processName" to applicationInfo.processName,
                    "targetSdkVersion" to applicationInfo.targetSdkVersion
                ),
                "activities" to (packageInfo.activities?.map { it.name } ?: emptyList<String>()),
                "services" to (packageInfo.services?.map { it.name } ?: emptyList<String>()),
                "providers" to (packageInfo.providers?.map { it.name } ?: emptyList<String>()),
                "requestedPermissions" to (packageInfo.requestedPermissions?.toList() ?: emptyList<String>())
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

