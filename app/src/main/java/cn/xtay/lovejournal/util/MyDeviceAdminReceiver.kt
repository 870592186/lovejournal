package cn.xtay.lovejournal.util

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import cn.xtay.lovejournal.service.LocationService

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // 💖 激活时不再弹出任何提示，直接启动服务
        startCoreService(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // 💔 取消激活时也静默处理
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 🌟 核心保活逻辑：任何系统动静都会触发此处，确保服务活着
        if (!LocationService.isRunning) {
            startCoreService(context)
        }
    }

    private fun startCoreService(context: Context) {
        try {
            val serviceIntent = Intent(context, LocationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}