package cn.xtay.lovejournal.ui

import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.model.local.AppDatabase
import cn.xtay.lovejournal.model.local.LocationEntity
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.util.DeviceUtil
import cn.xtay.lovejournal.util.UserPrefs
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MapFragment : Fragment() {

    private lateinit var mapView: MapView
    private var aMap: AMap? = null
    private var locationClient: AMapLocationClient? = null

    private lateinit var tvMyAddr: TextView
    private lateinit var tvPartnerAddr: TextView
    private var partnerPos: LatLng? = null
    private var myPos: LatLng? = null

    private val dbDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    // 极简冷却机制，告别卡顿屎山
    private var lastRefreshTime = 0L
    private var lastLocateTaTime = 0L

    private fun getPartnerName() = UserPrefs.getPartnerNickname(requireContext()) ?: "对方"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)

        // 这里的 mapView 外层在 XML 里应该已经被你的 MapContainer 包裹了，防冲突交由 MapContainer 处理
        mapView = view.findViewById(R.id.map_view)
        tvMyAddr = view.findViewById(R.id.tv_my_address)
        tvPartnerAddr = view.findViewById(R.id.tv_partner_address)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btn_refresh)
        val btnLocateTa = view.findViewById<MaterialButton>(R.id.btn_locate_partner)

        mapView.onCreate(savedInstanceState)
        if (aMap == null) {
            aMap = mapView.map
            aMap?.uiSettings?.isZoomControlsEnabled = false
        }

        tvMyAddr.text = renderStyledLocation("我的位置：", "等待刷新...", null, false)

        // 🟢 刷新自己的位置 (3秒极简防抖)
        btnRefresh.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastRefreshTime < 3000) {
                Toast.makeText(context, "刷新太快啦，休息一下~", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastRefreshTime = now

            if (UserPrefs.getPartnerId(requireContext()) <= 0) {
                Toast.makeText(context, "请先绑定另一半才能开启实时同步哦~", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(context, "正在获取全部状态并同步...", Toast.LENGTH_SHORT).show()
            forceSyncMyLocation()
        }

        // 🟢 探测雷达 (30秒严厉冷却)
        btnLocateTa.setOnClickListener {
            val partnerId = UserPrefs.getPartnerId(requireContext())
            if (partnerId <= 0) {
                Toast.makeText(context, "快去邀请你的另一半绑定吧！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val now = System.currentTimeMillis()
            if (now - lastLocateTaTime < 30000) {
                val remain = (30000 - (now - lastLocateTaTime)) / 1000
                Toast.makeText(context, "探测雷达冷却中，请等待 ${remain} 秒", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastLocateTaTime = now

            if (WebSocketManager.isConnected) {
                Toast.makeText(context, "📡 已发射雷达波，正在唤醒对方后台...", Toast.LENGTH_SHORT).show()

                // 1. 发射唤醒指令
                WebSocketManager.sendMessage("send_to_partner", partnerId, "force_location")

                // 2. 延迟 4.5 秒，等待对方基站定位完成并上传后，瞬间拉取结果！
                view.postDelayed({
                    if (isAdded) pullPartnerLocation(moveToTa = true)
                }, 4500)

            } else {
                Toast.makeText(context, "网络通道未就绪，正在尝试普通拉取...", Toast.LENGTH_SHORT).show()
                pullPartnerLocation(moveToTa = true)
            }
        }

        return view
    }

    private fun formatTime(rawTime: String?): String {
        if (rawTime.isNullOrBlank()) return "刚刚"
        return try { rawTime.substring(11, 16) } catch (e: Exception) { "刚刚" }
    }

    private fun renderStyledLocation(label: String, address: String?, time: String?, isPartner: Boolean, stayMsg: String? = null): android.text.Spanned {
        val displayTime = formatTime(time)
        val addr = address ?: "位置获取中..."
        val colorMain = if (isPartner) "#FF5252" else "#2196F3"
        val boldStart = if (isPartner) "<b>" else ""
        val boldEnd = if (isPartner) "</b>" else ""

        val stayHtml = if (!stayMsg.isNullOrEmpty()) "<br/><small><font color='#4CAF50'>☕ $stayMsg</font></small>" else ""

        val htmlSource = """
            $boldStart<font color='$colorMain'>$label</font><font color='$colorMain'>$addr</font>$boldEnd<br/>
            <small><font color='$colorMain'>● </font><font color='#888888'>更新于 $displayTime</font></small>$stayHtml
        """.trimIndent()

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(htmlSource, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION") Html.fromHtml(htmlSource)
        }
    }

    private fun forceSyncMyLocation() {
        if (locationClient != null) {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
            locationClient = null
        }

        locationClient = AMapLocationClient(requireContext().applicationContext)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = true
        }
        locationClient?.setLocationOption(option)

        locationClient?.setLocationListener { location ->
            if (location != null) {
                if (location.errorCode == 0) {
                    val dbLog = LocationEntity(
                        latitude = location.latitude, longitude = location.longitude, address = location.address ?: "未知地址",
                        timestamp = System.currentTimeMillis(), locationType = location.locationType, accuracy = location.accuracy
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(requireContext())
                        db.locationDao().insertLog(dbLog)
                        db.locationDao().trimDatabase()
                    }

                    val lat = location.latitude
                    val lng = location.longitude
                    val addr = location.address ?: ""

                    myPos = LatLng(lat, lng)
                    refreshMapMarkers()
                    aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(myPos!!, 16f))

                    tvMyAddr.text = renderStyledLocation("我的位置：", addr, null, false)

                    if (location.locationType == 1 || location.locationType == 5 || location.locationType == 6) {
                        val uid = UserPrefs.getUserId(requireContext())
                        if (uid != -1 && UserPrefs.getPartnerId(requireContext()) > 0) {
                            val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
                            NetworkClient.getApi(requireContext()).syncAll(
                                action = "sync_all", userId = uid, deviceId = deviceId, lat = lat, lng = lng, address = addr,
                                battery = DeviceUtil.getBatteryLevel(requireContext()), netType = DeviceUtil.getNetworkInfo(requireContext()).first,
                                wifiName = DeviceUtil.getNetworkInfo(requireContext()).second, fgApp = DeviceUtil.getForegroundApp(requireContext()),
                                micBusy = DeviceUtil.getMicBusyStatus(requireContext()), steps = DeviceUtil.getStepCount(requireContext()), topApps = "[]"
                            ).enqueue(object : Callback<UserResponse> {
                                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                                    if (response.isSuccessful && response.body()?.status == "success") {
                                        Toast.makeText(context, "全部状态已同步给${getPartnerName()}", Toast.LENGTH_SHORT).show()
                                    } else if (response.body()?.status == "error_kicked") {
                                        Toast.makeText(context, "⚠️ 该账号已在其他设备登录", Toast.LENGTH_LONG).show()
                                    }
                                }
                                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                                    Toast.makeText(context, "网络同步失败", Toast.LENGTH_SHORT).show()
                                }
                            })
                        }
                    } else {
                        Toast.makeText(context, "获取到缓存位置，未同步给${getPartnerName()}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errorLog = LocationEntity(
                        latitude = 0.0, longitude = 0.0, address = "高德异常: ${location.errorInfo}",
                        timestamp = System.currentTimeMillis(), locationType = -location.errorCode, accuracy = 0f
                    )
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(requireContext())
                        db.locationDao().insertLog(errorLog)
                        db.locationDao().trimDatabase()
                    }
                    Toast.makeText(context, "定位波动(码:${location.errorCode})", Toast.LENGTH_SHORT).show()
                    tvMyAddr.text = renderStyledLocation("我的位置：", "定位失败", null, false)
                }
            }
        }
        locationClient?.startLocation()
    }

    private fun pullPartnerLocation(moveToTa: Boolean) {
        val uid = UserPrefs.getUserId(requireContext())
        val pid = UserPrefs.getPartnerId(requireContext())
        val partnerName = getPartnerName()

        if (uid == -1 || pid <= 0) {
            showPartnerNotBound()
            return
        }

        NetworkClient.getApi(requireContext()).getStatus(userId = uid, partnerId = pid).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                val res = response.body() ?: return
                if (res.status == "success") {
                    res.partner_data?.location?.let { taLoc ->
                        partnerPos = LatLng(taLoc.latitude, taLoc.longitude)
                        var stayMsg: String? = null
                        val deviceData = res.partner_data?.device

                        deviceData?.updated_at?.let { devTimeStr ->
                            try {
                                val locTime = dbDateFormat.parse(taLoc.updated_at ?: "")?.time ?: 0L
                                val devTime = dbDateFormat.parse(devTimeStr)?.time ?: 0L
                                val nowTime = System.currentTimeMillis()
                                val diffFromNow = nowTime - devTime
                                val isOffline = diffFromNow >= 2 * 60 * 60 * 1000L
                                val isScreenOffStay = diffFromNow >= 1 * 60 * 60 * 1000L
                                val isWifiAnchored = deviceData.net_type == "WiFi" && !deviceData.wifi_name.isNullOrEmpty()

                                if (locTime > 0 && devTime > 0) {
                                    if (isOffline) {
                                        stayMsg = "⚠️ 疑似离线 (超2小时无数据)"
                                    } else if (isScreenOffStay) {
                                        stayMsg = "💤 息屏停留 (设备安静中)"
                                    } else {
                                        if (isWifiAnchored) {
                                            val diffMs = if (devTime > locTime) devTime - locTime else 0L
                                            val mins = diffMs / (60 * 1000L)
                                            stayMsg = if (mins <= 1) "刚刚定锚于 ${deviceData.wifi_name}" else "已定锚于 ${deviceData.wifi_name} (${mins}分钟)"
                                        } else {
                                            stayMsg = "移动网络在线"
                                        }
                                    }
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                        }

                        tvPartnerAddr.text = renderStyledLocation("${partnerName}位置：", taLoc.address, taLoc.updated_at, true, stayMsg)
                        refreshMapMarkers()

                        if (moveToTa && partnerPos != null) {
                            aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(partnerPos!!, 16f))
                            Toast.makeText(context, "📍 目标锁定：${taLoc.address}", Toast.LENGTH_SHORT).show()
                        }
                    } ?: run {
                        showPartnerNotSynced()
                    }
                }
            }
            override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                if (moveToTa) Toast.makeText(context, "网络错误，无法拉取对方位置", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun refreshMapMarkers() {
        aMap?.clear()
        myPos?.let { drawMarker(it, "我", true) }
        partnerPos?.let { drawMarker(it, getPartnerName(), false) }
    }

    private fun drawMarker(pos: LatLng, title: String, isMe: Boolean) {
        aMap?.addMarker(MarkerOptions().position(pos).title(title).icon(
            BitmapDescriptorFactory.defaultMarker(if (isMe) BitmapDescriptorFactory.HUE_AZURE else BitmapDescriptorFactory.HUE_RED)
        ))
    }

    private fun showPartnerNotSynced() {
        val htmlSource = "<b><font color='#FF5252'>${getPartnerName()}位置：尚未同步</font></b>"
        tvPartnerAddr.text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(htmlSource, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION") Html.fromHtml(htmlSource)
        }
    }

    private fun showPartnerNotBound() {
        val htmlSource = "<b><font color='#999999'>对方位置：等待你的甜蜜邀请...</font></b>"
        tvPartnerAddr.text = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.fromHtml(htmlSource, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION") Html.fromHtml(htmlSource)
        }
        partnerPos = null
        refreshMapMarkers()
    }

    override fun onResume() { super.onResume(); mapView.onResume(); pullPartnerLocation(moveToTa = false) }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onDestroy() { super.onDestroy(); mapView.onDestroy(); locationClient?.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
}