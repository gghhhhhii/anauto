package com.autobot.shell.core

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shell命令执行服务
 */
class ShellCommandService {

    /**
     * 执行Shell命令
     * @param command 命令
     * @param timeout 超时时间（秒）
     * @return 命令输出
     */
    fun execCommand(command: String, timeout: Int = 10): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            // 读取错误输出
            while (errorReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            process.waitFor()
            reader.close()
            errorReader.close()
            
            output.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "Error: ${e.message}"
        }
    }
}

