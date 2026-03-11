package cn.xtay.lovejournal.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import cn.xtay.lovejournal.R

class LocationTileService : TileService() {

    /**
     * 更新磁贴视觉状态的核心方法
     */
    private fun updateTileState() {
        val tile = qsTile ?: return
        val isAccOn = isAccessibilityEnabled()
        val isLocRunning = LocationService.isRunning

        // 根据服务运行状态切换 状态(State)、文字(Label) 和 图标(Icon)
        if (isAccOn && isLocRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "守护：完美运行"
            tile.icon = Icon.createWithResource(this, R.drawable.ic_location_tile_on)
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = if (!isAccOn) "守护：去开无障碍" else "守护：点击开启"
            // 🛑 修复UI不刷新的Bug：必须给关闭状态也设置Icon。
            // 直接复用同一个Icon即可，安卓系统会自动把它渲染成灰色(Inactive)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_location_tile_on)
        }

        // 必须调用此方法，改动才会生效
        tile.updateTile()
    }

    /**
     * 采用与 LocationService 一致的严谨判断逻辑，防止在某些国产系统上误判
     */
    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (enabledServices.isNullOrEmpty()) return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals("${packageName}/${cn.xtay.lovejournal.service.KeepAliveAccessibilityService::class.java.name}", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * 🌟 隐形复活祭坛：当用户下拉通知栏时，系统会强制调用这里
     */
    override fun onStartListening() {
        super.onStartListening()

        // 趁着系统唤醒我们，赶紧做个自检：
        // 如果无障碍权限是在开着的，但是我们的核心 LocationService 却被系统杀了
        if (isAccessibilityEnabled() && !LocationService.isRunning) {
            // 直接借系统下拉栏的东风，静默复活核心服务！
            startCoreService(isSilent = true)
        }

        updateTileState()
    }

    /**
     * 当用户点击磁贴时触发
     */
    override fun onClick() {
        super.onClick()

        if (!isAccessibilityEnabled()) {
            // 情况 1：无障碍没开 -> 引导用户去开启
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } else {
            // 情况 2：无障碍已开 -> 切换 LocationService 的开关状态
            if (LocationService.isRunning) {
                try {
                    stopService(Intent(this, LocationService::class.java))
                } catch (e: Exception) { e.printStackTrace() }
            } else {
                startCoreService(isSilent = false)
            }
        }

        // 🚀 核心优化：异步延时刷新 UI，解决磁贴点击后看起来“卡顿”不刷新的问题
        // 给系统 300 毫秒的时间去拉起或杀死服务，然后再读取 isRunning 状态
        Handler(Looper.getMainLooper()).postDelayed({
            updateTileState()
        }, 300)
    }

    /**
     * 启动核心服务
     * @param isSilent 是否是后台静默唤醒（如果是后台唤醒，启动失败不弹Toast打扰用户）
     */
    private fun startCoreService(isSilent: Boolean) {
        try {
            val serviceIntent = Intent(this, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 拦截 Android 12+ 后台启动前台服务被拒的异常
            if (!isSilent) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(applicationContext, "启动失败，请打开应用后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}