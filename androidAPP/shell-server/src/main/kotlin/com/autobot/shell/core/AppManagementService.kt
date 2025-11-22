package com.autobot.shell.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

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
     */
    fun startPackage(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
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
     * 获取所有应用包名列表
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

