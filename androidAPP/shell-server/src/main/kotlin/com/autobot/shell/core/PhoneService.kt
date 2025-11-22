package com.autobot.shell.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 电话服务
 */
class PhoneService(private val context: Context) {
    
    /**
     * 拨打电话
     * @param phoneNumber 电话号码
     * @return 是否成功
     */
    fun callPhone(phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 挂断电话
     * @return 是否成功
     */
    fun endCall(): Boolean {
        return try {
            // 使用ITelephony接口挂断电话
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                as android.telephony.TelephonyManager
            
            val telephonyClass = Class.forName("com.android.internal.telephony.ITelephony")
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, Context.TELEPHONY_SERVICE)
            
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            val telephony = asInterfaceMethod.invoke(null, binder)
            
            val endCallMethod = telephonyClass.getMethod("endCall")
            endCallMethod.invoke(telephony) as Boolean
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

