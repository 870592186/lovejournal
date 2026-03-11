package cn.xtay.lovejournal.util

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.xtay.lovejournal.service.KeepAliveAccessibilityService
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

object RemoteCommandExecutor {

    fun execute(context: Context, command: String) {
        // 🛑 已移除本地 Toast 反馈，实现静默执行
        when (command) {
            "lock_screen" -> lockScreen(context)
            "go_home" -> goHome(context)
            "open_wechat" -> openApp(context, "com.tencent.mm")
            "take_screenshot" -> takeScreenshot()
            "fly_heart" -> HeartEffectUtil.showFloatingHeart(context) // 💖 核心新增：收到指令，直接起飞！
            else -> { return /* 未知指令忽略并退出，不执行清理 */ }
        }

        // 🚀 核心自愈：一旦指令执行成功，立刻跨网追杀，抹除服务器上的残留指令
        clearCommandOnServer(context)
    }

    /**
     * 独立子线程 HTTP 处决器：直接暴力删除该用户的云端待执行命令
     */
    private fun clearCommandOnServer(context: Context) {
        val uid = UserPrefs.getUserId(context)
        val serverUrl = UserPrefs.getServerUrl(context)
        if (uid <= 0 || serverUrl.isEmpty()) return

        Thread {
            try {
                // 构建轻量级网络请求，直接访问 api.php
                val url = "$serverUrl/api.php"
                val formBody = FormBody.Builder()
                    .add("action", "clear_command") // 🚀 专属强杀指令
                    .add("user_id", uid.toString())
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()

                // 执行请求（不需要关心返回值，发出去就结束）
                OkHttpClient().newCall(request).execute().use { response -> }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
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