package com.autobot.shell.core

import android.content.Context
import android.provider.Telephony

/**
 * 短信服务
 */
class SmsService(private val context: Context) {
    
    /**
     * 获取所有短信
     * @return 短信列表
     */
    fun getAllSms(): List<Map<String, Any>> {
        return try {
            val smsList = mutableListOf<Map<String, Any>>()
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null,
                null,
                null,
                Telephony.Sms.DATE + " DESC"
            )
            
            cursor?.use {
                val idColumn = it.getColumnIndex(Telephony.Sms._ID)
                val addressColumn = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyColumn = it.getColumnIndex(Telephony.Sms.BODY)
                val dateColumn = it.getColumnIndex(Telephony.Sms.DATE)
                val typeColumn = it.getColumnIndex(Telephony.Sms.TYPE)
                
                while (it.moveToNext()) {
                    smsList.add(mapOf(
                        "id" to (if (idColumn >= 0) it.getString(idColumn) else ""),
                        "address" to (if (addressColumn >= 0) it.getString(addressColumn) else ""),
                        "body" to (if (bodyColumn >= 0) it.getString(bodyColumn) else ""),
                        "date" to (if (dateColumn >= 0) it.getLong(dateColumn) else 0L),
                        "type" to (if (typeColumn >= 0) it.getInt(typeColumn) else 0)
                    ))
                }
            }
            
            smsList
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * 发送短信
     * @param phoneNumber 电话号码
     * @param message 短信内容
     * @return 是否成功
     */
    fun sendSms(phoneNumber: String, message: String): Boolean {
        return try {
            val smsManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                as? android.telephony.SmsManager
                ?: android.telephony.SmsManager.getDefault()
            
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

