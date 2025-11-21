package com.autobot.http

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import timber.log.Timber
import java.io.IOException

/**
 * HTTP API 服务器
 * 基于 NanoHTTPD 实现
 */
class HttpServer(
    private val context: Context,
    private val port: Int = 7777
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "HttpServer"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Timber.d("$method $uri")

        return try {
            when {
                // 健康检查
                uri == "/api/hello" && method == Method.GET -> {
                    handleHello()
                }

                // 设备信息
                uri.startsWith("/api/device") -> {
                    handleDeviceInfo()
                }

                // 其他请求返回 404
                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        """{"code": 0, "message": "Not Found"}"""
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "请求处理失败: $uri")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"code": 0, "message": "Internal Server Error: ${e.message}"}"""
            )
        }
    }

    /**
     * 健康检查
     */
    private fun handleHello(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain",
            "OK"
        )
    }

    /**
     * 设备信息
     */
    private fun handleDeviceInfo(): Response {
        val info = mapOf(
            "model" to android.os.Build.MODEL,
            "brand" to android.os.Build.BRAND,
            "manufacturer" to android.os.Build.MANUFACTURER,
            "android" to android.os.Build.VERSION.RELEASE,
            "sdk" to android.os.Build.VERSION.SDK_INT
        )

        val json = org.json.JSONObject(info).toString()

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"code": 1, "data": $json, "message": "success"}"""
        )
    }

    /**
     * 启动服务器
     */
    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Timber.d("HTTP 服务器已启动: 端口 $port")
        } catch (e: IOException) {
            Timber.e(e, "HTTP 服务器启动失败")
            throw e
        }
    }

    /**
     * 停止服务器
     */
    fun stopServer() {
        try {
            stop()
            Timber.d("HTTP 服务器已停止")
        } catch (e: Exception) {
            Timber.e(e, "HTTP 服务器停止失败")
        }
    }
}

