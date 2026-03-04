package cn.xtay.lovejournal.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cn.xtay.lovejournal.service.LocationService
import cn.xtay.lovejournal.service.SyncWorker // 💖 引入我们刚写的特工

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        startCoreService(context)
    }

    // 🌟 只要手机有动静（如解锁、锁屏、开机等广播），就会来到这里触发复活！
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 如果核心服务挂了，立刻尝试复活
        if (!LocationService.isRunning) {
            startCoreService(context)
        }
    }

    private fun startCoreService(context: Context) {
        try {
            val serviceIntent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 💖 终极防线：如果因为 Android 12+ 的后台限制导致抛出异常（直接启动失败）
            // 立刻呼叫 WorkManager 提交一个一次性任务，进行“合法复活”！
            try {
                val reviveRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
                WorkManager.getInstance(context).enqueue(reviveRequest)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}