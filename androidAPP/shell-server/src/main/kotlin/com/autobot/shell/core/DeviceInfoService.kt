package com.autobot.shell.core

import android.os.Build
import android.view.Display
import java.net.NetworkInterface

/**
 * 设备信息服务（无需Context，适用于Shell环境）
 * 使用系统API、反射和命令行获取设备信息
 */
class DeviceInfoService {

    /**
     * 获取设备ID（通过系统属性）
     */
    fun getDeviceId(): String {
        return try {
            // 尝试多种方式获取设备ID
            getSystemProperty("ro.serialno") 
                ?: getSystemProperty("ro.boot.serialno")
                ?: Build.SERIAL
                ?: Build.getSerial()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 获取所有网络接口的IP地址
     */
    fun getIpAddresses(): List<String> {
        val ipList = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.address.size == 4) {
                        // IPv4 地址
                        ipList.add(address.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ipList.filter { it.isNotEmpty() }
    }

    /**
     * 获取屏幕信息（通过WindowManager服务）
     */
    fun getScreenInfo(): Map<String, Any> {
        return try {
            val displayInfo = getDisplayInfo()
            mapOf(
                "rotation" to (displayInfo["rotation"] ?: 0),
                "width" to (displayInfo["width"] ?: 0),
                "height" to (displayInfo["height"] ?: 0)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            mapOf(
                "rotation" to 0,
                "width" to 1080,
                "height" to 1920
            )
        }
    }

    /**
     * 获取屏幕方向
     */
    fun getScreenRotation(): Int {
        return try {
            val displayInfo = getDisplayInfo()
            displayInfo["rotation"] as? Int ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * 获取完整的系统信息
     */
    fun getSystemInfo(): Map<String, Any> {
        val displayInfo = try {
            getDisplayInfo()
        } catch (e: Exception) {
            mapOf("rotation" to 0, "width" to 1080, "height" to 1920)
        }

        // 内存信息
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryInfo = mapOf(
            "total" to maxMemory,
            "free" to freeMemory,
            "totalStr" to formatSize(maxMemory),
            "freeStr" to formatSize(freeMemory)
        )

        // 存储信息（通过StatFs）
        val diskInfo = try {
            getDiskInfo()
        } catch (e: Exception) {
            mapOf(
                "phoneTotalStr" to "N/A",
                "phoneFreeStr" to "N/A",
                "sdcardTotalStr" to "N/A",
                "sdcardFreeStr" to "N/A"
            )
        }

        // 设备信息
        val devicesInfo = mapOf(
            "deviceId" to getDeviceId(),
            "imei" to "N/A",
            "meid" to "N/A",
            "pseudoID" to "N/A"
        )

        return mapOf(
            "clientVersion" to 12403,
            "brand" to Build.BRAND,
            "id" to Build.ID,
            "display" to Build.DISPLAY,
            "product" to Build.PRODUCT,
            "device" to Build.DEVICE,
            "board" to Build.BOARD,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sku" to "unknown",
            "socManufacturer" to Build.MANUFACTURER,
            "socModel" to Build.HARDWARE,
            "abis" to Build.SUPPORTED_ABIS.toList(),
            "bootloader" to Build.BOOTLOADER,
            "hardware" to Build.HARDWARE,
            "serial" to getDeviceId(),
            "sdkInt" to Build.VERSION.SDK_INT.toString(),
            "release" to Build.VERSION.RELEASE,
            "displayInfo" to displayInfo,
            "memoryInfo" to memoryInfo,
            "diskInfo" to diskInfo,
            "devicesInfo" to devicesInfo
        )
    }

    /**
     * 通过WindowManager服务获取显示信息
     */
    private fun getDisplayInfo(): Map<String, Any> {
        return try {
            // 通过反射获取WindowManager服务
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "window")

            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
            val wm = asInterface.invoke(null, binder)

            // 获取Display信息
            val getInitialDisplaySize = wm.javaClass.getMethod("getInitialDisplaySize", Int::class.javaPrimitiveType, Class.forName("android.graphics.Point"))
            val point = Class.forName("android.graphics.Point").newInstance()
            getInitialDisplaySize.invoke(wm, 0, point)

            val x = point.javaClass.getField("x").get(point) as Int
            val y = point.javaClass.getField("y").get(point) as Int

            // 获取旋转
            val getRotation = wm.javaClass.getMethod("getDefaultDisplayRotation")
            val rotation = getRotation.invoke(wm) as Int

            mapOf(
                "rotation" to rotation,
                "width" to x,
                "height" to y
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // 默认值
            mapOf(
                "rotation" to 0,
                "width" to 1080,
                "height" to 1920
            )
        }
    }

    /**
     * 获取磁盘信息
     */
    private fun getDiskInfo(): Map<String, Any> {
        return try {
            val statFs = android.os.StatFs("/sdcard")
            val blockSize = statFs.blockSizeLong
            val totalBlocks = statFs.blockCountLong
            val availableBlocks = statFs.availableBlocksLong

            val totalSize = totalBlocks * blockSize
            val freeSize = availableBlocks * blockSize

            mapOf(
                "sdcardTotal" to totalSize,
                "sdcardFree" to freeSize,
                "sdcardTotalStr" to formatSize(totalSize),
                "sdcardFreeStr" to formatSize(freeSize),
                "phoneTotal" to totalSize,
                "phoneFree" to freeSize,
                "phoneTotalStr" to formatSize(totalSize),
                "phoneFreeStr" to formatSize(freeSize)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            mapOf(
                "sdcardTotalStr" to "N/A",
                "sdcardFreeStr" to "N/A",
                "phoneTotalStr" to "N/A",
                "phoneFreeStr" to "N/A"
            )
        }
    }

    /**
     * 获取系统属性
     */
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatSize(size: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = size.toDouble()
        var unitIndex = 0
        
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        
        return String.format("%.1f%s", value, units[unitIndex])
    }
}
