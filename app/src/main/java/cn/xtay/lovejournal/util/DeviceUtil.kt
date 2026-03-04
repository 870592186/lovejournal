package cn.xtay.lovejournal.util

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.app.NotificationManager
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.*

object DeviceUtil {

    private var rawStepCount: Int = 0

    /**
     * 【核心优化】：获取今日步数（从零点起算）
     */
    fun getStepCount(context: Context): Int {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null && event.values.isNotEmpty()) {
                        rawStepCount = event.values[0].toInt()
                        sensorManager.unregisterListener(this)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val savedDay = UserPrefs.getStepDay(context)
        var baseline = UserPrefs.getStepBaseline(context)

        if (todayStr != savedDay) {
            UserPrefs.saveStepDay(context, todayStr)
            UserPrefs.saveStepBaseline(context, rawStepCount)
            return 0
        }

        if (rawStepCount < baseline) {
            baseline = 0
            UserPrefs.saveStepBaseline(context, 0)
        }

        return (rawStepCount - baseline).coerceAtLeast(0)
    }

    fun getBatteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        return if (isCharging) level + 100 else level
    }

    fun getNetworkInfo(context: Context): Pair<String, String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return "无网络" to ""
        val actNw = cm.getNetworkCapabilities(nw) ?: return "无网络" to ""
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ssid = wm.connectionInfo.ssid.replace("\"", "")
                "WiFi" to if (ssid == "<unknown ssid>") "已连接" else ssid
            }
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据" to "运营商网络"
            else -> "未知" to ""
        }
    }

    /**
     * 💖 完美修复：获取完整的前台应用名称（中文名）
     */
    fun getForegroundApp(context: Context): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val time = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)

        if (!stats.isNullOrEmpty()) {
            val pkg = stats.maxByOrNull { it.lastTimeUsed }?.packageName ?: return "桌面"

            // 将包名翻译为桌面显示的应用名
            return try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()

                // 过滤一些常见的系统组件名，统一显示为桌面
                if (pkg.contains("launcher") || pkg.contains("trebuchet") || label.contains("桌面")) {
                    "桌面"
                } else {
                    label
                }
            } catch (e: Exception) {
                // 如果解析失败（如系统组件），则返回包名的最后一段作为备选
                pkg.split(".").last()
            }
        }
        return "桌面"
    }

    fun getTopAppsRankingJson(context: Context): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        if (stats.isNullOrEmpty()) return "[]"
        val rankingList = stats.filter { it.totalTimeInForeground > 0 && it.lastTimeUsed >= startTime }
            .groupBy { it.packageName }.mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
            .toList().sortedByDescending { it.second }.take(10)
            .mapNotNull { (pkg, time) ->
                try {
                    if (pkg.contains("android") || pkg.contains("launcher") || pkg.contains("service")) return@mapNotNull null
                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    val mins = time / 60000
                    if (mins <= 0) return@mapNotNull null
                    val timeStr = if (mins >= 60) "${mins / 60}h${mins % 60}m" else "${mins}min"
                    mapOf("app" to label, "time" to timeStr)
                } catch (e: Exception) { null }
            }.distinctBy { it["app"] }.take(3)
        return Gson().toJson(rankingList)
    }

    fun getMicBusyStatus(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val isComm = am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_IN_CALL
        val isRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { am.activeRecordingConfigurations.isNotEmpty() } catch (e: Exception) { false }
        } else false
        return if (isComm || isRecording) 1 else 0
    }

    // ==========================================
    // 💖 新增：配合策略管理器 (StrategyManager) 的工具方法
    // ==========================================

    /**
     * 判断当前屏幕是否亮起
     */
    fun isScreenOn(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    /**
     * 判断系统是否处于“免打扰(Do Not Disturb)”模式
     */
    fun isDndModeOn(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }
    }
}