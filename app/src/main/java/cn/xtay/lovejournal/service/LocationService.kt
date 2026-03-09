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
import java.util.concurrent.TimeUnit

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
            e.printStackTrace()
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

    private var pendingClearCommandTime: Long = 0L
    private var hasSentLowBatteryWarning = false
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isScreenOn = true

    // 💖 核心升级：极简的内存监听器，加入“息屏暂存”逻辑！
    // 💖 核心升级：极简的内存监听器，加入“万能指令拦截”逻辑！
    private val wsListener = object : WebSocketManager.MessageListener {
        override fun onCommandReceived(command: String, data: String) {
            if (command == "fly_heart") {
                if (isScreenOn) {
                    // 1. 如果屏幕亮着，0 毫秒延迟立刻弹爱心！
                    cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(this@LocationService)
                    uploadData(lastLat, lastLng, lastAddr, "✅ 实时响应浪漫魔法")
                } else {
                    // 2. 💖 如果息屏了，偷偷存进缓存，等亮屏的时候再炸出来！
                    val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("pending_heart", true).apply()
                }
            } else if (command == "low_battery_alert") {
                val msg = if (data.contains("msg")) JSONObject(data).optString("msg") else "TA的手机电量严重不足！"
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@LocationService, "🚨 紧急通知：$msg", Toast.LENGTH_LONG).show()
                }
            } else if (command == "sync_period") {
                // 姨妈助手属于前台页面内的刷新，用指定包名的广播通知它是最安全的
                val intent = Intent("cn.xtay.lovejournal.WS_COMMAND")
                intent.setPackage(packageName)
                intent.putExtra("command", "sync_period")
                sendBroadcast(intent)
            } else {
                // 🚀 终极杀招：把其他所有你自定义的指令（锁屏、响铃等），全部交给原生的万能执行器！
                try {
                    cn.xtay.lovejournal.util.RemoteCommandExecutor.execute(this@LocationService, command)
                    // 执行完顺便上报一下状态
                    uploadData(lastLat, lastLng, lastAddr, "✅ 实时响应指令: $command")
                } catch (e: Exception) {
                    e.printStackTrace()
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

    private val deviceSyncRunnable = object : Runnable {
        override fun run() {
            if (isScreenOn) {
                checkLowBatteryAndSync()
                if (DeviceUtil.getNetworkInfo(this@LocationService).first != "无网络") {
                    uploadData(lastLat, lastLng, lastAddr, null)
                }
            }
            syncHandler.postDelayed(this, 60000L)
        }
    }

    private val locationSyncRunnable = object : Runnable {
        override fun run() {
            if (DeviceUtil.getNetworkInfo(this@LocationService).first != "无网络") {
                triggerSingleLocation(false)
            }
            syncHandler.postDelayed(this, 300000L)
        }
    }

    private fun checkLowBatteryAndSync() {
        val battery = DeviceUtil.getBatteryLevel(this)
        if (battery <= 10 && !hasSentLowBatteryWarning) {
            hasSentLowBatteryWarning = true
            triggerSingleLocation(true)
            val partnerId = UserPrefs.getPartnerId(this)
            if (partnerId > 0) {
                val data = JSONObject().apply { put("msg", "TA的手机电量仅剩 $battery%，即将失联！") }
                WebSocketManager.sendMessage("send_to_partner", partnerId, "low_battery_alert", data)
            }
            uploadData(lastLat, lastLng, lastAddr, "⚠️电量濒危($battery%)")
        } else if (battery > 15) {
            hasSentLowBatteryWarning = false
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    StrategyManager.reset()
                    isScreenOn = false
                    syncHandler.removeCallbacksAndMessages(null)
                    acquireTempWakeLock(60000L)
                    triggerSingleLocation(true)

                    val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
                    WorkManager.getInstance(this@LocationService).enqueueUniquePeriodicWork(
                        WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, workRequest
                    )
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    WorkManager.getInstance(this@LocationService).cancelUniqueWork(WORK_NAME)
                    acquireTempWakeLock()

                    // 💖 核心新增：亮屏瞬间检查潜伏的爱心！
                    val prefs = getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
                    if (prefs.getBoolean("pending_heart", false)) {
                        // 擦除标记，防止无限循环弹
                        prefs.edit().putBoolean("pending_heart", false).apply()

                        // 延迟 1.5 秒播放（让用户有足够的时间看清屏幕解锁后的界面，然后突然被爱心包围！）
                        Handler(Looper.getMainLooper()).postDelayed({
                            cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(this@LocationService)
                            uploadData(lastLat, lastLng, lastAddr, "✅ 亮屏补发错过的魔法")
                        }, 1500)
                    }

                    checkLowBatteryAndSync()
                    triggerSingleLocation(false)
                    syncHandler.postDelayed(deviceSyncRunnable, 60000L)
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

            val uid = UserPrefs.getUserId(this@LocationService)
            if (uid > 0 && !WebSocketManager.isConnected) {
                WebSocketManager.connect(this@LocationService, uid)
            }

            StrategyManager.reset()
            acquireTempWakeLock(60000L)

            val statusMsg = when (netType) {
                "WiFi" -> "刚连上 $newWifi"
                "移动数据" -> "已切换至移动数据"
                else -> "网络状态刷新"
            }
            uploadData(lastLat, lastLng, lastAddr, statusMsg)
            triggerSingleLocation(true)
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

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = pm.isInteractive

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("守护引擎初始化中..."))

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenStateReceiver, filter)

        initAMapLocation()
        initNetworkObserver()

        val uid = UserPrefs.getUserId(this)
        if (uid > 0) WebSocketManager.connect(this, uid)

        // 🚀 挂载极速内存监听器
        WebSocketManager.addListener(wsListener)

        if (isScreenOn) {
            syncHandler.post(deviceSyncRunnable)
            syncHandler.post(locationSyncRunnable)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_UPDATE_NOTIF") {
            val netInfo = DeviceUtil.getNetworkInfo(this).first
            if (netInfo == "无网络") {
                updateNotification(UserPrefs.getNotifOffline(this).ifEmpty { "无网络连接，已暂停后台同步" })
            } else {
                updateNotification("${UserPrefs.getNotifNormal(this).ifEmpty { "守护中：网络正常" }} ($netInfo)")
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_WORKER_SYNC") {
            if (DeviceUtil.getNetworkInfo(this).first == "无网络") return START_STICKY
            acquireTempWakeLock(60000L)

            val uid = UserPrefs.getUserId(this)
            if (uid > 0 && !WebSocketManager.isConnected) WebSocketManager.connect(this, uid)

            StrategyManager.checkAndExecuteRemoteCommand(this) { commandTime ->
                pendingClearCommandTime = commandTime
            }

            if (lastNetType == "WiFi") uploadData(lastLat, lastLng, lastAddr, null)
            else triggerSingleLocation(false)
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
                        uploadData(lastLat, lastLng, lastAddr, null)
                    } else {
                        if (!isFallbackToGps) {
                            isFallbackToGps = true
                            syncHandler.post(locationRunnable)
                            return@setLocationListener
                        }
                        isFallbackToGps = false
                        uploadData(lastLat, lastLng, lastAddr, UserPrefs.getNotifError(this@LocationService).ifEmpty { "⚠️ 信号盲区/波动" })
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
                if (isFallbackToGps) {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isGpsFirst = true
                    isOnceLocationLatest = true
                    httpTimeOut = 15000
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
        syncHandler.postDelayed(locationRunnable, 2500)
    }

    private fun uploadData(lat: Double?, lng: Double?, addr: String?, fgAppOverride: String? = null) {
        if (DeviceUtil.getNetworkInfo(this).first == "无网络") return
        val uid = UserPrefs.getUserId(this)
        if (uid == -1 || UserPrefs.getPartnerId(this) <= 0) return

        val battery = DeviceUtil.getBatteryLevel(this)
        val (netType, wifiName) = DeviceUtil.getNetworkInfo(this)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val finalFgApp = fgAppOverride ?: if (!isScreenOn) "息屏睡眠 💤" else DeviceUtil.getForegroundApp(this)

        NetworkClient.getApi(this).syncAll(
            action = "sync_all", userId = uid, deviceId = deviceId, lat = lat, lng = lng, address = addr,
            battery = battery, netType = netType, wifiName = wifiName,
            fgApp = finalFgApp, micBusy = DeviceUtil.getMicBusyStatus(this),
            steps = DeviceUtil.getStepCount(this), topApps = "[]",
            clearCommandTime = if (pendingClearCommandTime > 0L) pendingClearCommandTime else null
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                val body = response.body()
                if (body != null) {
                    if (body.status == "error_kicked") {
                        handleKickedOffline(body.message ?: "账号已在别处登录")
                    } else if (body.status == "success") {
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
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val displayTitle = UserPrefs.getNotifTitle(this).ifEmpty { "情侣手记实时守护中" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(displayTitle)
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
            val channel = NotificationChannel(CHANNEL_ID, "位置实时共享", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}