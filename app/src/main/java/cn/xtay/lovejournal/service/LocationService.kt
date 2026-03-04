package cn.xtay.lovejournal.service

import android.Manifest
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
import android.telephony.CellLocation
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.work.*
import cn.xtay.lovejournal.LoginActivity
import cn.xtay.lovejournal.MainActivity
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.model.local.AppDatabase
import cn.xtay.lovejournal.model.local.LocationEntity
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.DeviceUtil
import cn.xtay.lovejournal.util.StrategyManager
import cn.xtay.lovejournal.util.UserPrefs
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            try {
                applicationContext.startService(intent)
            } catch (ex: Exception) { ex.printStackTrace() }
        }
        return Result.success()
    }
}

class LocationService : Service() {

    companion object {
        var isRunning = false
            private set
        private const val WORK_NAME = "ScreenOffSyncWork"
        // 💖 新增：OTA 更新通知广播 Action
        const val ACTION_UPDATE_AVAILABLE = "cn.xtay.lovejournal.ACTION_UPDATE_AVAILABLE"
    }

    private var mLocationClient: AMapLocationClient? = null
    private val CHANNEL_ID = "love_journal_bg_location"
    private val NOTIFICATION_ID = 2026

    private var tempWakeLock: PowerManager.WakeLock? = null
    private var isEmergencyLocation = false

    // 🌟 核心新增：记录是否处于 GPS 兜底重试状态
    private var isFallbackToGps = false

    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastAddr: String? = null

    private var telephonyManager: TelephonyManager? = null
    private var lastCellId: String? = null
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: Any? = null

    private var pendingClearCommandTime: Long = 0L

    private fun acquireTempWakeLock(timeoutMs: Long = 30000L) {
        if (tempWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            tempWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoveJournal:TempWakeLock")
            tempWakeLock?.setReferenceCounted(false)
        }
        tempWakeLock?.acquire(timeoutMs)
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var screenOffTime = 0L
    private var lastLocationTime = 0L
    private var lastUploadTime = 0L

    private val heartbeatRunnable = Runnable { checkAndExecuteHeartbeat() }

    private fun checkAndExecuteHeartbeat() {
        if (!isScreenOn) return

        if (DeviceUtil.getNetworkInfo(this).first == "无网络") {
            return
        }

        StrategyManager.checkAndExecuteRemoteCommand(this) { commandTime ->
            pendingClearCommandTime = commandTime

            // 💖 核心新增：解析 OTA 更新指令
            val cmd = UserPrefs.getRemoteCommand(this@LocationService)
            if (cmd.startsWith("ota_update|")) {
                // 1. 发送广播通知 MainActivity (如果它在前台就弹窗)
                sendBroadcast(Intent(ACTION_UPDATE_AVAILABLE))
                // 2. 上报更新状态
                uploadData(lastLat, lastLng, lastAddr, "📥 收到系统更新推送")
            } else {
                uploadData(lastLat, lastLng, lastAddr, "✅ 已执行远控指令")
            }
        }

        val batteryLevel = DeviceUtil.getBatteryLevel(this)
        val isIntercepted = StrategyManager.shouldIntercept(this, batteryLevel) { msg ->
            uploadData(lastLat, lastLng, lastAddr, msg)
        }

        if (isIntercepted) {
            scheduleNextHeartbeat()
            return
        }

        val now = System.currentTimeMillis()
        val syncInterval = 30000L
        val gpsInterval = 300000L

        if (now - lastUploadTime < syncInterval - 5000L) {
            scheduleNextHeartbeat()
            return
        }

        lastUploadTime = now
        val isWifiConnected = (lastNetType == "WiFi")

        if (isWifiConnected) {
            uploadData(lastLat, lastLng, lastAddr, null)
        } else {
            if (now - lastLocationTime >= gpsInterval - 15000L) {
                triggerSingleLocation(false)
                lastLocationTime = now
            } else {
                uploadData(lastLat, lastLng, lastAddr, null)
            }
        }
        scheduleNextHeartbeat()
    }

    private fun scheduleNextHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        if (isScreenOn) {
            heartbeatHandler.postDelayed(heartbeatRunnable, 30000L)
        }
    }

