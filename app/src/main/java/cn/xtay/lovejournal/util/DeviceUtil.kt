package cn.xtay.lovejournal.util

import android.app.NotificationManager
import android.app.usage.UsageEvents
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
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.*

object DeviceUtil {

    private var rawStepCount: Int = 0

    fun getStepCount(context: Context): Int {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (stepSensor != null) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null && event.values.isNotEmpty()) {
                        rawStepCount = event.values[0].toInt()
                        // 拿到最新数据后立刻注销，防止传感器持续耗电
                        sensorManager.unregisterListener(this)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_FASTEST)
        }

        // ⚠️ 注：由于传感器回调是异步的，本次 return 的大概率是内存里缓存的上一次的 rawStepCount。
        // 这在每 10 秒轮询的架构下是完全可接受的“一帧延迟”。
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
                val ssid = wm.connectionInfo.ssid?.replace("\"", "") ?: "已连接"
                "WiFi" to if (ssid == "<unknown ssid>") "已连接" else ssid
            }
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据" to "运营商网络"
            else -> "未知" to ""
        }
    }

    /**
     * 🚀 纯净版实时前台探测：基于 UsageStatsManager 事件流
     */
    fun getForegroundApp(context: Context): String {
        // 1. 息屏/伪装拦截
        val state = context.getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state == 1 || state == 2 || !isScreenOn(context)) {
            return "息屏睡眠 💤"
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager
        val endTime = System.currentTimeMillis()

        // 💡 性能优化：10秒轮询一次，时间窗口最多查过去 1 分钟足矣，极大减少系统遍历负担
        val startTime = endTime - 1000 * 60 * 1

        var lastResumedPkg = ""

        // 2. 核心探测：遍历真实发生的物理事件流
        try {
            val events = usm.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastResumedPkg = event.packageName
                }
            }
        } catch (e: Exception) {}

        // 3. 兜底探测：如果在深度省电模式下事件流被吞，用统计保底
        if (lastResumedPkg.isEmpty()) {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            if (!stats.isNullOrEmpty()) {
                lastResumedPkg = stats.maxByOrNull { it.lastTimeUsed }?.packageName ?: ""
            }
        }

        // 4. 判断是不是桌面
        if (lastResumedPkg.isEmpty() || lastResumedPkg.contains("launcher") || lastResumedPkg.contains("trebuchet") || lastResumedPkg == "android") {
            return "桌面"
        }

        // 5. 判断是不是情侣手记本身
        if (lastResumedPkg == context.packageName) {
            return "情侣手记"
        }

        // 6. 翻译其他应用的名字
        return try {
            val info = pm.getApplicationInfo(lastResumedPkg, 0)
            val label = pm.getApplicationLabel(info).toString()
            if (label.contains("桌面")) "桌面" else label
        } catch (e: Exception) {
            // 包名解析失败（比如应用刚被卸载等极端情况），返回包名后缀
            lastResumedPkg.split(".").last()
        }
    }

    fun getTopAppsRankingJson(context: Context): String {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val pm = context.packageManager

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

        if (stats.isNullOrEmpty()) return "[]"

        val rankingList = stats
            .filter { it.totalTimeInForeground > 0 && it.lastTimeUsed >= startTime }
            .groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
            .toList()
            .sortedByDescending { it.second }
            .take(10)
            .mapNotNull { (pkg, time) ->
                try {
                    if (pkg.contains("android") || pkg.contains("launcher") || pkg.contains("service")) return@mapNotNull null

                    val info = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(info).toString()
                    val mins = time / 60000

                    if (mins <= 0) return@mapNotNull null
                    val timeStr = if (mins >= 60) "${mins / 60}h${mins % 60}m" else "${mins}min"

                    mapOf("app" to label, "time" to timeStr)
                } catch (e: Exception) {
                    null
                }
            }
            .distinctBy { it["app"] }
            .take(3)

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

    fun isScreenOn(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    fun isDndModeOn(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        } else {
            false
        }
    }
}