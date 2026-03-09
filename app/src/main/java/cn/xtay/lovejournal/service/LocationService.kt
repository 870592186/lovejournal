package cn.xtay.lovejournal.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val intent = Intent(applicationContext, LocationService::class.java).apply { action = "ACTION_WORKER_SYNC" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            try { applicationContext.startService(intent) } catch (ex: Exception) { ex.printStackTrace() }
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
    private val NOTIFICATION_ID = 2026

    private var tempWakeLock: PowerManager.WakeLock? = null
    private var isEmergencyLocation = false
    private var isFallbackToGps = false

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastAddr: String? = null

    private var lastUploadedFgApp: String = ""
    private var lastUploadedBattery: Int = -1

    private var pendingClearCommandTime: Long = 0L
    private var hasSentLowBatteryWarning = false
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isScreenOn = true

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

    private val wsListener = object : WebSocketManager.MessageListener {
        override fun onCommandReceived(command: String, data: String) {
            val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)

            when (command) {
                "partner_status_update" -> {
                    try {
                        val json = JSONObject(data)
                        val payload = if (json.has("device")) json else json.optJSONObject("data")

                        if (payload != null) {
                            val deviceData = payload.optJSONObject("device")
                            val locationData = payload.optJSONObject("location")

                            val pBat = deviceData?.optInt("battery") ?: 0
                            val pApp = deviceData?.optString("foreground_app") ?: ""
                            val pAddr = locationData?.optString("address") ?: ""

                            UserPrefs.saveWidgetData(this@LocationService, pBat, pApp, pAddr)
                            CoupleWidgetProvider.updateAllWidgets(this@LocationService)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                "fly_heart" -> {
                    if (isScreenOn && state == 0) {
                        cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(this@LocationService)
                        uploadDataViaEvents(lastLat, lastLng, lastAddr, forceHttp = true)
                    } else {
                        getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).edit().putBoolean("pending_heart", true).apply()
                        playHeartbeatVibration()
                        if (state == 0) uploadDataViaEvents(lastLat, lastLng, lastAddr, forceHttp = true)
                    }
                }
                "low_battery_alert" -> {
                    val msg = if (data.contains("msg")) JSONObject(data).optString("msg") else "TA的手机电量严重不足！"
                    Handler(Looper.getMainLooper()).post { Toast.makeText(this@LocationService, "🚨 紧急通知：$msg", Toast.LENGTH_LONG).show() }
                }
                "sync_period" -> {
                    val intent = Intent("cn.xtay.lovejournal.WS_COMMAND").apply { setPackage(packageName); putExtra("command", "sync_period") }
                    sendBroadcast(intent)
                }
                "force_location" -> {
                    if (state == 0) {
                        acquireTempWakeLock(15000L)
                        triggerSingleLocation(isEmergency = true)
                    }
                }
                else -> {
                    try {
                        if (state == 0) {
                            cn.xtay.lovejournal.util.RemoteCommandExecutor.execute(this@LocationService, command)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }

    private fun acquireTempWakeLock(timeoutMs: Long = 30000L) {
        if (tempWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoveJournal:TempWakeLock")
            tempWakeLock?.setReferenceCounted(false)
        }
        tempWakeLock?.acquire(timeoutMs)
    }

    private val localEventMonitorRunnable = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
            val state = prefs.getInt("dev_sleep_state", 0)

            if (state == 1 || state == 2) {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (hour in 8..12) {
                    prefs.edit().putInt("dev_sleep_state", 0).apply()
                } else {
                    syncHandler.postDelayed(this, 5000L)
                    return
                }
            }

            if (isScreenOn) {
                val currentApp = DeviceUtil.getForegroundApp(this@LocationService)
                val currentBattery = DeviceUtil.getBatteryLevel(this@LocationService)

                if (currentApp != lastUploadedFgApp || currentBattery != lastUploadedBattery) {
                    uploadDataViaEvents(lastLat, lastLng, lastAddr, false)
                }

                if (currentBattery <= 10 && !hasSentLowBatteryWarning) {
                    hasSentLowBatteryWarning = true
                    val partnerId = UserPrefs.getPartnerId(this@LocationService)
                    if (partnerId > 0) {
                        val payload = JSONObject().apply { put("msg", "TA的手机电量仅剩 $currentBattery%，即将失联！") }
                        WebSocketManager.sendMessage("send_to_partner", partnerId, "low_battery_alert", payload)
                    }
                } else if (currentBattery > 15) {
                    hasSentLowBatteryWarning = false
                }
            }
            syncHandler.postDelayed(this, 3000L)
        }
    }

    private val locationSyncRunnable = object : Runnable {
        override fun run() {
            val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
            if (state == 0) {
                val netInfo = DeviceUtil.getNetworkInfo(this@LocationService)
                if (netInfo.first != "无网络") {
                    if (netInfo.first != "WiFi") {
                        triggerSingleLocation(false)
                    }
                }
            }
            syncHandler.postDelayed(this, 300000L)
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
                    syncHandler.removeCallbacksAndMessages(null)
                    acquireTempWakeLock(60000L)

                    if (state == 0) {
                        uploadDataViaEvents(lastLat, lastLng, lastAddr, false)
                        val netInfo = DeviceUtil.getNetworkInfo(this@LocationService)
                        if (netInfo.first != "WiFi") {
                            triggerSingleLocation(true)
                        }
                    }

                    val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
                    WorkManager.getInstance(this@LocationService).enqueueUniquePeriodicWork(
                        WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, workRequest
                    )
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    WorkManager.getInstance(this@LocationService).cancelUniqueWork(WORK_NAME)
                    acquireTempWakeLock()

                    if (state == 0 && prefs.getBoolean("pending_heart", false)) {
                        prefs.edit().putBoolean("pending_heart", false).apply()
                        Handler(Looper.getMainLooper()).postDelayed({
                            cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(this@LocationService)
                        }, 1500)
                    }

                    if (state == 0) {
                        uploadDataViaEvents(lastLat, lastLng, lastAddr, false)
                        val netInfo = DeviceUtil.getNetworkInfo(this@LocationService)
                        if (netInfo.first != "WiFi") {
                            triggerSingleLocation(false)
                        }
                    }

                    syncHandler.postDelayed(localEventMonitorRunnable, 3000L)
                    syncHandler.postDelayed(locationSyncRunnable, 300000L)
                }
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetType: String = "UNINITIALIZED"
    private var lastWifiName: String? = null

    private val networkCheckRunnable = Runnable {
        val (netType, currentWifiName) = DeviceUtil.getNetworkInfo(this@LocationService)
        val newWifi = if (netType == "WiFi") currentWifiName else null

        if (netType != lastNetType || newWifi != lastWifiName) {
            lastNetType = netType
            lastWifiName = newWifi

            if (netType == "无网络") {
                updateNotification(UserPrefs.getNotifOffline(this@LocationService).ifEmpty { "无网络连接，已暂停后台同步" })
                WebSocketManager.disconnect()
                return@Runnable
            }

            StrategyManager.reset()
            acquireTempWakeLock(60000L)

            val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
            if (state == 0) {
                // 💡 核心优化：网络刚连上时，底层 IP 分配和 DNS 解析通常需要 1~2 秒。
                // 我们强制挂起 3 秒，等网络绝对稳定后，再去建连 WebSocket 并强制拉起一次定锚定位！
                syncHandler.postDelayed({
                    val uid = UserPrefs.getUserId(this@LocationService)
                    if (uid > 0 && !WebSocketManager.isConnected) {
                        WebSocketManager.connect(this@LocationService, uid)
                    }
                    // 强制拉起一次定位。定位成功后，系统会自动调用 uploadDataViaEvents 上报新锚点和网络状态。
                    triggerSingleLocation(true)
                }, 3000L)
            }

            updateNotification("${UserPrefs.getNotifNormal(this@LocationService).ifEmpty { "守护中：网络正常" }} (${netType})")
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

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("守护引擎初始化中..."))

        registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })

        initAMapLocation()
        initNetworkObserver()

        val uid = UserPrefs.getUserId(this)
        if (uid > 0) WebSocketManager.connect(this, uid)
        WebSocketManager.addListener(wsListener)

        if (isScreenOn) {
            syncHandler.post(localEventMonitorRunnable)
            syncHandler.post(locationSyncRunnable)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_UPDATE_NOTIF") {
            val netInfo = DeviceUtil.getNetworkInfo(this).first
            if (netInfo == "无网络") updateNotification(UserPrefs.getNotifOffline(this).ifEmpty { "无网络连接，已暂停后台同步" })
            else updateNotification("${UserPrefs.getNotifNormal(this).ifEmpty { "守护中：网络正常" }} ($netInfo)")
            return START_STICKY
        }

        if (intent?.action == "ACTION_WORKER_SYNC") {
            if (DeviceUtil.getNetworkInfo(this).first == "无网络") return START_STICKY
            acquireTempWakeLock(60000L)

            val uid = UserPrefs.getUserId(this)
            if (uid > 0 && !WebSocketManager.isConnected) WebSocketManager.connect(this, uid)
            StrategyManager.checkAndExecuteRemoteCommand(this) { commandTime -> pendingClearCommandTime = commandTime }

            val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
            if (state == 0) {
                val netInfo = DeviceUtil.getNetworkInfo(this)
                if (netInfo.first == "WiFi") {
                    uploadDataViaEvents(lastLat, lastLng, lastAddr, forceHttp = false)
                } else {
                    triggerSingleLocation(false)
                }
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
                        val dbLog = LocationEntity(
                            latitude = location.latitude, longitude = location.longitude,
                            address = location.address ?: "未知地址", timestamp = System.currentTimeMillis(),
                            locationType = location.locationType, accuracy = location.accuracy
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            val db = AppDatabase.getDatabase(applicationContext)
                            db.locationDao().insertLog(dbLog)
                            db.locationDao().trimDatabase()
                        }
                        if (location.locationType == 1 || location.locationType == 5 || location.locationType == 6) {
                            lastLat = location.latitude
                            lastLng = location.longitude
                            lastAddr = location.address ?: ""
                        }

                        val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
                        if (state == 0) {
                            // 定位成功后，顺带把设备最新状态一起推给服务器（形成定锚同步）
                            uploadDataViaEvents(lastLat, lastLng, lastAddr, forceHttp = false)
                        }
                    } else {
                        if (!isFallbackToGps) {
                            isFallbackToGps = true
                            syncHandler.post(locationRunnable)
                            return@setLocationListener
                        }
                        isFallbackToGps = false
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private val locationRunnable = Runnable {
        mLocationClient?.let { client ->
            val option = AMapLocationClientOption().apply {
                isNeedAddress = true
                isOnceLocation = true
                isLocationCacheEnable = false
                isSensorEnable = true

                if (isFallbackToGps || isEmergencyLocation) {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isGpsFirst = true
                    isOnceLocationLatest = true
                    httpTimeOut = 12000
                } else {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
                    isGpsFirst = false
                    isOnceLocationLatest = false
                    httpTimeOut = 8000
                }
            }
            client.setLocationOption(option)
            client.startLocation()
        }
        isEmergencyLocation = false
    }

    private fun triggerSingleLocation(isEmergency: Boolean = false) {
        this.isEmergencyLocation = isEmergency
        syncHandler.removeCallbacks(locationRunnable)
        syncHandler.postDelayed(locationRunnable, 1500)
    }

    private fun uploadDataViaEvents(lat: Double?, lng: Double?, addr: String?, forceHttp: Boolean) {
        if (DeviceUtil.getNetworkInfo(this).first == "无网络") return

        val state = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state != 0) return

        val uid = UserPrefs.getUserId(this)
        if (uid == -1 || UserPrefs.getPartnerId(this) <= 0) return

        val currentApp = DeviceUtil.getForegroundApp(this)
        val currentBattery = DeviceUtil.getBatteryLevel(this)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val netType = DeviceUtil.getNetworkInfo(this).first
        val wifiName = DeviceUtil.getNetworkInfo(this).second
        val micBusy = DeviceUtil.getMicBusyStatus(this)
        val steps = DeviceUtil.getStepCount(this)

        lastUploadedFgApp = currentApp
        lastUploadedBattery = currentBattery

        if (!forceHttp && WebSocketManager.isConnected) {
            try {
                val payload = JSONObject().apply {
                    put("action", "sync_state")
                    put("user_id", uid)
                    put("device_id", deviceId)
                    if (lat != null) put("lat", lat)
                    if (lng != null) put("lng", lng)
                    put("address", addr ?: "")
                    put("battery", currentBattery)
                    put("net_type", netType)
                    put("wifi_name", wifiName)
                    put("fg_app", currentApp)
                    put("mic_busy", micBusy)
                    put("steps", steps)
                    put("top_apps_json", "[]")
                }

                WebSocketManager.sendRawJson(payload.toString())
                return
            } catch (e: Exception) { e.printStackTrace() }
        }

        NetworkClient.getApi(this).syncAll(
            action = "sync_all", userId = uid, deviceId = deviceId,
            lat = lat, lng = lng, address = addr, battery = currentBattery,
            netType = netType, wifiName = wifiName, fgApp = currentApp,
            micBusy = micBusy, steps = steps, topApps = "[]",
            clearCommandTime = if (pendingClearCommandTime > 0L) pendingClearCommandTime else null
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                val body = response.body()
                if (body?.status == "error_kicked") {
                    handleKickedOffline(body.message ?: "账号已在别处登录")
                } else if (body?.status == "success") {
                    body.server_config?.let { UserPrefs.saveServerConfigData(this@LocationService, it) }
                    body.partner_data?.let { pd ->
                        UserPrefs.saveWidgetData(
                            this@LocationService, pd.device?.battery ?: 0,
                            pd.device?.foreground_app ?: "", pd.location?.address ?: ""
                        )
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
        Handler(Looper.getMainLooper()).postDelayed({ Toast.makeText(applicationContext, "⚠️ 强制下线: $reason", Toast.LENGTH_LONG).show() }, 500)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        WebSocketManager.removeListener(wsListener)
        WebSocketManager.disconnect()
        syncHandler.removeCallbacksAndMessages(null)
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
        unregisterReceiver(screenStateReceiver)

        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        mLocationClient?.stopLocation()
        mLocationClient?.onDestroy()
        if (tempWakeLock?.isHeld == true) tempWakeLock?.release()
        super.onDestroy()
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(UserPrefs.getNotifTitle(this).ifEmpty { "情侣手记实时守护中" })
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "位置实时共享", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}