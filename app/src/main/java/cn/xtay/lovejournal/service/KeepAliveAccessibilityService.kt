package cn.xtay.lovejournal.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        // 💖 关键修复：定义单例，让 RemoteCommandExecutor 能找到它
        var instance: KeepAliveAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this // 💖 绑定实例

        // 💖 原有功能：当机主开启无障碍时，确保核心服务被拉起
        try {
            val intent = Intent(this, LocationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 彻底静默，不消耗任何 CPU 资源
    }

    override fun onInterrupt() {
        // 彻底静默
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null // 💖 销毁时清理引用，防止内存泄漏
    }

    /**
     * 💖 核心新增：执行全局动作（如回桌面、截图等）
     * 供 RemoteCommandExecutor 直接调用
     */
    fun performGlobal(action: Int) {
        performGlobalAction(action)
    }
}