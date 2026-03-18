package cn.xtay.lovejournal

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.xtay.lovejournal.ui.ChatActivity
import cn.xtay.lovejournal.util.UserPrefs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class NetworkOptActivity : AppCompatActivity() {

    private lateinit var ivRadar: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var cardCache: View

    private val handler = Handler(Looper.getMainLooper())
    private var isEngineRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🚀 核心修复：一进页面立刻继承“隐藏最近任务”的隐身斗篷
        applyHideRecents()

        // 沉浸式体验：隐藏状态栏和导航栏，让它看起来像系统底层工具
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_network_opt)

        ivRadar = findViewById(R.id.iv_radar_gif)
        tvStatus = findViewById(R.id.tv_network_status)
        tvLog = findViewById(R.id.tv_fake_log)
        cardCache = findViewById(R.id.card_cache_clean)

        // 1. 根据传入参数，决定是否显示“缓存待清理”
        val hasNewMessage = intent.getBooleanExtra("has_new_message", false)
        if (hasNewMessage) {
            cardCache.visibility = View.VISIBLE
        } else {
            cardCache.visibility = View.GONE
        }

        // 2. 绑定雷达主图的 2 秒暗门 (回主页)
        setSecretDoor(
            view = ivRadar,
            fakeToastMsg = "Engine is running smoothly...",
            targetActivityClass = MainActivity::class.java
        )

        // 3. 绑定缓存清理框的 2 秒暗门 (去聊天页)
        setSecretDoor(
            view = cardCache,
            fakeToastMsg = "已清理待优化",
            targetActivityClass = ChatActivity::class.java
        )
    }

    // 🚀 新增：防止多次点击通知栏无限叠加，并且重新抓取暗号
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val hasNewMessage = intent?.getBooleanExtra("has_new_message", false) ?: false
        if (hasNewMessage) {
            cardCache.visibility = View.VISIBLE
        } else {
            cardCache.visibility = View.GONE
        }
    }

    // 🚀 新增：同步隐藏最近任务列表功能
    private fun applyHideRecents() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                // 读取用户是否在设置里开启了隐藏后台
                val isHide = UserPrefs.isHideRecentsEnabled(this)
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                // 把隐身属性强加给当前整个任务栈
                am.appTasks?.firstOrNull()?.setExcludeFromRecents(isHide)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isEngineRunning = true

        // 🚀 核心：仅在前台加载并播放 GIF 引擎
        Glide.with(this)
            .asGif()
            .load(R.drawable.tech_radar) // 确保你的 gif 名字是 tech_radar.gif
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .into(ivRadar)

        // 启动动态伪装文案
        startFakeOptimizationLoop()
    }

    override fun onPause() {
        super.onPause()
        isEngineRunning = false
        handler.removeCallbacksAndMessages(null) // 停止日志滚动

        // 🚀 核心：切后台立刻销毁 GIF 渲染，绝对不费电！
        Glide.with(this).clear(ivRadar)
    }

    /**
     * 动态网络状态文本更新器
     */
    private fun startFakeOptimizationLoop() {
        if (!isEngineRunning) return

        val isWifi = isWifiConnected()

        // 动态状态
        tvStatus.text = if (isWifi) "WLAN Acceleration Active" else "Cellular Data Stabilizing"

        // 随机生成极客风日志
        val logs = arrayOf(
            "Checking DNS latency: ${java.util.Random().nextInt(20) + 5}ms",
            "Allocating background bandwidth...",
            "Encrypting data packets (AES-256)...",
            "Pinging nearby cellular towers...",
            "Clearing system TCP/IP cache..."
        )
        tvLog.text = logs[java.util.Random().nextInt(logs.size)]

        // 每隔 1.5 秒刷新一次日志
        handler.postDelayed({ startFakeOptimizationLoop() }, 1500)
    }

    /**
     * 真实的系统网络状态检测
     */
    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 🚀 终极防线：严格的 2 秒长按判定机制
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setSecretDoor(view: View, fakeToastMsg: String, targetActivityClass: Class<*>) {
        var isLongPressed = false
        val secretRunnable = Runnable {
            isLongPressed = true
            // 触发暗门！震动反馈并跳转
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            vibrator.vibrate(50) // 微小震动提示成功解锁

            startActivity(Intent(this@NetworkOptActivity, targetActivityClass))
            finish() // 销毁当前假页面
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
                    // 按下时，设定 2 秒后触发穿越
                    handler.postDelayed(secretRunnable, 2000)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(secretRunnable)
                    if (!isLongPressed) {
                        // 如果没按满 2 秒就松手，弹出假装的 Toast
                        Toast.makeText(this@NetworkOptActivity, fakeToastMsg, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(secretRunnable)
                    true
                }
                else -> false
            }
        }
    }
}