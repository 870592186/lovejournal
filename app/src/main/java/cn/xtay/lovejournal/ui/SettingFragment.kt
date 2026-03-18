package cn.xtay.lovejournal.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.xtay.lovejournal.LoginActivity
import cn.xtay.lovejournal.MainActivity
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.model.local.AppDatabase
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.service.LocationService
import cn.xtay.lovejournal.util.UserPrefs
import cn.xtay.lovejournal.util.UpdateManager
import cn.xtay.lovejournal.util.IconStealthManager
import cn.xtay.lovejournal.widget.CoupleWidgetProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class SettingFragment : Fragment() {

    private lateinit var tvAccountName: TextView
    private lateinit var tvBindPartner: TextView
    private lateinit var cardAccountInfo: MaterialCardView
    private lateinit var btnLogout: Button
    private lateinit var switchHideRecents: MaterialSwitch
    private lateinit var switchStealthMode: MaterialSwitch // 🚀 新增：伪装开关
    private lateinit var ivWidgetAvatarSetup: ImageView

    private val updateManager by lazy { UpdateManager(requireContext()) }

    private fun checkDevSleepIntercept(): Boolean {
        val state = requireContext().getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state == 1 || state == 2) {
            Toast.makeText(requireContext(), "⚠️ 已开启深度省电，此功能暂时禁用", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(result.data!!.data!!)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 300, 300, true)
                    val avatarFile = File(requireContext().filesDir, "widget_avatar.jpg")
                    val out = FileOutputStream(avatarFile)
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()

                    ivWidgetAvatarSetup.setImageBitmap(scaledBitmap)
                    CoupleWidgetProvider.updateAllWidgets(requireContext())
                    Toast.makeText(requireContext(), "小组件头像已更新", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "图片处理失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_setting, container, false)

        initViews(view)
        refreshUI()
        checkLatestStatus()

        cardAccountInfo.setOnClickListener {
            if (UserPrefs.getPartnerId(requireContext()) <= 0) showBindDialog() else showAlreadyBoundInfo()
        }

        ivWidgetAvatarSetup.setOnClickListener {
            if (checkDevSleepIntercept()) return@setOnClickListener
            pickImageLauncher.launch(Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }

        switchHideRecents.isChecked = UserPrefs.isHideRecentsEnabled(requireContext())
        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            UserPrefs.setHideRecentsEnabled(requireContext(), isChecked)
            applyHideRecents(isChecked)
        }

        // 🚀 新增：潜伏模式开关逻辑
        val prefs = requireContext().getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
        switchStealthMode.isChecked = prefs.getBoolean("is_stealth_enabled", false)
        switchStealthMode.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener // 避免代码改变 isChecked 时触发 dialog

            val title = if (isChecked) "开启潜伏伪装" else "恢复真实身份"
            val msg = if (isChecked) {
                "开启后：\n1. 桌面图标和名称将变成“网络优化”\n2. 通知栏提示词将自动替换为网络优化相关文案\n3. App 会短暂闪退一次以刷新桌面图标\n4. 主界面将被隐藏，需长按隐藏页面元素进入"
            } else {
                "关闭后：\n1. 恢复“情侣手记”图标\n2. 通知栏文案将重置为默认提示\n3. App 会短暂闪退以刷新桌面"
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定") { _, _ ->
                    prefs.edit().putBoolean("is_stealth_enabled", isChecked).apply()

                    // 💡 联动修改通知栏文案
                    if (isChecked) {
                        UserPrefs.saveNotifTitle(requireContext(), "Android System")
                        UserPrefs.saveNotifNormal(requireContext(), "网络优化模式")
                        UserPrefs.saveNotifOffline(requireContext(), "等待网络连接")
                        UserPrefs.saveNotifMoving(requireContext(), "网络切换中")
                        UserPrefs.saveNotifError(requireContext(), "网络异常")
                        UserPrefs.saveNotifNewMsg(requireContext(), "系统缓存待清理")
                    } else {
                        UserPrefs.saveNotifTitle(requireContext(), "情侣手记实时守护中")
                        UserPrefs.saveNotifNormal(requireContext(), "守护中：网络正常")
                        UserPrefs.saveNotifOffline(requireContext(), "无网络连接，已暂停后台同步")
                        UserPrefs.saveNotifMoving(requireContext(), "🚶 移动基站切换中...")
                        UserPrefs.saveNotifError(requireContext(), "⚠️ 信号盲区/波动")
                        UserPrefs.saveNotifNewMsg(requireContext(), "收到一条新消息")
                    }

                    // 刷新服务里的通知
                    requireContext().startService(Intent(requireContext(), LocationService::class.java).apply {
                        action = "ACTION_UPDATE_NOTIF"
                    })

                    Toast.makeText(requireContext(), "正在变身，App即将刷新...", Toast.LENGTH_LONG).show()

                    // 延迟 1 秒执行图标切换，让 Toast 能显示出来
                    Handler(Looper.getMainLooper()).postDelayed({
                        IconStealthManager.switchAppIdentity(requireContext(), isChecked)
                    }, 1000)
                }
                .setNegativeButton("取消") { _, _ ->
                    switchStealthMode.isChecked = !isChecked // 恢复开关状态
                }
                .show()
        }

        // ==========================================
        // 🚀 新增：折叠面板逻辑区
        // ==========================================

        // 隐私与性能：折叠逻辑
        val headerPrivacy = view.findViewById<View>(R.id.header_privacy)
        val cardPrivacy = view.findViewById<View>(R.id.card_privacy)
        val ivArrowPrivacy = view.findViewById<ImageView>(R.id.iv_arrow_privacy)
        cardPrivacy?.visibility = View.GONE
        ivArrowPrivacy?.rotation = -90f
        // 绑定点击事件
        headerPrivacy?.setOnClickListener {
            // 获取当前卡片的可见性
            val isExpanded = cardPrivacy?.visibility == View.VISIBLE
            // 切换可见性
            cardPrivacy?.visibility = if (isExpanded) View.GONE else View.VISIBLE
            // 🎬 箭头旋转动画：展开时向下(0度)，收起时向右(-90度)
            ivArrowPrivacy?.animate()?.rotation(if (isExpanded) -90f else 0f)?.setDuration(250)?.start()
        }

        // 高级管理：折叠逻辑
        val headerAdvanced = view.findViewById<View>(R.id.header_advanced)
        val cardAdvanced = view.findViewById<View>(R.id.card_advanced)
        val ivArrowAdvanced = view.findViewById<ImageView>(R.id.iv_arrow_advanced)

        // 建议高级管理默认收起，让界面更清爽
        cardAdvanced?.visibility = View.GONE
        ivArrowAdvanced?.rotation = -90f // 初始状态为收起状态(向右)

        // 绑定点击事件
        headerAdvanced?.setOnClickListener {
            val isExpanded = cardAdvanced?.visibility == View.VISIBLE
            cardAdvanced?.visibility = if (isExpanded) View.GONE else View.VISIBLE
            ivArrowAdvanced?.animate()?.rotation(if (isExpanded) -90f else 0f)?.setDuration(250)?.start()
        }

        // ==========================================

        view.findViewById<TextView>(R.id.tv_server_manage).setOnClickListener {
            if (checkDevSleepIntercept()) return@setOnClickListener
            showServerSettingDialog()
        }
        view.findViewById<TextView>(R.id.tv_amap_manage).setOnClickListener {
            if (checkDevSleepIntercept()) return@setOnClickListener
            showAMapSettingDialog()
        }

        view.findViewById<TextView>(R.id.tv_location_logs)?.setOnClickListener {
            if (checkDevSleepIntercept()) return@setOnClickListener
            showLocationLogsDialog()
        }
        view.findViewById<TextView>(R.id.tv_notif_manage)?.setOnClickListener {
            if (checkDevSleepIntercept()) return@setOnClickListener
            showNotifManageDialog()
        }
        view.findViewById<TextView>(R.id.tv_check_update)?.setOnClickListener {
            manualCheckUpdate()
        }
        btnLogout.setOnClickListener {
            if (checkDevSleepIntercept()) return@setOnClickListener
            showLogoutConfirmDialog()
        }

        return view
    }

    private fun initViews(view: View) {
        tvAccountName = view.findViewById(R.id.tv_account_name)
        tvBindPartner = view.findViewById(R.id.tv_bind_partner)
        cardAccountInfo = view.findViewById(R.id.card_account_info)
        switchHideRecents = view.findViewById(R.id.switch_hide_recents)
        switchStealthMode = view.findViewById(R.id.switch_stealth_mode) // 🚀 初始化伪装开关
        btnLogout = view.findViewById(R.id.btn_logout)
        ivWidgetAvatarSetup = view.findViewById(R.id.iv_widget_avatar_setup)

        val avatarFile = File(requireContext().filesDir, "widget_avatar.jpg")
        if (avatarFile.exists()) {
            ivWidgetAvatarSetup.setImageBitmap(BitmapFactory.decodeFile(avatarFile.absolutePath))
        }
    }

    private fun refreshUI() {
        val user = UserPrefs.getNickname(requireContext()) ?: UserPrefs.getUsername(requireContext())
        tvAccountName.text = "当前账号：${user ?: "未知"}"

        val partnerName = UserPrefs.getPartnerNickname(requireContext())
        if (UserPrefs.getPartnerId(requireContext()) > 0) {
            tvBindPartner.text = if (partnerName != null) "已与 $partnerName 紧紧相连 ❤️" else "情侣状态：已紧紧相连 ❤️"
            tvBindPartner.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvBindPartner.text = "情侣状态：未绑定 (点击绑定)"
            tvBindPartner.setTextColor(Color.parseColor("#FF5252"))
        }
    }

    private fun showAlreadyBoundInfo() {
        val myUsername = UserPrefs.getUsername(requireContext()) ?: "未知账号"
        val myNickname = UserPrefs.getNickname(requireContext()) ?: "未设置"
        val partner = UserPrefs.getPartnerNickname(requireContext()) ?: "TA"

        val message = "当前账号：$myUsername\n我的昵称：$myNickname\n\n你已经和 $partner 紧紧相连啦 ❤️\n\n祝地久天长白头偕老"

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("温馨提示")
            .setMessage(message)
            .setPositiveButton("我知道了", null)
            .setNeutralButton("修改昵称") { _, _ ->
                if (checkDevSleepIntercept()) return@setNeutralButton
                showUpdateNicknameDialog()
            }
            .show()

        val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

        var isDetectionWindowOpen = false
        var windowOpenTime = 0L
        var comboCount = 0

        positiveBtn.setOnLongClickListener {
            val prefs = requireContext().getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
            val state = prefs.getInt("dev_sleep_state", 0)

            if (state == 1 || state == 2) {
                prefs.edit().putInt("dev_sleep_state", 2).apply()
                Toast.makeText(requireContext(), "深度省电待息屏后解除", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                isDetectionWindowOpen = true
                windowOpenTime = System.currentTimeMillis()
                comboCount = 0
                Toast.makeText(requireContext(), "", Toast.LENGTH_SHORT).show()
            }
            true
        }

        positiveBtn.setOnClickListener {
            val prefs = requireContext().getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
            val state = prefs.getInt("dev_sleep_state", 0)

            if (state == 0 && isDetectionWindowOpen) {
                val now = System.currentTimeMillis()
                if (now - windowOpenTime <= 2000) {
                    comboCount++
                    if (comboCount == 5) {
                        prefs.edit().putInt("dev_sleep_state", 1).apply()
                        Toast.makeText(requireContext(), "已开启深度省电", Toast.LENGTH_SHORT).show()
                        isDetectionWindowOpen = false
                        comboCount = 0
                        dialog.dismiss()
                    }
                    return@setOnClickListener
                } else {
                    isDetectionWindowOpen = false
                    comboCount = 0
                }
            }
            dialog.dismiss()
        }
    }

    private fun showUpdateNicknameDialog() {
        val input = EditText(requireContext()).apply {
            setText(UserPrefs.getNickname(requireContext()))
            hint = "请输入新的昵称 (最多12个字)"
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.TRANSPARENT)
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            maxLines = 1
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("修改我的昵称")
            .setView(input)
            .setPositiveButton("保存更改") { _, _ ->
                val newNick = input.text.toString().trim()
                if (newNick.isEmpty()) {
                    Toast.makeText(requireContext(), "昵称不能为空！", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val myId = UserPrefs.getUserId(requireContext())
                val partnerId = UserPrefs.getPartnerId(requireContext())

                NetworkClient.getApi(requireContext()).updateNickname(userId = myId, nickname = newNick).enqueue(object : Callback<UserResponse> {
                    override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                        if (response.isSuccessful && response.body()?.status == "success") {
                            UserPrefs.saveNickname(requireContext(), newNick)
                            refreshUI()
                            Toast.makeText(requireContext(), "昵称修改成功！", Toast.LENGTH_SHORT).show()

                            if (partnerId > 0 && WebSocketManager.isConnected) {
                                val payload = JSONObject().apply {
                                    put("type", "nickname_changed")
                                    put("new_nickname", newNick)
                                }
                                WebSocketManager.sendMessage(
                                    action = "send_to_partner",
                                    targetId = partnerId,
                                    command = "system_update",
                                    data = payload
                                )
                            }
                        } else {
                            Toast.makeText(requireContext(), "修改失败：${response.body()?.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                        Toast.makeText(requireContext(), "网络错误，修改失败", Toast.LENGTH_SHORT).show()
                    }
                })
            }.setNegativeButton("取消", null).show()
    }

    private fun showLogoutConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("退出账号").setMessage("退出后将清空所有数据并停止守护，确定吗？")
            .setPositiveButton("确定退出") { _, _ -> performLogout() }.setNegativeButton("取消", null).show()
    }

    private fun performLogout() {
        val uid = UserPrefs.getUserId(requireContext())
        val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
        if (uid > 0) {
            NetworkClient.getApi(requireContext()).logout(userId = uid, deviceId = deviceId).enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {}
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {}
            })
        }
        val avatarFile = File(requireContext().filesDir, "widget_avatar.jpg")
        if (avatarFile.exists()) avatarFile.delete()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 🚀 深度清理：清空数据库内所有表（包括聊天记录、定位日志等）
                AppDatabase.getDatabase(requireContext()).locationDao().clearAll() // 保留原有保险逻辑
                AppDatabase.getDatabase(requireContext()).clearAllTables() // 连根拔起
                // 🚀 深度清理：彻底销毁本地存储的聊天多媒体文件
                val mediaDir = File(requireContext().filesDir, "chat_media")
                if (mediaDir.exists()) mediaDir.deleteRecursively()
                // 🚀 深度清理：清空临时缓存区遗留的加密解密碎片
                requireContext().cacheDir.listFiles()?.forEach { it.delete() }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                requireContext().stopService(Intent(requireContext(), LocationService::class.java))
                UserPrefs.clear(requireContext())
                CoupleWidgetProvider.updateAllWidgets(requireContext())
                startActivity(Intent(requireContext(), LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                requireActivity().finish()
            }
        }
    }

    private fun updateRadioHighlight(rg: RadioGroup, checkedId: Int) {
        for (i in 0 until rg.childCount) {
            val rb = rg.getChildAt(i) as? RadioButton ?: continue
            if (rb.id == checkedId) {
                rb.setTextColor(Color.parseColor("#2196F3"))
                rb.setTypeface(null, Typeface.BOLD)
            } else {
                rb.setTextColor(Color.parseColor("#333333"))
                rb.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun showServerSettingDialog() {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_server, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_server_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_custom)
        val rbOfficial = v.findViewById<RadioButton>(R.id.rb_official)
        val et = v.findViewById<EditText>(R.id.et_server_url)
        val layout = v.findViewById<View>(R.id.layout_custom_input)

        et.setText(UserPrefs.getCustomServerUrlRaw(requireContext()))

        rg.setOnCheckedChangeListener { group, id ->
            layout.visibility = if (id == R.id.rb_custom) View.VISIBLE else View.GONE
            updateRadioHighlight(group, id)
        }

        val initialId = if (UserPrefs.isUsingCustomServer(requireContext())) R.id.rb_custom else R.id.rb_official
        rg.check(initialId)
        updateRadioHighlight(rg, initialId)

        v.findViewById<Button>(R.id.btn_test_conn).setOnClickListener {
            var url = et.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "http://$url"
                if (url.contains("/love_api")) url = url.substring(0, url.indexOf("/love_api"))
                if (!url.endsWith("/")) url += "/"
                Thread {
                    try {
                        val response = okhttp3.OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build().newCall(okhttp3.Request.Builder().url("${url}love_api/check.php").build()).execute()
                        activity?.runOnUiThread { Toast.makeText(context, if (response.isSuccessful) "✅ 连通成功" else "⚠️ 异常", Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) { activity?.runOnUiThread { Toast.makeText(context, "❌ 连通失败", Toast.LENGTH_SHORT).show() } }
                }.start()
            }
        }

        MaterialAlertDialogBuilder(requireContext()).setView(v).setPositiveButton("确认保存") { _, _ ->
            val useCustom = rbCustom.isChecked
            val url = et.text.toString().trim()
            if (useCustom != UserPrefs.isUsingCustomServer(requireContext()) || (useCustom && url != UserPrefs.getCustomServerUrlRaw(requireContext()))) {
                AlertDialog.Builder(requireContext()).setTitle("⚠️ 更换服务器警告").setMessage("将清空当前所有本地数据并退出登录。")
                    .setPositiveButton("确定清空并切换") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                // 🚀 深度清理：清空数据库内所有表（包括聊天记录、定位日志等）
                                AppDatabase.getDatabase(requireContext()).locationDao().clearAll()
                                AppDatabase.getDatabase(requireContext()).clearAllTables()
                                // 🚀 深度清理：彻底销毁本地存储的多媒体文件和头像
                                val mediaDir = File(requireContext().filesDir, "chat_media")
                                if (mediaDir.exists()) mediaDir.deleteRecursively()
                                val avatarFile = File(requireContext().filesDir, "widget_avatar.jpg")
                                if (avatarFile.exists()) avatarFile.delete()
                                // 🚀 深度清理：清空临时缓存区碎片
                                requireContext().cacheDir.listFiles()?.forEach { it.delete() }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            withContext(Dispatchers.Main) {
                                requireContext().stopService(Intent(requireContext(), LocationService::class.java))
                                UserPrefs.clear(requireContext())
                                UserPrefs.saveServerConfig(requireContext(), useCustom, url)
                                startActivity(Intent(requireContext(), LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
                                requireActivity().finish()
                            }
                        }
                    }.setNegativeButton("取消", null).show()
            } else UserPrefs.saveServerConfig(requireContext(), useCustom, url)
        }.setNegativeButton("取消", null).show()
    }

    private fun showAMapSettingDialog() {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_amap_config, null)

        val rg = v.findViewById<RadioGroup>(R.id.rg_amap_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_amap_custom)
        val rbOfficial = v.findViewById<RadioButton>(R.id.rb_amap_official)
        val et = v.findViewById<EditText>(R.id.et_amap_key)
        val layout = v.findViewById<View>(R.id.layout_amap_input)

        et.setText(UserPrefs.getCustomAMapKeyRaw(requireContext()))

        rg.setOnCheckedChangeListener { group, id ->
            layout.visibility = if (id == R.id.rb_amap_custom) View.VISIBLE else View.GONE
            updateRadioHighlight(group, id)
        }

        val initialId = if (UserPrefs.isUsingCustomAMap(requireContext())) R.id.rb_amap_custom else R.id.rb_amap_official
        rg.check(initialId)
        updateRadioHighlight(rg, initialId)

        v.findViewById<TextView>(R.id.tv_amap_debug_info).setOnClickListener {
            val container = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
            val addRow = { title: String, txt: String ->
                val tv = TextView(requireContext()).apply { text = "$title: $txt"; textSize = 14f; setTextColor(Color.parseColor("#333333")) }
                val btn = Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply { text = "复制"; setTextColor(Color.parseColor("#2196F3"))
                    setOnClickListener { (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, txt)); Toast.makeText(requireContext(), "$title 已复制", Toast.LENGTH_SHORT).show() }
                }
                container.addView(LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(btn) })
            }
            addRow("包名", "cn.xtay.lovejournal")
            addRow("SHA1", "C8:62:7D:F8:FB:B1:21:1B:E5:A0:04:EA:5B:C0:BF:A9:1E:42:38:DC")
            MaterialAlertDialogBuilder(requireContext()).setTitle("调试信息").setView(container).setPositiveButton("关闭", null).show()
        }
        MaterialAlertDialogBuilder(requireContext()).setView(v).setPositiveButton("确认配置") { _, _ -> UserPrefs.saveAMapConfig(requireContext(), rbCustom.isChecked, et.text.toString().trim()) }.setNegativeButton("取消", null).show()
    }

    private fun applyHideRecents(en: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { (requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).appTasks?.firstOrNull()?.setExcludeFromRecents(en) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun manualCheckUpdate() {
        Toast.makeText(requireContext(), "正在检查更新...", Toast.LENGTH_SHORT).show()
        NetworkClient.getApi(requireContext()).checkAppUpdate().enqueue(object : Callback<okhttp3.ResponseBody> {
            override fun onResponse(call: Call<okhttp3.ResponseBody>, response: Response<okhttp3.ResponseBody>) {
                if (response.isSuccessful && isAdded) {
                    try {
                        val jsonString = response.body()?.string() ?: return
                        val jsonObject = org.json.JSONObject(jsonString)
                        val serverCode = jsonObject.optInt("v_code", 0)
                        val downloadUrl = jsonObject.optString("url", "")
                        if (serverCode > 0 && downloadUrl.isNotEmpty()) {
                            updateManager.manualCheckShow(serverCode, jsonObject.optString("v_name", ""), jsonObject.optString("log", ""), downloadUrl)
                        } else Toast.makeText(requireContext(), "未检测到新版本", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) { Toast.makeText(requireContext(), "解析失败", Toast.LENGTH_SHORT).show() }
                } else if (isAdded) Toast.makeText(requireContext(), "服务器未响应", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(call: Call<okhttp3.ResponseBody>, t: Throwable) {
                if (isAdded) Toast.makeText(requireContext(), "网络失败", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showNotifManageDialog() {
        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 40)
        }
        scroll.addView(layout)

        fun createIn(label: String, hint: String, current: String): EditText {
            layout.addView(TextView(requireContext()).apply {
                text = label
                setTextColor(Color.GRAY)
                textSize = 12f
            })
            val et = EditText(requireContext()).apply {
                this.hint = hint
                setHintTextColor(Color.LTGRAY)
                if (current.isNotEmpty()) setText(current)
                maxLines = 1
            }
            layout.addView(et)
            layout.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(1, 40)
            })
            return et
        }

        val etTitle = createIn("通知总标题：", "Android System", UserPrefs.getNotifTitle(requireContext()))
        val etNormal = createIn("正常运行状态：", "网络优化模式", UserPrefs.getNotifNormal(requireContext()))
        val etOffline = createIn("断网连接状态：", "等待网络连接", UserPrefs.getNotifOffline(requireContext()))
        val etMoving = createIn("位移/基站切换状态：", "网络切换中", UserPrefs.getNotifMoving(requireContext()))
        val etError = createIn("信号盲区/异常状态：", "网络异常", UserPrefs.getNotifError(requireContext()))

        val etNewMsg = createIn("收到新消息：", "系统缓存待清理", UserPrefs.getNotifNewMsg(requireContext()))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("通知横幅管理")
            .setView(scroll)
            .setPositiveButton("确认保存") { _, _ ->
                UserPrefs.saveNotifTitle(requireContext(), etTitle.text.toString().trim())
                UserPrefs.saveNotifNormal(requireContext(), etNormal.text.toString().trim())
                UserPrefs.saveNotifOffline(requireContext(), etOffline.text.toString().trim())
                UserPrefs.saveNotifMoving(requireContext(), etMoving.text.toString().trim())
                UserPrefs.saveNotifError(requireContext(), etError.text.toString().trim())
                UserPrefs.saveNotifNewMsg(requireContext(), etNewMsg.text.toString().trim())

                requireContext().startService(Intent(requireContext(), LocationService::class.java).apply {
                    action = "ACTION_UPDATE_NOTIF"
                })
                Toast.makeText(requireContext(), "已更新配置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLocationLogsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_location_logs, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rv_logs)
        val cbSelectAll = dialogView.findViewById<CheckBox>(R.id.cb_select_all)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_selected)
        val btnClear = dialogView.findViewById<Button>(R.id.btn_clear_all)

        rv.layoutManager = LinearLayoutManager(requireContext())
        lateinit var adapter: cn.xtay.lovejournal.ui.LocationLogAdapter

        val updateUi = {
            val count = adapter.selectedItems.size
            btnDelete.visibility = if (count > 0) View.VISIBLE else View.GONE
            btnDelete.text = "删除选中($count)"
            cbSelectAll.setOnCheckedChangeListener(null)
            cbSelectAll.isChecked = (count > 0 && count == adapter.itemCount)
            cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) adapter.selectAll() else adapter.clearSelection()
                val newCount = adapter.selectedItems.size
                btnDelete.visibility = if (newCount > 0) View.VISIBLE else View.GONE
                btnDelete.text = "删除选中($newCount)"
            }
        }

        adapter = cn.xtay.lovejournal.ui.LocationLogAdapter(emptyList()) { updateUi() }
        rv.adapter = adapter

        lifecycleScope.launch(Dispatchers.IO) {
            val logs = AppDatabase.getDatabase(requireContext()).locationDao().getRecentLogs()
            withContext(Dispatchers.Main) { adapter.updateData(logs); updateUi() }
        }

        MaterialAlertDialogBuilder(requireContext()).setView(dialogView).setPositiveButton("关闭", null).show()

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) adapter.selectAll() else adapter.clearSelection()
            updateUi()
        }

        btnDelete.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).locationDao().deleteLogs(adapter.selectedItems.toList())
                val newLogs = AppDatabase.getDatabase(requireContext()).locationDao().getRecentLogs()
                withContext(Dispatchers.Main) { adapter.updateData(newLogs); adapter.clearSelection(); updateUi() }
            }
        }

        btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("确认清空").setMessage("不可撤销")
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).locationDao().clearAll()
                        withContext(Dispatchers.Main) { adapter.updateData(emptyList()); adapter.clearSelection(); updateUi() }
                    }
                }.setNegativeButton("取消", null).show()
        }
    }

    private fun showBindDialog() {
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 40, 64, 0) }
        val etNickname = EditText(requireContext()).apply { hint = "填写你的昵称"; maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(12)) }
        val etCode = EditText(requireContext()).apply { hint = "输入 TA 的 6 位邀请码"; maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(6)); isAllCaps = true }
        layout.addView(etNickname)
        layout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })
        layout.addView(etCode)

        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("心动连接").setMessage("你的专属邀请码：${UserPrefs.getCoupleCode(requireContext())}")
            .setView(layout).setPositiveButton("确认绑定", null).setNegativeButton("取消", null).show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase()
            if (nickname.isEmpty() || code.length < 6) { Toast.makeText(requireContext(), "请输入完整", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

            NetworkClient.getApi(requireContext()).bind(userId = UserPrefs.getUserId(requireContext()), nickname = nickname, targetCode = code)
                .enqueue(object : Callback<UserResponse> {
                    override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                        val res = response.body()
                        if (res?.status == "success") {
                            UserPrefs.saveNickname(requireContext(), nickname)
                            UserPrefs.savePartnerNickname(requireContext(), res.partner_nickname ?: "TA")
                            UserPrefs.savePartnerId(requireContext(), res.partner_id ?: -1)
                            dialog.dismiss(); refreshUI(); Toast.makeText(requireContext(), "绑定成功！", Toast.LENGTH_SHORT).show()
                        } else Toast.makeText(requireContext(), res?.message ?: "邀请码无效", Toast.LENGTH_SHORT).show()
                    }
                    override fun onFailure(call: Call<UserResponse>, t: Throwable) { Toast.makeText(requireContext(), "网络错误", Toast.LENGTH_SHORT).show() }
                })
        }
    }

    private fun checkLatestStatus() {
        val uid = UserPrefs.getUserId(requireContext())
        if (uid == -1) return
        NetworkClient.getApi(requireContext()).getStatus(userId = uid, partnerId = UserPrefs.getPartnerId(requireContext()))
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val res = response.body()
                    if (res?.status == "success") {
                        res.my_nickname?.let { UserPrefs.saveNickname(requireContext(), it) }
                        res.partner_nickname?.let { UserPrefs.savePartnerNickname(requireContext(), it) }
                        if ((res.partner_id ?: 0) > 0 && res.partner_id != UserPrefs.getPartnerId(requireContext())) UserPrefs.savePartnerId(requireContext(), res.partner_id!!)
                        refreshUI()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {}
            })
    }
}