package cn.xtay.lovejournal.ui

import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.model.PartnerData
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.DeviceUtil
import cn.xtay.lovejournal.util.UserPrefs
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.Gson
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class DeviceFragment : Fragment() {

    private lateinit var tvPartnerBattery: TextView
    private lateinit var progressPartnerBattery: LinearProgressIndicator
    private lateinit var tvPartnerNet: TextView
    private lateinit var tvPartnerApp: TextView
    private lateinit var viewMicIndicator: ImageView
    private lateinit var tvMicStatus: TextView
    private lateinit var tvPartnerSteps: TextView

    private lateinit var tvMyBattery: TextView
    private lateinit var progressMyBattery: LinearProgressIndicator
    private lateinit var tvMyNet: TextView
    private lateinit var tvMyApp: TextView
    private lateinit var viewMyMicIndicator: ImageView
    private lateinit var tvMyMicStatus: TextView
    private lateinit var tvMySteps: TextView
    private lateinit var tvSyncHint: TextView

    // 🚀 新增：下拉刷新控件
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastFetchTime = 0L // 🚀 新增：防抖动控制

    // 🚀 优化后的 10 秒轮询任务
    private val uiTask = object : Runnable {
        override fun run() {
            updateMyUI()
            fetchPartnerStatus(isSilent = true) // 定时刷新时静默请求，不弹 Toast
            uiHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_device, container, false)

        // 🚀 初始化下拉刷新控件
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout)

        tvPartnerBattery = view.findViewById(R.id.tv_partner_battery)
        progressPartnerBattery = view.findViewById(R.id.progress_partner_battery)
        tvPartnerNet = view.findViewById(R.id.tv_partner_net)
        tvPartnerApp = view.findViewById(R.id.tv_partner_app)
        viewMicIndicator = view.findViewById(R.id.view_mic_indicator)
        tvMicStatus = view.findViewById(R.id.tv_mic_status)
        tvPartnerSteps = view.findViewById(R.id.tv_partner_steps)

        tvMyBattery = view.findViewById(R.id.tv_my_battery)
        progressMyBattery = view.findViewById(R.id.progress_my_battery)
        tvMyNet = view.findViewById(R.id.tv_my_net)
        tvMyApp = view.findViewById(R.id.tv_my_app)
        viewMyMicIndicator = view.findViewById(R.id.view_my_mic_indicator)
        tvMyMicStatus = view.findViewById(R.id.tv_my_mic_status)
        tvMySteps = view.findViewById(R.id.tv_my_steps)
        tvSyncHint = view.findViewById(R.id.tv_sync_hint)

        // 🚀 设置下拉刷新的颜色和监听器
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
        swipeRefreshLayout.setColorSchemeColors(typedValue.data)

        swipeRefreshLayout.setOnRefreshListener {
            updateMyUI() // 下拉时也顺便刷新一下自己的状态UI
            fetchPartnerStatus(isSilent = false) // 手动下拉，不是静默
        }

        return view
    }

    // 🚀 改造获取数据方法，加入防抖和下拉动画控制
    private fun fetchPartnerStatus(isSilent: Boolean = false) {
        val now = System.currentTimeMillis()
        // 防抖：1秒内不重复请求
        if (now - lastFetchTime < 1000) {
            swipeRefreshLayout.isRefreshing = false
            return
        }

        val uid = UserPrefs.getUserId(requireContext())
        val pid = UserPrefs.getPartnerId(requireContext())

        if (uid == -1 || pid == -1) {
            swipeRefreshLayout.isRefreshing = false
            updatePartnerUI()
            return
        }

        // 如果是手动下拉，且正在刷新，给个友好提示
        if (!isSilent && swipeRefreshLayout.isRefreshing) {
            Toast.makeText(context, "正在获取TA的最新状态...", Toast.LENGTH_SHORT).show()
        }

        lastFetchTime = now
        // 如果是代码触发的非静默刷新，强制显示加载圈
        if (!swipeRefreshLayout.isRefreshing && !isSilent) {
            swipeRefreshLayout.isRefreshing = true
        }

        NetworkClient.getApi(requireContext()).getStatus(userId = uid, partnerId = pid)
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    swipeRefreshLayout.isRefreshing = false // 关闭动画
                    val res = response.body() ?: return
                    if (res.status == "success" && res.partner_data != null) {
                        UserPrefs.savePartnerDeviceJson(requireContext(), Gson().toJson(res.partner_data))
                        updatePartnerUI()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    swipeRefreshLayout.isRefreshing = false // 关闭动画
                    updatePartnerUI() // 失败时用本地缓存兜底显示
                }
            })
    }

    private fun updatePartnerUI() {
        val ctx = context ?: return
        val json = UserPrefs.getPartnerDeviceJson(ctx) ?: return
        try {
            val data = Gson().fromJson(json, PartnerData::class.java)
            val dev = data.device ?: return

            val rawBattery = dev.battery
            val isCharging = rawBattery > 100
            val realBattery = if (isCharging) rawBattery - 100 else rawBattery

            tvPartnerBattery.text = if (isCharging) "$realBattery% (充电中)" else "$realBattery%"
            progressPartnerBattery.setProgress(realBattery, true)

            val wifiName = dev.wifi_name ?: ""
            tvPartnerNet.text = if (wifiName.isNotBlank() && wifiName != "运营商网络") {
                "网络：${dev.net_type} ($wifiName)"
            } else {
                "网络：${dev.net_type ?: "未知"}"
            }

            tvPartnerApp.text = "当前前台：${dev.foreground_app ?: "桌面或隐藏"}"
            renderMicUI(dev.mic_busy, viewMicIndicator, tvMicStatus)

            tvPartnerSteps.text = "运动步数：${dev.steps} 步"

            data.device?.updated_at?.let {
                tvSyncHint.text = "状态最后同步于: ${it.substring(11, 16)}"
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun updateMyUI() {
        val ctx = context ?: return
        val rawBattery = DeviceUtil.getBatteryLevel(ctx)
        val isCharging = rawBattery > 100
        val realBattery = if (isCharging) rawBattery - 100 else rawBattery

        tvMyBattery.text = if (isCharging) "$realBattery% (充电中)" else "$realBattery%"
        progressMyBattery.setProgress(realBattery, true)

        val (netType, wifiName) = DeviceUtil.getNetworkInfo(ctx)
        tvMyNet.text = if (wifiName.isNotBlank() && netType == "WiFi") "网络：$netType ($wifiName)" else "网络：$netType"

        tvMyApp.text = "状态：${DeviceUtil.getForegroundApp(ctx)}"
        val myMicBusy = DeviceUtil.getMicBusyStatus(ctx)
        renderMicUI(myMicBusy, viewMyMicIndicator, tvMyMicStatus)

        tvMySteps.text = "运动步数：${DeviceUtil.getStepCount(ctx)} 步"
    }

    private fun renderMicUI(status: Int, indicator: ImageView, label: TextView) {
        if (status == 1) {
            indicator.imageTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            label.text = "麦克风：通话/录音中"
            label.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            indicator.imageTintList = ColorStateList.valueOf(Color.parseColor("#757575"))
            label.text = "麦克风：闲置 (未占用)"
            label.setTextColor(Color.parseColor("#444444"))
        }
    }

    override fun onResume() {
        super.onResume()
        // 进页面时先主动静默刷一次
        updateMyUI()
        fetchPartnerStatus(isSilent = true)
        // 启动 10 秒轮询
        uiHandler.postDelayed(uiTask, 10000)
    }

    override fun onPause() {
        super.onPause()
        // 离开页面停止轮询
        uiHandler.removeCallbacks(uiTask)
    }
}