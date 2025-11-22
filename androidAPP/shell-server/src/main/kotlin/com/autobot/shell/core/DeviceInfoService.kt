package com.autobot.shell.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.*

/**
 * 设备信息服务
 * 提供设备ID、IP地址、屏幕信息等
 */
class DeviceInfoService(private val context: Context) {

    /**
     * 获取设备ID（Android ID）
     */
    fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
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
     * 获取屏幕信息
     */
    fun getScreenInfo(): Map<String, Any> {
        val displayMetrics = context.resources.displayMetrics
        val rotation = context.display?.rotation ?: 0
        
        return mapOf(
            "rotation" to rotation,
            "width" to displayMetrics.widthPixels,
            "height" to displayMetrics.heightPixels
        )
    }

    /**
     * 获取屏幕方向
     * 0: 竖直向上
     * 1: 逆时针旋转90度
     * 2: 旋转180度
     * 3: 顺时针旋转90度
     */
    fun getScreenRotation(): Int {
        return context.display?.rotation ?: 0
    }

    /**
     * 获取完整的系统信息
     */
    fun getSystemInfo(): Map<String, Any> {
        val displayMetrics = context.resources.displayMetrics
        val rotation = context.display?.rotation ?: 0
        
        // 显示信息
        val displayInfo = mapOf(
            "rotation" to rotation,
            "width" to displayMetrics.widthPixels,
            "height" to displayMetrics.heightPixels
        )

        // 内存信息
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val memoryInfo = mapOf(
            "total" to totalMemory,
            "free" to freeMemory,
            "totalStr" to formatSize(totalMemory),
            "freeStr" to formatSize(freeMemory)
        )

        // 存储信息（简化版本）
        val diskInfo = mapOf(
            "phoneTotalStr" to "N/A",
            "phoneFreeStr" to "N/A",
            "sdcardTotalStr" to "N/A",
            "sdcardFreeStr" to "N/A"
        )

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
            "serial" to "unknown",
            "sdkInt" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "displayInfo" to displayInfo,
            "memoryInfo" to memoryInfo,
            "diskInfo" to diskInfo,
            "devicesInfo" to devicesInfo
        )
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

