package cn.xtay.lovejournal

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.amap.api.maps.MapsInitializer
import com.amap.api.location.AMapLocationClient
import cn.xtay.lovejournal.util.MyDeviceAdminReceiver
import cn.xtay.lovejournal.util.UserPrefs
import cn.xtay.lovejournal.util.UpdateManager
import cn.xtay.lovejournal.service.LocationService
import cn.xtay.lovejournal.service.KeepAliveAccessibilityService
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    // 弹窗防重叠锁
    private var currentDialog: AlertDialog? = null

    // 💖 新增：更新管理器
    private val updateManager by lazy { UpdateManager(this) }

    // 💖 新增：OTA 指令监听广播
    private val otaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_UPDATE_AVAILABLE) {
                checkOtaCommand()
            }
        }
    }

    // 前台基础权限阵列（不含后台定位）
    private val RUNTIME_PERMISSIONS = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }.toTypedArray()

    private lateinit var viewPager: ViewPager2
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. 登录自检：未登录直接拦截
        if (!UserPrefs.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        DynamicColors.applyToActivityIfAvailable(this)

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        viewPager = findViewById(R.id.view_pager)
        bottomNav = findViewById(R.id.bottom_nav)
        viewPager.adapter = MainAdapter(this)
        viewPager.offscreenPageLimit = 3

        val rootLayout = findViewById<android.view.View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            bottomNav.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        setupNavigation()

        // 💖 注册 OTA 监听广播
        // 💖 这样写既能解决 Android 14 闪退，又能消除红线，兼容所有版本
        ContextCompat.registerReceiver(
            this,
            otaReceiver,
            IntentFilter(LocationService.ACTION_UPDATE_AVAILABLE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()

        // 💖 重置“稍后提醒”标志位，确保每次打开 APP 进入前台都能再次弹出
        UpdateManager.isDelayedInSession = false

        // 每次进入前台时，先关掉可能残留的对话框，防止叠罗汉
        currentDialog?.dismiss()

        // 💖 检查是否有待处理的更新指令
        checkOtaCommand()

        val partnerId = UserPrefs.getPartnerId(this)
        if (partnerId <= 0) {
            // 单身状态下，只弹绑定警告，不去烦用户要底层权限
            checkBindingStatus()
        } else {
            // ✅ 只要绑定了就直接去触发权限检查
            checkAndRequestNextPermission()
        }

        forceRefreshServiceNotification()
    }

    /**
     * 💖 核心新增：解析远控指令中的 OTA 更新信息
     */
    private fun checkOtaCommand() {
        val fullCommand = UserPrefs.getRemoteCommand(this)
        // 格式：ota_update|code|name|log|url
        if (fullCommand.startsWith("ota_update|")) {
            val parts = fullCommand.split("|")
            if (parts.size >= 5) {
                try {
                    val serverCode = parts[1].toInt()
                    val versionName = parts[2]
                    val updateLog = parts[3].replace("\\n", "\n") // 支持换行符转义
                    val downloadUrl = parts[4]

                    // 调用 UpdateManager 执行版本比对和弹窗
                    updateManager.checkAndShowDialog(
                        serverCode = serverCode,
                        versionName = versionName,
                        log = updateLog,
                        url = downloadUrl
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 检查情侣绑定状态
     */
    private fun checkBindingStatus() {
        currentDialog = AlertDialog.Builder(this)
            .setTitle("账号安全警告")
            .setMessage("你还未绑定情侣，账号将在注册成功24小时内自动失效，请及时绑定情侣")
            .setCancelable(false) // 强制必须看
            .setPositiveButton("立即绑定") { _, _ ->
                showManualBindDialog()
            }
            .setNegativeButton("我知道了", null)
            .show()
    }

    /**
     * 💖 修复 1：升级为双输入框（昵称 + 邀请码）
     */
    private fun showManualBindDialog() {
        currentDialog?.dismiss()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 0)
        }

        val etNickname = EditText(this).apply {
            hint = "填写你的昵称（建议实名/常用昵称）"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
        }

        val etCode = EditText(this).apply {
            hint = "输入 TA 的 6 位邀请码"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            isAllCaps = true
        }

        // 添加一个小提示字
        val tvHint = TextView(this).apply {
            text = "设置后不可修改，绑定后将开启实时守护"
            textSize = 12f
            setPadding(0, 10, 0, 20)
        }

        layout.addView(etNickname)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(etCode)
        layout.addView(tvHint)

        currentDialog = AlertDialog.Builder(this)
            .setTitle("绑定情侣")
            .setView(layout)
            .setPositiveButton("确认绑定", null) // 设为null防止直接关闭
            .setNegativeButton("取消", null)
            .show()

        currentDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase()

            if (nickname.isEmpty()) {
                Toast.makeText(this, "请输入你的昵称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (code.length < 6) {
                Toast.makeText(this, "请输入 6 位邀请码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performBindAction(nickname, code)
            currentDialog?.dismiss()
        }
    }

    /**
     * 💖 修复 2：执行绑定请求（补齐 nickname 参数）
     */
    private fun performBindAction(nickname: String, code: String) {
        val uid = UserPrefs.getUserId(this)
        NetworkClient.getApi(this).bind(userId = uid, nickname = nickname, targetCode = code)
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val res = response.body() ?: return
                    if (res.status == "success") {
                        // 💖 保存自己和对方的昵称
                        UserPrefs.saveNickname(this@MainActivity, nickname)
                        UserPrefs.savePartnerNickname(this@MainActivity, res.partner_nickname ?: "TA")
                        UserPrefs.savePartnerId(this@MainActivity, res.partner_id ?: -1)

                        Toast.makeText(this@MainActivity, "绑定成功！祝白头偕老 ❤️", Toast.LENGTH_SHORT).show()

                        // 绑定成功后，立即触发权限申请流程
                        checkAndRequestNextPermission()
                    } else {
                        Toast.makeText(this@MainActivity, res.message ?: "绑定失败", Toast.LENGTH_SHORT).show()
                        showManualBindDialog()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "网络错误，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // --- 权限管理与功能逻辑 ---

    private fun checkAndRequestNextPermission() {
        when {
            !hasRuntimePermissions() -> {
                ActivityCompat.requestPermissions(this, RUNTIME_PERMISSIONS, 1001)
            }
            !hasBackgroundLocationPermission() -> {
                showPermissionGuideDialog("开启后台守护", "为了让对方能随时看到你的位置，请在设置中选择【始终允许】位置权限。") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1002)
                    }
                }
            }
            !Settings.canDrawOverlays(this) -> {
                showPermissionGuideDialog("悬浮窗权限", "请开启悬浮窗权限。") {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            !isIgnoringBatteryOptimizations() -> {
                showPermissionGuideDialog("白名单权限", "请将应用加入电池优化白名单。") {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
            !hasUsageStatsPermission() -> {
                showPermissionGuideDialog("使用情况统计", "我们需要此权限来同步活跃状态。") {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            !isDeviceAdminActive() -> {
                showPermissionGuideDialog("设备管理员", "开启权限以防止应用被意外卸载。") {
                    val componentName = ComponentName(this, MyDeviceAdminReceiver::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    }
                    startActivity(intent)
                }
            }
            !isAccessibilityServiceEnabled() -> {
                showPermissionGuideDialog("终极保活", "请在【已下载应用】中开启【情侣手记】无障碍服务，开启后即使关掉通知位置也能更新。") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            else -> {
                tryStartLocationService()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkAndRequestNextPermission()
    }

    private fun tryStartLocationService() {
        val intent = Intent(this, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun forceRefreshServiceNotification() {
        if (LocationService.isRunning) {
            val intent = Intent(this, LocationService::class.java).apply { action = "REFRESH_NOTIFICATION" }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun hasRuntimePermissions() = RUNTIME_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, KeepAliveAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServices?.contains(expectedComponentName.flattenToString()) == true
    }

    private fun isIgnoringBatteryOptimizations() = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun hasUsageStatsPermission() = (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

    private fun isDeviceAdminActive() = (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(ComponentName(this, MyDeviceAdminReceiver::class.java))

    private fun showPermissionGuideDialog(title: String, msg: String, onConfirm: () -> Unit) {
        currentDialog?.dismiss()
        currentDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("去开启") { _, _ -> onConfirm() }
            .show()
    }

    override fun onStop() {
        super.onStop()
        forceRefreshServiceNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(otaReceiver) // 💖 记得销毁监听
        currentDialog?.dismiss()
    }

    private fun setupNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> viewPager.setCurrentItem(0, false)
                R.id.nav_period -> viewPager.setCurrentItem(1, false)
                R.id.nav_device -> viewPager.setCurrentItem(2, false)
                R.id.nav_setting -> viewPager.setCurrentItem(3, false)
            }
            true
        }
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                bottomNav.menu.getItem(position).isChecked = true
            }
        })
    }
}