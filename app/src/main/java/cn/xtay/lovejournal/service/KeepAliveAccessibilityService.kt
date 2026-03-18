package cn.xtay.lovejournal.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.lang.ref.WeakReference

class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        // 💖 终极优化：使用弱引用 (WeakReference) 包裹 Context，彻底消灭内存泄漏隐患！
        private var instanceRef: WeakReference<KeepAliveAccessibilityService>? = null

        // 供外部获取实例的安全通道
        val instance: KeepAliveAccessibilityService?
            get() = instanceRef?.get()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this) // 💖 安全绑定

        // 💖 原有功能：当机主开启无障碍时，确保核心服务被拉起
        try {
            val intent = Intent(this, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 💖 终极防线补齐：无障碍服务也可能遭遇 Android 12+ 的后台启动限制，上 WorkManager 兜底！
            try {
                val reviveRequest = OneTimeWorkRequestBuilder<SyncWorker>().build()
                WorkManager.getInstance(this).enqueue(reviveRequest)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 彻底静默，不消耗任何 Kotlin 层的 CPU 资源
    }

    override fun onInterrupt() {
        // 彻底静默
    }

    override fun onDestroy() {
        super.onDestroy()
        // 💖 销毁时安全清理，滴水不漏
        instanceRef?.clear()
        instanceRef = null
    }

    /**
     * 💖 核心新增：执行全局动作（如回桌面、截图等）
     * 供 RemoteCommandExecutor 直接调用
     */
    fun performGlobal(action: Int) {
        performGlobalAction(action)
    }
}