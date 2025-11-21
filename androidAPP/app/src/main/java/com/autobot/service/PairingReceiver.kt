package com.autobot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import timber.log.Timber

/**
 * 配对广播接收器
 * 接收通知栏输入的配对码
 */
class PairingReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PairingReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("onReceive: action=${intent.action}")
        
        when (intent.action) {
            WirelessDebugPairingService.ACTION_PAIR -> {
                // 从 RemoteInput 获取配对码
                val results = RemoteInput.getResultsFromIntent(intent)
                val pairingCode = results?.getCharSequence(WirelessDebugPairingService.KEY_PAIRING_CODE)?.toString()
                
                if (pairingCode != null) {
                    Timber.i("✓ 收到配对码: $pairingCode")
                    
                    // 启动服务并传递配对码
                    val serviceIntent = Intent(context, WirelessDebugPairingService::class.java).apply {
                        action = WirelessDebugPairingService.ACTION_PAIR
                        putExtra(WirelessDebugPairingService.KEY_PAIRING_CODE, pairingCode)
                    }
                    context.startService(serviceIntent)
                } else {
                    Timber.e("✗ 配对码为空")
                }
            }
        }
    }
}

