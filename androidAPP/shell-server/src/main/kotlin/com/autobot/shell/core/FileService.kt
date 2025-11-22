package com.autobot.shell.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 文件操作服务
 */
class FileService {
    
    /**
     * 列出目录下的文件
     * @param path 目录路径
     * @return 文件列表信息
     */
    fun listFiles(path: String): List<Map<String, Any>> {
        return try {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return emptyList()
            }
            
            val files = dir.listFiles() ?: return emptyList()
            files.map { file ->
                mapOf(
                    "name" to file.name,
                    "path" to file.absolutePath,
                    "isDirectory" to file.isDirectory,
                    "size" to file.length(),
                    "lastModified" to file.lastModified()
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * 删除文件
     * @param path 文件路径
     * @return 是否成功
     */
    fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 读取文件内容（Base64编码）
     * @param path 文件路径
     * @return Base64编码的文件内容，失败返回null
     */
    fun readFileBase64(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile) {
                return null
            }
            
            val inputStream = FileInputStream(file)
            val fileSize = file.length().toInt()
            val bytes = ByteArray(fileSize)
            var offset = 0
            var bytesRead: Int
            while (offset < fileSize) {
                bytesRead = inputStream.read(bytes, offset, fileSize - offset)
                if (bytesRead == -1) break
                offset += bytesRead
            }
            inputStream.close()
            
            // 转换为Base64
            val base64Class = Class.forName("android.util.Base64")
            val encodeToStringMethod = base64Class.getMethod(
                "encodeToString",
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            encodeToStringMethod.invoke(null, bytes, 0) as String
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 写入文件（从Base64解码）
     * @param path 文件路径
     * @param base64Content Base64编码的文件内容
     * @return 是否成功
     */
    fun writeFileBase64(path: String, base64Content: String): Boolean {
        return try {
            // Base64解码
            val base64Class = Class.forName("android.util.Base64")
            val decodeMethod = base64Class.getMethod(
                "decode",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val bytes = decodeMethod.invoke(null, base64Content, 0) as ByteArray
            
            // 写入文件
            val file = File(path)
            file.parentFile?.mkdirs()
            
            val outputStream = FileOutputStream(file)
            outputStream.write(bytes)
            outputStream.close()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

