package cn.xtay.lovejournal.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.*
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.*
import cn.xtay.lovejournal.LoginActivity
import cn.xtay.lovejournal.MainActivity
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.model.local.AppDatabase
import cn.xtay.lovejournal.model.local.ChatEntity
import cn.xtay.lovejournal.model.local.LocationEntity
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.util.DeviceUtil
import cn.xtay.lovejournal.util.StrategyManager
import cn.xtay.lovejournal.util.UserPrefs
import cn.xtay.lovejournal.widget.CoupleWidgetProvider
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.core.app.NotificationManagerCompat

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val intent = Intent(applicationContext, LocationService::class.java).apply {
            action = "ACTION_WORKER_SYNC"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            try {
                applicationContext.startService(intent)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        return Result.success()
    }
}

class LocationService : Service() {

    companion object {
        var isRunning = false
            private set
        private const val WORK_NAME = "ScreenOffSyncWork"
    }

    private var mLocationClient: AMapLocationClient? = null
    private val CHANNEL_ID = "love_journal_bg_location"
    private val EMERGENCY_CHANNEL_ID = "love_journal_emergency"
    private val MESSAGE_CHANNEL_ID = "love_journal_message"

    private val NOTIFICATION_ID = 2026
    private val MSG_NOTIF_ID = 2027

    private var tempWakeLock: PowerManager.WakeLock? = null

    // 定位引擎核心标志位
    private var isEmergencyLocation = false
    private var isFallbackToGps = false
    private var isGpsRetry = false
    private var pendingHttpSyncOnLocation = false
    private var consecutiveGpsFailures = 0
    private var gpsCooldownEndTime = 0L

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastAddr: String? = null
    private var lastLocationTime: Long = 0L

    private var pendingClearCommandTime: Long = 0L
    private var hasSentLowBatteryWarning = false
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isScreenOn = true

    // 状态差分引擎缓存
    private var wasInSleepMode = false
    private var lastSyncApp: String = ""
    private var lastSyncBattery: Int = -1
    private var lastSyncMic: Int = -1

    // 传感器监控系统
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var isMoving = false
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private var lastSensorUpdateTime = 0L

    private fun getCurrentBattery(): Int {
        val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
        val mockLevel = prefs.getInt("mock_battery_level", -1)
        if (mockLevel != -1) {
            return mockLevel
        }
        return DeviceUtil.getBatteryLevel(this)
    }

