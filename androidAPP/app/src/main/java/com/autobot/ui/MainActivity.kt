package com.autobot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autobot.databinding.ActivityMainBinding
import com.autobot.shell.ShellServerManager
import com.autobot.service.WirelessDebugPairingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 主界面 - 简化版（只保留 Shell Server）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var shellServerManager: ShellServerManager

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
        private const val PREFS_NAME = "AutoBotPrefs"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shellServerManager = ShellServerManager(this)

        setupViews()
        
        // 首次启动检查悬浮窗权限（会延迟检查其他权限）
        checkOverlayPermissionOnFirstLaunch()
    }
    
    /**
     * 首次启动时检查悬浮窗权限
     */
    private fun checkOverlayPermissionOnFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        
        if (isFirstLaunch) {
            // 标记为已启动过
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
            
            // 检查悬浮窗权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    Timber.d("首次启动，请求悬浮窗权限")
                    // 延迟显示，确保 Activity 完全启动
                    binding.root.post {
                        showOverlayPermissionDialog()
                    }
                    return // 不立即检查其他权限
                }
            }
        }
        
        // 如果不是首次启动或悬浮窗权限已授予，检查其他权限
        checkAndRequestPermissions()
    }
    
    /**
     * 显示悬浮窗权限说明对话框
     */
    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage(
                "AutoBot 需要「显示在其他应用上层」权限以提供更好的自动化体验。\n\n" +
                        "此权限用于：\n" +
                        "• 显示悬浮控制球\n" +
                        "• 快速操作面板\n" +
                        "• 实时日志显示\n\n" +
                        "您可以随时在设置中关闭此权限。"
            )
            .setPositiveButton("去设置") { _, _ ->
                requestOverlayPermission()
            }
            .setNegativeButton("暂不需要") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(
                    this,
                    "您可以稍后在设置中授予此权限",
                    Toast.LENGTH_SHORT
                ).show()
                // 对话框关闭后，检查其他权限
                checkAndRequestPermissions()
            }
            .setCancelable(false)
            .setOnDismissListener {
                // 无论如何关闭对话框，都继续检查其他权限
                checkAndRequestPermissions()
            }
            .show()
    }
    
    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            } catch (e: Exception) {
                Timber.e(e, "打开悬浮窗权限设置失败")
                Toast.makeText(
                    this,
                    "无法打开设置页面，请手动在设置中授予权限",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * 检查并请求必要的权限
     */
    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            Timber.d("请求权限: ${missingPermissions.joinToString()}")
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        } else {
            Timber.d("所有权限已授予")
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_PERMISSIONS) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }
            
            if (deniedPermissions.isNotEmpty()) {
                Timber.w("部分权限被拒绝: ${deniedPermissions.joinToString()}")
                Toast.makeText(
                    this,
                    "部分权限被拒绝，功能可能受限",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Timber.d("所有权限已授予")
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Timber.i("✓ 悬浮窗权限已授予")
                    Toast.makeText(
                        this,
                        "✅ 悬浮窗权限已授予",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Timber.w("悬浮窗权限被拒绝")
                    Toast.makeText(
                        this,
                        "悬浮窗权限未授予，部分功能可能受限",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            // 从悬浮窗权限设置返回后，检查其他权限
            checkAndRequestPermissions()
        }
    }

    private fun setupViews() {
        // 配对按钮
        binding.btnStartPairing.setOnClickListener {
            startPairing()
        }

        // 启动 Shell Server 按钮
        binding.btnStartShellServer.setOnClickListener {
            startShellServer()
        }

        // 停止 Shell Server 按钮
        binding.btnStopShellServer.setOnClickListener {
            stopShellServer()
        }
    }

    /**
     * 开始无线调试配对（通知栏方式）
     */
    private fun startPairing() {
        Timber.d("开始配对")
        
        // 总是显示引导对话框（让用户确认是否已开启无线调试）
        showDeveloperOptionsDialog()
    }

    /**
     * 显示开发者选项引导对话框
     */
    private fun showDeveloperOptionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("无线调试配对")
            .setMessage(
                "📱 开始配对前，请先确认：\n\n" +
                        "✓ 已开启「开发者选项」→「无线调试」\n" +
                        "✓ 可以看到「使用配对码配对设备」选项\n\n" +
                        "如果还没开启，请点击「去开发者选项」"
            )
            .setPositiveButton("去开发者选项") { _, _ ->
                try {
                    // 跳转到开发者选项
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    
                    // 提示用户操作
                    Toast.makeText(
                        this,
                        "请开启「无线调试」后返回",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Timber.e(e, "打开开发者选项失败")
                    Toast.makeText(
                        this,
                        "无法打开设置\n请手动进入：设置 → 更多设置 → 开发者选项",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("已开启，开始配对") { _, _ ->
                // 启动配对服务
                WirelessDebugPairingService.startPairing(this)
                
                Toast.makeText(
                    this,
                    "✅ 配对服务已启动\n\n" +
                            "1. 开发者选项 → 无线调试 → 使用配对码配对设备\n" +
                            "2. 在通知栏输入配对码",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 启动 Shell Server
     */
    private fun startShellServer() {
        Timber.d("启动 Shell Server")
        scope.launch {
            binding.tvShellStatus.text = "⏳ Shell Server: 启动中..."
            binding.tvServerUrl.text = ""
            binding.tvServerUrl.visibility = android.view.View.GONE
            
            val success = withContext(Dispatchers.IO) {
                shellServerManager.deployAndStart()
            }
            
            if (success) {
                binding.tvShellStatus.text = "🟢 Shell Server: 运行中"
                binding.tvServerUrl.text = "✓ API: http://127.0.0.1:19090/api/*"
                binding.tvServerUrl.visibility = android.view.View.VISIBLE
                Toast.makeText(
                    this@MainActivity,
                    "✅ Shell Server 已启动！\n\n请在电脑执行:\nadb forward tcp:19090 tcp:19090",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                binding.tvShellStatus.text = "🔴 Shell Server: 启动失败"
                binding.tvServerUrl.visibility = android.view.View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "❌ Shell Server 启动失败！\n请检查是否已配对并开启无线调试。",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 停止 Shell Server
     */
    private fun stopShellServer() {
        Timber.d("停止 Shell Server")
        scope.launch {
            binding.tvShellStatus.text = "⏳ Shell Server: 停止中..."
            
            val success = withContext(Dispatchers.IO) {
                shellServerManager.stop()
            }
            
            if (success) {
                binding.tvShellStatus.text = "⚫ Shell Server: 未启动"
                binding.tvServerUrl.text = ""
                binding.tvServerUrl.visibility = android.view.View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "✅ Shell Server 已停止",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "⚠️ 停止可能未完全成功",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