    private fun handleCellLocationChange(location: CellLocation?) {
        val currentCellId = location?.toString()
        if (currentCellId != null && currentCellId != lastCellId) {
            lastCellId = currentCellId

            if (DeviceUtil.getNetworkInfo(this).first == "无网络") return

            if (!isScreenOn && lastNetType != "WiFi") {
                val now = System.currentTimeMillis()

                if (now - lastLocationTime >= 300000L) {
                    acquireTempWakeLock(60000L)

                    // 💖 修改：使用自定义移动中文案
                    val customVal = UserPrefs.getNotifMoving(this)
                    val displayMsg = if (customVal.isNotEmpty()) customVal else "🚶 移动基站切换中..."

                    uploadData(lastLat, lastLng, lastAddr, displayMsg)
                    triggerSingleLocation(true)

                    lastLocationTime = now
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    StrategyManager.reset()
                    isScreenOn = false
                    screenOffTime = System.currentTimeMillis()
                    heartbeatHandler.removeCallbacksAndMessages(null)

                    if (DeviceUtil.getNetworkInfo(this@LocationService).first == "无网络") {
                        return
                    }

                    acquireTempWakeLock(60000L)
                    uploadData(lastLat, lastLng, lastAddr, null)

                    if (lastNetType != "WiFi") {
                        triggerSingleLocation(true)
                    }

                    val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
                    WorkManager.getInstance(this@LocationService).enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        workRequest
                    )
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    WorkManager.getInstance(this@LocationService).cancelUniqueWork(WORK_NAME)

                    acquireTempWakeLock()
                    lastUploadTime = 0L
                    checkAndExecuteHeartbeat()
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

        val isNetworkChanged = (netType != lastNetType) || (newWifi != lastWifiName)

        if (isNetworkChanged) {
            lastNetType = netType
            lastWifiName = newWifi

            if (netType == "无网络") {
                // 💖 修改：使用自定义断网文案
                val customOff = UserPrefs.getNotifOffline(this@LocationService)
                val displayMsg = if (customOff.isNotEmpty()) customOff else "无网络连接，已暂停后台同步"

                updateNotification(displayMsg)
                heartbeatHandler.removeCallbacks(locationRunnable)
                return@Runnable
            }

            StrategyManager.reset()
            acquireTempWakeLock(60000L)

            val now = System.currentTimeMillis()
            lastLocationTime = now
            lastUploadTime = now

            val statusMsg = when (netType) {
                "WiFi" -> "刚连上 $newWifi"
                "移动数据" -> "已切换至移动数据"
                else -> "网络状态刷新"
            }
            uploadData(lastLat, lastLng, lastAddr, statusMsg)

            triggerSingleLocation(true)

            // 💖 修改：使用自定义正常运行文案
            val customNormal = UserPrefs.getNotifNormal(this@LocationService)
            val baseMsg = if (customNormal.isNotEmpty()) customNormal else "守护中：网络正常"
            updateNotification("$baseMsg (${netType})")

            if (isScreenOn) scheduleNextHeartbeat()
        }
    }

    private fun initNetworkObserver() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 💖 唤醒锁加长至 20 秒，覆盖延迟
                acquireTempWakeLock(20000L)
                heartbeatHandler.removeCallbacks(networkCheckRunnable)
                // 💖 延迟 1.5 秒执行网络状态抓取
                heartbeatHandler.postDelayed(networkCheckRunnable, 1500L)
            }
            override fun onLost(network: Network) {
                // 💖 唤醒锁加长至 20 秒，覆盖延迟
                acquireTempWakeLock(20000L)
                heartbeatHandler.removeCallbacks(networkCheckRunnable)
                // 💖 延迟 1.5 秒执行断网状态抓取
                heartbeatHandler.postDelayed(networkCheckRunnable, 1500L)
            }
        }
        connectivityManager?.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback!!)
        // 初始启动时不用延迟
        heartbeatHandler.post(networkCheckRunnable)
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = pm.isInteractive

        if (!isScreenOn) {
            screenOffTime = System.currentTimeMillis()
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("守护引擎初始化中..."))

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        registerReceiver(screenStateReceiver, filter)

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CellLocationListener {
                override fun onCellLocationChanged(location: CellLocation) {
                    handleCellLocationChange(location)
                }
            }
            telephonyManager?.registerTelephonyCallback(mainExecutor, telephonyCallback as TelephonyCallback)
        } else {
            phoneStateListener = object : PhoneStateListener() {
                @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                override fun onCellLocationChanged(location: CellLocation?) {
                    super.onCellLocationChanged(location)
                    handleCellLocationChange(location)
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CELL_LOCATION)
        }

        initAMapLocation()
        initNetworkObserver()

        checkAndExecuteHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 💖 处理从设置页面发来的刷新横幅指令
        if (intent?.action == "ACTION_UPDATE_NOTIF") {
            val netInfo = DeviceUtil.getNetworkInfo(this).first
            if (netInfo == "无网络") {
                val customOff = UserPrefs.getNotifOffline(this)
                val displayMsg = if (customOff.isNotEmpty()) customOff else "无网络连接，已暂停后台同步"
                updateNotification(displayMsg)
            } else {
                val customNormal = UserPrefs.getNotifNormal(this)
                val baseMsg = if (customNormal.isNotEmpty()) customNormal else "守护中：网络正常"
                updateNotification("$baseMsg ($netInfo)")
            }
            return START_STICKY
        }

        if (intent?.action == "ACTION_WORKER_SYNC") {
            if (DeviceUtil.getNetworkInfo(this).first == "无网络") return START_STICKY

            acquireTempWakeLock(60000L)

            // 💖 核心新增：Worker 同步时也检查一遍 OTA 指令
            StrategyManager.checkAndExecuteRemoteCommand(this) { commandTime ->
                pendingClearCommandTime = commandTime
                val cmd = UserPrefs.getRemoteCommand(this@LocationService)
                if (cmd.startsWith("ota_update|")) {
                    sendBroadcast(Intent(ACTION_UPDATE_AVAILABLE))
                }
            }

            if (lastNetType == "WiFi") {
                uploadData(lastLat, lastLng, lastAddr, null)
            } else {
                triggerSingleLocation(false)
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
                        // 🌟 成功获取位置，重置标志位
                        isFallbackToGps = false

                        val dbLog = LocationEntity(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            address = location.address ?: "未知地址",
                            timestamp = System.currentTimeMillis(),
                            locationType = location.locationType,
                            accuracy = location.accuracy
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
                        // 🌟 核心逻辑：如果在省电模式下（基站/WiFi）没找到位置
                        if (!isFallbackToGps) {
                            // 标记进入降级补偿状态，并立即重试
                            isFallbackToGps = true
                            heartbeatHandler.post(locationRunnable)
                            return@setLocationListener
                        }

                        // 🌟 如果连 GPS 兜底都失败了，彻底放弃，记录异常
                        isFallbackToGps = false

                        val errorLog = LocationEntity(
                            latitude = 0.0,
                            longitude = 0.0,
                            address = "高德异常: ${location.errorInfo}",
                            timestamp = System.currentTimeMillis(),
                            locationType = -location.errorCode,
                            accuracy = 0f
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            val db = AppDatabase.getDatabase(applicationContext)
                            db.locationDao().insertLog(errorLog)
                            db.locationDao().trimDatabase()
                        }

                        lastLocationTime = 0L

                        // 💖 修改：使用自定义异常文案
                        val customErr = UserPrefs.getNotifError(this@LocationService)
                        val displayMsg = if (customErr.isNotEmpty()) customErr else "⚠️ 信号盲区/波动"

                        uploadData(lastLat, lastLng, lastAddr, displayMsg)
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

                // 🌟 所有状态默认使用低精度，只有触发兜底才激活 GPS
                if (isFallbackToGps) {
                    // 荒郊野外模式：临时启用高精度 (GPS + 辅助网络)
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isGpsFirst = true
                    isOnceLocationLatest = true
                    httpTimeOut = 15000
                } else {
                    // 全天候省电模式：完全不开启 GPS，仅依赖附近基站和 WiFi 嗅探
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
        heartbeatHandler.removeCallbacks(locationRunnable)
        heartbeatHandler.postDelayed(locationRunnable, 2500)
    }

    private fun uploadData(lat: Double?, lng: Double?, addr: String?, fgAppOverride: String? = null) {
        if (DeviceUtil.getNetworkInfo(this).first == "无网络") return

        val uid = UserPrefs.getUserId(this)
        if (uid == -1 || UserPrefs.getPartnerId(this) <= 0) return

        val battery = DeviceUtil.getBatteryLevel(this)
        val (netType, wifiName) = DeviceUtil.getNetworkInfo(this)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        val finalFgApp = if (!isScreenOn) {
            if (fgAppOverride != null) {
                fgAppOverride
            } else {
                val diffMins = (System.currentTimeMillis() - screenOffTime) / 60000
                if (diffMins <= 0L) "息屏" else "已息屏 ${diffMins}分钟"
            }
        } else {
            fgAppOverride ?: DeviceUtil.getForegroundApp(this)
        }

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
                        body.server_config?.let { config ->
                            UserPrefs.saveServerConfigData(this@LocationService, config)
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
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        Handler(Looper.getMainLooper()).postDelayed({
            Toast.makeText(applicationContext, "⚠️ 强制下线: $reason", Toast.LENGTH_LONG).show()
        }, 500)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyCallback?.let {
                telephonyManager?.unregisterTelephonyCallback(it as TelephonyCallback)
            }
        } else {
            phoneStateListener?.let {
                telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE)
            }
        }

        heartbeatHandler.removeCallbacksAndMessages(null)
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

        // 💖 修改：使用自定义总标题
        val customTitle = UserPrefs.getNotifTitle(this)
        val displayTitle = if (customTitle.isNotEmpty()) customTitle else "情侣手记实时守护中"

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
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "位置实时共享", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}