    private fun playHeartbeatVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                val pattern = longArrayOf(0, 100, 150, 100)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showEmergencyNotification(msg: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "紧急警报",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "对方电量濒危或紧急通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 150, 200, 150, 200)
            }
            notificationManager.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚨 紧急警报")
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(9999, notification)
    }

    private fun showMessageNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notifText = UserPrefs.getNotifNewMsg(this).ifEmpty { "收到一条新消息" }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("提示")
            .setContentText(notifText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(MSG_NOTIF_ID, notification)
    }

    private fun showTemporaryStatus(msg: String, durationMs: Long = 4000L) {
        val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state != 0) {
            uploadData(lastLat, lastLng, lastAddr, "息屏睡眠 💤")
        } else {
            uploadData(lastLat, lastLng, lastAddr, msg)
            syncHandler.postDelayed({
                lastSyncApp = "FORCE_RECOVER_STATE"
            }, durationMs)
        }
    }

    private fun releasePendingCommands() {
        val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
        var hasPending = false

        if (prefs.getBoolean("pending_heart", false)) {
            prefs.edit().putBoolean("pending_heart", false).apply()
            hasPending = true
            Handler(Looper.getMainLooper()).postDelayed({
                cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(this@LocationService)
            }, 1500)
        }

        val pendingCmds = prefs.getString("pending_cmds", "") ?: ""
        if (pendingCmds.isNotEmpty()) {
            prefs.edit().putString("pending_cmds", "").apply()
            hasPending = true
            val cmds = pendingCmds.split(",")
            Handler(Looper.getMainLooper()).postDelayed({
                cmds.forEach { cmd ->
                    if (cmd.isNotBlank()) {
                        try {
                            cn.xtay.lovejournal.util.RemoteCommandExecutor.execute(this@LocationService, cmd)
                        } catch (e: Exception) {}
                    }
                }
            }, 2000)
        }
        if (hasPending) {
            showTemporaryStatus("✅ 亮屏补发错过的魔法/指令")
        }
    }

    private val wsListener = object : WebSocketManager.MessageListener {
        override fun onCommandReceived(command: String, data: String) {
            val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
            val state = prefs.getInt("dev_sleep_state", 0)

            when (command) {
                // 🚀 核心新增：静默接收 Pong 回执，证明连接依然活着，不做多余处理
                "pong" -> {
                    // 底层通道畅通无阻，无需进行 UI 操作
                }

                // --- 聊天消息逻辑 (保留原貌) ---
                "read_ack" -> {
                    try {
                        val json = JSONObject(data)
                        val msgIds = json.optJSONArray("msg_ids")
                        if (msgIds != null && msgIds.length() > 0) {
                            CoroutineScope(Dispatchers.IO).launch {
                                val dao = AppDatabase.getDatabase(this@LocationService).chatDao()
                                for (i in 0 until msgIds.length()) {
                                    dao.updateMessageStatus(msgIds.getString(i), 3)
                                }
                                withContext(Dispatchers.Main) {
                                    val intent = Intent("cn.xtay.lovejournal.ACTION_CHAT_MSG")
                                    intent.setPackage(packageName)
                                    sendBroadcast(intent)
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                "receive_chat_msg" -> {
                    if (state == 0) showMessageNotification()
                    CoroutineScope(Dispatchers.IO).launch {
                        saveIncomingMessageToDb(data)
                        withContext(Dispatchers.Main) {
                            val intent = Intent("cn.xtay.lovejournal.ACTION_CHAT_MSG")
                            intent.setPackage(packageName)
                            sendBroadcast(intent)
                        }
                    }
                }
                "receive_offline_messages" -> {
                    if (state == 0) showMessageNotification()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val array = JSONArray(data)
                            for (i in 0 until array.length()) {
                                saveIncomingMessageToDb(array.getJSONObject(i).toString())
                            }
                            withContext(Dispatchers.Main) {
                                val intent = Intent("cn.xtay.lovejournal.ACTION_CHAT_MSG")
                                intent.setPackage(packageName)
                                sendBroadcast(intent)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                "msg_ack" -> {
                    try {
                        val json = JSONObject(data)
                        val ackMsgId = json.optString("msg_id", "")
                        val ackStatus = json.optInt("status", 0)
                        if (ackMsgId.isNotEmpty()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                AppDatabase.getDatabase(this@LocationService).chatDao().updateMessageStatus(ackMsgId, ackStatus)
                                withContext(Dispatchers.Main) {
                                    val intent = Intent("cn.xtay.lovejournal.ACTION_CHAT_MSG")
                                    intent.setPackage(packageName)
                                    sendBroadcast(intent)
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                // --- 状态更新逻辑 (保留原貌) ---
                "partner_status_update" -> {
                    try {
                        var pData = data
                        if (pData.startsWith("{") && JSONObject(pData).has("data")) {
                            val inner = JSONObject(pData).getString("data")
                            if (inner.startsWith("{")) pData = inner
                        }
                        val payload = JSONObject(pData)
                        val dev = payload.optJSONObject("device")
                        val battery = dev?.optInt("battery") ?: 0
                        val fgApp = dev?.optString("foreground_app") ?: ""
                        val locAddress = payload.optJSONObject("location")?.optString("address") ?: ""

                        UserPrefs.saveWidgetData(this@LocationService, battery, fgApp, locAddress)
                        UserPrefs.savePartnerDeviceJson(this@LocationService, pData)
                        CoupleWidgetProvider.updateAllWidgets(this@LocationService)
                        sendBroadcast(Intent("cn.xtay.lovejournal.ACTION_PARTNER_UPDATE").setPackage(packageName))
                    } catch (e: Exception) { e.printStackTrace() }
                }

                // --- 浪漫魔法与指令 (保留原貌) ---
                "fly_heart" -> {
                    if (state != 0) {
                        prefs.edit().putBoolean("pending_heart", true).apply()
                    } else {
                        if (isScreenOn) {
                            cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(this@LocationService)
                            showTemporaryStatus("✅ 实时响应浪漫魔法")
                        } else {
                            prefs.edit().putBoolean("pending_heart", true).apply()
                            playHeartbeatVibration()
                            showTemporaryStatus("💓 息屏心电感应成功")
                        }
                    }
                }
                "low_battery_alert" -> {
                    val msg = if (data.contains("msg")) JSONObject(data).optString("msg") else "TA的手机电量严重不足！"
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@LocationService, "🚨 紧急通知：$msg", Toast.LENGTH_LONG).show()
                    }
                    showEmergencyNotification(msg)
                }
                "sync_period" -> {
                    sendBroadcast(Intent("cn.xtay.lovejournal.WS_COMMAND").apply {
                        setPackage(packageName)
                        putExtra("command", "sync_period")
                    })
                }
                "force_location" -> {
                    if (state == 1 || state == 2) {
                        showTemporaryStatus("📡 收到召唤，正在紧急定位...")
                    } else {
                        acquireTempWakeLock()
                        showTemporaryStatus("📡 收到召唤，正在紧急定位...")
                        triggerSingleLocation(isEmergency = true, forceHttp = false)
                    }
                }

                else -> {
                    if (state != 0) {
                        val pendingCmds = prefs.getString("pending_cmds", "") ?: ""
                        val newCmds = if (pendingCmds.isEmpty()) command else "$pendingCmds,$command"
                        prefs.edit().putString("pending_cmds", newCmds).apply()
                    } else {
                        try {
                            cn.xtay.lovejournal.util.RemoteCommandExecutor.execute(this@LocationService, command)
                            showTemporaryStatus("✅ 实时响应指令: $command")
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        }
    }

    private fun acquireTempWakeLock(timeoutMs: Long = 20000L) {
        if (tempWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoveJournal:TempWakeLock")
            tempWakeLock?.setReferenceCounted(false)
        }
        tempWakeLock?.acquire(timeoutMs)
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSensorUpdateTime > 1000) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    if (lastSensorUpdateTime != 0L) {
                        val delta = Math.abs(x + y + z - lastAccelX - lastAccelY - lastAccelZ)
                        isMoving = delta > 2.0f
                    }

                    lastAccelX = x
                    lastAccelY = y
                    lastAccelZ = z
                    lastSensorUpdateTime = currentTime
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val dynamicDiffSyncRunnable = object : Runnable {
        override fun run() {
            if (!isScreenOn) return
            val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
            val state = prefs.getInt("dev_sleep_state", 0)

            if (state == 1 || state == 2) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour in 8..12) {
                    prefs.edit().putInt("dev_sleep_state", 0).apply()
                }
            }

            val finalState = prefs.getInt("dev_sleep_state", 0)

            if (finalState != 0) {
                if (!wasInSleepMode) {
                    wasInSleepMode = true
                    uploadData(lastLat, lastLng, lastAddr, "息屏睡眠 💤")
                }
                syncHandler.postDelayed(this, 10000L)
                return
            }

            if (wasInSleepMode) {
                wasInSleepMode = false
                releasePendingCommands()
            }

            val currentApp = DeviceUtil.getForegroundApp(this@LocationService)
            val currentBattery = getCurrentBattery()
            val currentMic = DeviceUtil.getMicBusyStatus(this@LocationService)

            val hasChanged = currentApp != lastSyncApp || currentBattery != lastSyncBattery || currentMic != lastSyncMic

            if (hasChanged) {
                lastSyncApp = currentApp
                lastSyncBattery = currentBattery
                lastSyncMic = currentMic

                val netInfo = DeviceUtil.getNetworkInfo(this@LocationService).first
                if (netInfo != "无网络") {
                    uploadDataViaWebSocket()
                }
                checkLowBatteryAndSync()
            }

            val nextDelay = if (isMoving) 10000L else 30000L
            syncHandler.postDelayed(this, nextDelay)
        }
    }

    private val periodicLocationRunnable = object : Runnable {
        override fun run() {
            val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
            if (state == 0 && isScreenOn) {
                val netInfo = DeviceUtil.getNetworkInfo(this@LocationService).first
                if (netInfo != "无网络" && netInfo != "WiFi" && isMoving) {
                    triggerSingleLocation(isEmergency = false, forceHttp = false)
                }
            }
            syncHandler.postDelayed(this, 300000L)
        }
    }

    // 🚀 核心重构：双态智能保活 + 断连看门狗
    private val pingRunnable = object : Runnable {
        override fun run() {
            val netInfo = DeviceUtil.getNetworkInfo(this@LocationService).first

            // 🚀 动态心跳策略：WiFi 极其稳健，心跳 3 分钟 (180s) 极致省电；移动数据 NAT 易老化，心跳 45 秒 (45s) 防断开
            val pingInterval = if (netInfo == "WiFi") 180000L else 45000L

            if (WebSocketManager.isConnected) {
                try {
                    WebSocketManager.sendRawJson(JSONObject().apply { put("action", "ping") }.toString())
                } catch (e: Exception) {}
            } else {
                // 🚀 断连看门狗：由于没有厂商推送，如果 WebSocket 意外断开，心跳机制会作为“兜底唤醒”强行把连接拉起来
                if (netInfo != "无网络") {
                    val uid = UserPrefs.getUserId(this@LocationService)
                    if (uid > 0) {
                        WebSocketManager.connect(this@LocationService, uid)
                    }
                }
            }
            // 使用动态算出的最佳时间继续下一次心跳
            syncHandler.postDelayed(this, pingInterval)
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetType: String = "UNINITIALIZED"
    private var lastWifiName: String? = null

    private var wifiDebounceRunnable: Runnable? = null

    private val networkCheckRunnable = Runnable {
        val (netType, currentWifiName) = DeviceUtil.getNetworkInfo(this@LocationService)
        val newWifi = if (netType == "WiFi") currentWifiName else null

        if (netType != lastNetType || newWifi != lastWifiName) {
            lastNetType = netType
            lastWifiName = newWifi

            if (netType == "无网络") {
                val notifMsg = UserPrefs.getNotifOffline(this@LocationService).ifEmpty { "无网络连接，已暂停后台同步" }
                updateNotification(notifMsg)

                sensorManager?.unregisterListener(sensorListener)
                syncHandler.removeCallbacks(dynamicDiffSyncRunnable)
                syncHandler.removeCallbacks(periodicLocationRunnable)
                syncHandler.removeCallbacks(pingRunnable)

                WebSocketManager.disconnect()
                return@Runnable
            }

            gpsCooldownEndTime = 0L
            val uid = UserPrefs.getUserId(this@LocationService)
            if (uid > 0 && !WebSocketManager.isConnected) {
                WebSocketManager.connect(this@LocationService, uid)
            }

            StrategyManager.reset()
            acquireTempWakeLock()

            if (isScreenOn) {
                sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
                syncHandler.removeCallbacks(dynamicDiffSyncRunnable)
                syncHandler.postDelayed(dynamicDiffSyncRunnable, 1000L)
                syncHandler.removeCallbacks(periodicLocationRunnable)
                syncHandler.postDelayed(periodicLocationRunnable, 300000L)
                syncHandler.removeCallbacks(pingRunnable)
                syncHandler.post(pingRunnable)
            }

            val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
            if (state == 0) {
                val statusMsg = when (netType) {
                    "WiFi" -> "刚连上 $newWifi"
                    "移动数据" -> "已切换至移动数据"
                    else -> "网络状态刷新"
                }
                showTemporaryStatus(statusMsg, 4000L)

                wifiDebounceRunnable?.let { syncHandler.removeCallbacks(it) }
                wifiDebounceRunnable = Runnable {
                    triggerSingleLocation(isEmergency = false, forceHttp = true)
                }
                syncHandler.postDelayed(wifiDebounceRunnable!!, 3000L)
            } else {
                uploadData(lastLat, lastLng, lastAddr, "息屏睡眠 💤")
            }

            val defaultNorm = UserPrefs.getNotifNormal(this@LocationService).ifEmpty { "守护中：网络正常" }
            updateNotification("$defaultNorm ($netType)")
        }
    }

    private fun checkLowBatteryAndSync() {
        val battery = getCurrentBattery()
        if (battery <= 10 && !hasSentLowBatteryWarning) {
            hasSentLowBatteryWarning = true
            triggerSingleLocation(isEmergency = false, forceHttp = true)
            val partnerId = UserPrefs.getPartnerId(this)
            if (partnerId > 0) {
                val msgObj = JSONObject().apply {
                    put("msg", "TA的手机电量仅剩 $battery%，即将失联！")
                }
                WebSocketManager.sendMessage("send_to_partner", partnerId, "low_battery_alert", msgObj)
            }
            showTemporaryStatus("⚠️电量濒危($battery%)")
        } else if (battery > 15) {
            hasSentLowBatteryWarning = false
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
            var state = prefs.getInt("dev_sleep_state", 0)

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (state == 2) {
                        prefs.edit().putInt("dev_sleep_state", 0).apply()
                        state = 0
                    }

                    StrategyManager.reset()
                    isScreenOn = false
                    gpsCooldownEndTime = 0L

                    sensorManager?.unregisterListener(sensorListener)
                    syncHandler.removeCallbacks(dynamicDiffSyncRunnable)
                    syncHandler.removeCallbacks(periodicLocationRunnable)

                    acquireTempWakeLock()

                    val timeSinceLastLoc = System.currentTimeMillis() - lastLocationTime
                    if (timeSinceLastLoc > 5 * 60 * 1000L && state == 0) {
                        triggerSingleLocation(isEmergency = false, forceHttp = true)
                    } else {
                        uploadData(lastLat, lastLng, lastAddr, if (state != 0) "息屏睡眠 💤" else null)
                    }

                    val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
                    WorkManager.getInstance(this@LocationService).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, workRequest)
                }

                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    WorkManager.getInstance(this@LocationService).cancelUniqueWork(WORK_NAME)
                    gpsCooldownEndTime = 0L
                    acquireTempWakeLock()

                    val currentState = prefs.getInt("dev_sleep_state", 0)

                    if (currentState == 0) {
                        releasePendingCommands()
                    }

                    sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

                    if (WebSocketManager.isConnected) {
                        val uid = UserPrefs.getUserId(this@LocationService)
                        if (uid > 0) {
                            val pullObj = JSONObject().apply {
                                put("action", "pull_offline_messages")
                                put("user_id", uid)
                            }
                            WebSocketManager.sendRawJson(pullObj.toString())
                        }
                    }

                    val timeSinceLastLoc = System.currentTimeMillis() - lastLocationTime
                    if (timeSinceLastLoc > 5 * 60 * 1000L && currentState == 0) {
                        triggerSingleLocation(isEmergency = false, forceHttp = true)
                    } else {
                        uploadData(lastLat, lastLng, lastAddr, if (currentState != 0) "息屏睡眠 💤" else null)
                    }

                    syncHandler.removeCallbacks(dynamicDiffSyncRunnable)
                    syncHandler.postDelayed(dynamicDiffSyncRunnable, 1000L)
                    syncHandler.removeCallbacks(periodicLocationRunnable)
                    syncHandler.postDelayed(periodicLocationRunnable, 300000L)
                }
            }
        }
    }

    private fun initNetworkObserver() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                syncHandler.removeCallbacks(networkCheckRunnable)
                syncHandler.postDelayed(networkCheckRunnable, 1500L)
            }
            override fun onLost(network: Network) {
                syncHandler.removeCallbacks(networkCheckRunnable)
                syncHandler.postDelayed(networkCheckRunnable, 1500L)
            }
        }
        connectivityManager?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback!!)
        syncHandler.post(networkCheckRunnable)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isScreenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("守护引擎初始化中..."))

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)

        initAMapLocation()
        initNetworkObserver()

        WebSocketManager.addListener(wsListener)
        val uid = UserPrefs.getUserId(this)
        if (uid > 0) {
            WebSocketManager.connect(this, uid)
        }

        if (isScreenOn) {
            sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
            syncHandler.post(dynamicDiffSyncRunnable)
            syncHandler.post(periodicLocationRunnable)
        }
        syncHandler.post(pingRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_UPDATE_NOTIF") {
            val netInfo = DeviceUtil.getNetworkInfo(this).first
            if (netInfo == "无网络") {
                val notifMsg = UserPrefs.getNotifOffline(this).ifEmpty { "无网络连接，已暂停后台同步" }
                updateNotification(notifMsg)
            } else {
                val notifMsg = UserPrefs.getNotifNormal(this).ifEmpty { "守护中：网络正常" }
                updateNotification("$notifMsg ($netInfo)")
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_WORKER_SYNC") {
            if (DeviceUtil.getNetworkInfo(this).first == "无网络") return START_STICKY

            acquireTempWakeLock()
            val uid = UserPrefs.getUserId(this)
            if (uid > 0 && !WebSocketManager.isConnected) {
                WebSocketManager.connect(this, uid)
            }

            StrategyManager.checkAndExecuteRemoteCommand(this) { commandTime ->
                val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
                val lastCmdTime = prefs.getLong("last_cmd_time", 0L)

                if (commandTime > lastCmdTime) {
                    prefs.edit().putLong("last_cmd_time", commandTime).apply()
                    pendingClearCommandTime = commandTime

                    Thread {
                        try {
                            val url = UserPrefs.getServerUrl(this@LocationService) + "/api.php"
                            val formBody = okhttp3.FormBody.Builder()
                                .add("action", "clear_command")
                                .add("user_id", uid.toString())
                                .build()
                            val request = okhttp3.Request.Builder().url(url).post(formBody).build()
                            okhttp3.OkHttpClient().newCall(request).execute().use {}
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()

                    if (prefs.getInt("dev_sleep_state", 0) != 0) {
                        uploadData(lastLat, lastLng, lastAddr, "息屏睡眠 💤")
                    } else {
                        uploadData(lastLat, lastLng, lastAddr, null)
                    }
                }
            }

            val currentState = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
            if (currentState == 0) {
                val timeSinceLastLoc = System.currentTimeMillis() - lastLocationTime
                if (timeSinceLastLoc > 5 * 60 * 1000L) {
                    triggerSingleLocation(isEmergency = false, forceHttp = true)
                } else {
                    uploadData(lastLat, lastLng, lastAddr, null)
                }
            } else {
                uploadData(lastLat, lastLng, lastAddr, "息屏睡眠 💤")
            }
        }
        return START_STICKY
    }

    private fun initAMapLocation() {
        try {
            mLocationClient = AMapLocationClient(applicationContext)
            mLocationClient?.setLocationListener { location ->
                if (location != null) {
                    if (location.errorCode == 0) {
                        isFallbackToGps = false
                        isGpsRetry = false
                        consecutiveGpsFailures = 0

                        CoroutineScope(Dispatchers.IO).launch {
                            val db = AppDatabase.getDatabase(applicationContext)
                            val entity = LocationEntity(
                                latitude = location.latitude, longitude = location.longitude, address = location.address ?: "未知地址",
                                timestamp = System.currentTimeMillis(), locationType = location.locationType, accuracy = location.accuracy
                            )
                            db.locationDao().insertLog(entity)
                            db.locationDao().trimDatabase()
                        }
                        if (location.locationType == 1 || location.locationType == 5 || location.locationType == 6) {
                            lastLat = location.latitude
                            lastLng = location.longitude
                            lastAddr = location.address ?: ""
                            lastLocationTime = System.currentTimeMillis()
                        }

                        val currentState = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
                        if (currentState != 0) {
                            uploadData(lastLat, lastLng, lastAddr, "息屏睡眠 💤")
                        } else {
                            if (pendingHttpSyncOnLocation) {
                                pendingHttpSyncOnLocation = false
                                uploadData(lastLat, lastLng, lastAddr, null)
                            }
                        }
                        lastSyncApp = "FORCE_RECOVER_STATE"
                    } else {
                        if (System.currentTimeMillis() < gpsCooldownEndTime) {
                            if (pendingHttpSyncOnLocation) {
                                pendingHttpSyncOnLocation = false
                                uploadData(lastLat, lastLng, lastAddr, null)
                            }
                        } else {
                            if (!isFallbackToGps && !isGpsRetry) {
                                isFallbackToGps = true
                                syncHandler.postDelayed(locationRunnable, 500)
                            } else if (isFallbackToGps && !isGpsRetry) {
                                isGpsRetry = true
                                syncHandler.postDelayed(locationRunnable, 3000)
                            } else {
                                consecutiveGpsFailures = 2
                                gpsCooldownEndTime = System.currentTimeMillis() + 30 * 60 * 1000L
                                isFallbackToGps = false
                                isGpsRetry = false
                                val errorMsg = UserPrefs.getNotifError(this@LocationService).ifEmpty { "⚠️ 信号盲区/波动" }
                                uploadData(lastLat, lastLng, lastAddr, errorMsg)
                                if (pendingHttpSyncOnLocation) pendingHttpSyncOnLocation = false
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val locationRunnable = Runnable {
        val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state != 0) {
            isEmergencyLocation = false
            isGpsRetry = false
            return@Runnable
        }

        mLocationClient?.let { client ->
            val option = AMapLocationClientOption().apply {
                isNeedAddress = true
                isOnceLocation = true
                isLocationCacheEnable = false
                isSensorEnable = true

                if (System.currentTimeMillis() < gpsCooldownEndTime) {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                    isGpsFirst = false
                    httpTimeOut = 8000
                } else {
                    if (isGpsRetry || isFallbackToGps || isEmergencyLocation) {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                        isGpsFirst = true
                        isOnceLocationLatest = true
                        httpTimeOut = 15000
                    } else {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                        isGpsFirst = false
                        httpTimeOut = 8000
                    }
                }
            }
            client.stopLocation()
            client.setLocationOption(option)
            client.startLocation()
        }
        isEmergencyLocation = false
    }

    private fun triggerSingleLocation(isEmergency: Boolean = false, forceHttp: Boolean = false) {
        val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state != 0) return

        this.isEmergencyLocation = isEmergency
        this.pendingHttpSyncOnLocation = forceHttp
        this.isFallbackToGps = false
        this.isGpsRetry = false
        syncHandler.removeCallbacks(locationRunnable)
        mLocationClient?.stopLocation()
        syncHandler.postDelayed(locationRunnable, 500)
    }

    private fun uploadDataViaWebSocket() {
        val uid = UserPrefs.getUserId(this)
        if (uid <= 0 || !WebSocketManager.isConnected) return
        try {
            val payload = JSONObject().apply {
                put("action", "sync_state")
                put("user_id", uid)
                put("device_id", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                put("fg_app", DeviceUtil.getForegroundApp(this@LocationService))
                put("battery", getCurrentBattery())
                put("mic_busy", DeviceUtil.getMicBusyStatus(this@LocationService))
                put("net_type", DeviceUtil.getNetworkInfo(this@LocationService).first)
                put("wifi_name", DeviceUtil.getNetworkInfo(this@LocationService).second)
                put("lat", lastLat ?: 0.0)
                put("lng", lastLng ?: 0.0)
                put("address", lastAddr ?: "")
                put("steps", DeviceUtil.getStepCount(this@LocationService))
                put("top_apps_json", "[]")
                if (pendingClearCommandTime > 0L) put("clear_command_time", pendingClearCommandTime)
            }
            WebSocketManager.sendRawJson(payload.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun uploadData(lat: Double?, lng: Double?, addr: String?, fgAppOverride: String? = null) {
        val netInfo = DeviceUtil.getNetworkInfo(this).first
        if (netInfo == "无网络") return
        val uid = UserPrefs.getUserId(this)
        if (uid == -1 || UserPrefs.getPartnerId(this) <= 0) return

        val finalFgApp = fgAppOverride ?: DeviceUtil.getForegroundApp(this)

        if (WebSocketManager.isConnected) {
            try {
                val payload = JSONObject().apply {
                    put("action", "sync_state")
                    put("user_id", uid)
                    put("device_id", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID))
                    put("fg_app", finalFgApp)
                    put("battery", getCurrentBattery())
                    put("mic_busy", DeviceUtil.getMicBusyStatus(this@LocationService))
                    put("net_type", DeviceUtil.getNetworkInfo(this@LocationService).first)
                    put("wifi_name", DeviceUtil.getNetworkInfo(this@LocationService).second)
                    put("lat", lat ?: 0.0)
                    put("lng", lng ?: 0.0)
                    put("address", addr ?: "")
                    put("steps", DeviceUtil.getStepCount(this@LocationService))
                    put("top_apps_json", "[]")
                    if (pendingClearCommandTime > 0L) put("clear_command_time", pendingClearCommandTime)
                }
                WebSocketManager.sendRawJson(payload.toString())
            } catch (e: Exception) {}
        }

        val clearTime = if (pendingClearCommandTime > 0L) pendingClearCommandTime else null

        NetworkClient.getApi(this).syncAll(
            action = "sync_all", userId = uid, deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
            lat = lat, lng = lng, address = addr, battery = getCurrentBattery(), netType = DeviceUtil.getNetworkInfo(this).first,
            wifiName = DeviceUtil.getNetworkInfo(this).second, fgApp = finalFgApp, micBusy = DeviceUtil.getMicBusyStatus(this),
            steps = DeviceUtil.getStepCount(this), topApps = "[]", clearCommandTime = clearTime
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                val body = response.body()
                if (body?.status == "error_kicked") {
                    handleKickedOffline(body.message ?: "账号已在别处登录")
                } else if (body?.status == "success") {
                    body.server_config?.let { UserPrefs.saveServerConfigData(this@LocationService, it) }
                    body.partner_data?.let { pd ->
                        val pBattery = pd.device?.battery ?: 0
                        val pApp = pd.device?.foreground_app ?: ""
                        val pAddr = pd.location?.address ?: ""
                        UserPrefs.saveWidgetData(this@LocationService, pBattery, pApp, pAddr)
                        CoupleWidgetProvider.updateAllWidgets(this@LocationService)
                    }
                    pendingClearCommandTime = 0L
                }
            }
            override fun onFailure(call: Call<UserResponse>, t: Throwable) {}
        })
    }

    private fun handleKickedOffline(reason: String) {
        UserPrefs.clear(this)
        stopSelf()
        startActivity(Intent(this, LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(applicationContext, "⚠️ 强制下线: $reason", Toast.LENGTH_LONG).show()
        }, 500)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        WebSocketManager.removeListener(wsListener)
        WebSocketManager.disconnect()
        sensorManager?.unregisterListener(sensorListener)
        syncHandler.removeCallbacksAndMessages(null)
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
        unregisterReceiver(screenStateReceiver)

        connectivityManager?.let { networkCallback?.let { cb -> it.unregisterNetworkCallback(cb) } }
        mLocationClient?.stopLocation()
        mLocationClient?.onDestroy()

        if (tempWakeLock?.isHeld == true) tempWakeLock?.release()
        super.onDestroy()
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val titleStr = UserPrefs.getNotifTitle(this).ifEmpty { "情侣手记实时守护中" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleStr)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "位置实时共享", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL_ID, "新消息提醒", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private suspend fun saveIncomingMessageToDb(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val msgId = json.optString("msg_id", UUID.randomUUID().toString())
            val senderId = json.optInt("sender_id", UserPrefs.getPartnerId(this@LocationService))
            val msgType = json.optString("msg_type", "text")
            val content = json.optString("content", "")
            val isBurn = json.optInt("is_burn", 0) == 1
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val replyTo = if (json.has("reply_to")) json.getString("reply_to") else null
            val originalName = json.optString("original_name", "")

            val entity = ChatEntity(
                msgId = msgId,
                senderId = senderId,
                receiverId = UserPrefs.getUserId(this@LocationService),
                msgType = msgType,
                content = content,
                originalName = originalName,
                isBurn = isBurn,
                timestamp = timestamp,
                isRead = false,
                replyToMsgId = replyTo,
                status = 2
            )
            AppDatabase.getDatabase(this@LocationService).chatDao().insertMessage(entity)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}