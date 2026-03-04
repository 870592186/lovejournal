package cn.xtay.lovejournal.util

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.xtay.lovejournal.service.KeepAliveAccessibilityService

object RemoteCommandExecutor {

    fun execute(context: Context, command: String) {
        // 🛑 已移除本地 Toast 反馈，实现静默执行
        when (command) {
            "lock_screen" -> lockScreen(context)
            "go_home" -> goHome(context)
            "open_wechat" -> openApp(context, "com.tencent.mm")
            "take_screenshot" -> takeScreenshot()
            else -> { /* 未知指令忽略 */ }
        }
    }

    private fun lockScreen(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun goHome(context: Context) {
        val service = KeepAliveAccessibilityService.instance
        if (service != null) {
            service.performGlobal(AccessibilityService.GLOBAL_ACTION_HOME)
        } else {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    private fun openApp(context: Context, packageName: String) {
        try {
            context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun takeScreenshot() {
        val service = KeepAliveAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobal(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }
}