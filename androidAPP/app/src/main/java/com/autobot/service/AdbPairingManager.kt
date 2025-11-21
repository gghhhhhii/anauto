package com.autobot.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import timber.log.Timber

/**
 * ADB 配对服务发现管理器
 * 使用 mDNS (NsdManager) 发现 ADB 服务
 */
class AdbPairingManager(
    private val context: Context,
    private val serviceType: String = "_adb-tls-pairing._tcp."
) {
    companion object {
        private const val TAG = "AdbPairingManager"
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp."
    }
    
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private var callback: ((NsdServiceInfo) -> Unit)? = null
    
    fun startDiscovery(onServiceFound: (NsdServiceInfo) -> Unit) {
        callback = onServiceFound
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("发现启动失败: $errorCode")
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("停止发现失败: $errorCode")
            }
            
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("开始发现服务: $serviceType")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("停止发现服务: $serviceType")
            }
            
            override fun onServiceFound(service: NsdServiceInfo) {
                Timber.d("发现服务: ${service.serviceName}")
                
                resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Timber.e("解析失败: $errorCode")
                    }
                    
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Timber.i("✓ 服务解析成功:")
                        Timber.i("  名称: ${serviceInfo.serviceName}")
                        Timber.i("  主机: ${serviceInfo.host}")
                        Timber.i("  端口: ${serviceInfo.port}")
                        
                        callback?.invoke(serviceInfo)
                    }
                }
                
                try {
                    nsdManager?.resolveService(service, resolveListener)
                } catch (e: Exception) {
                    Timber.e(e, "解析服务异常")
                }
            }
            
            override fun onServiceLost(service: NsdServiceInfo) {
                Timber.d("丢失服务: ${service.serviceName}")
            }
        }
        
        try {
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Timber.e(e, "启动服务发现失败")
        }
    }
    
    fun stopDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
        } catch (e: Exception) {
            Timber.e(e, "停止服务发现失败")
        }
    }
}

