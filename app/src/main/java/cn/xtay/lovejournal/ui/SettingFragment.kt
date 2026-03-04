package cn.xtay.lovejournal.ui

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import cn.xtay.lovejournal.service.LocationService
import cn.xtay.lovejournal.util.UserPrefs
import cn.xtay.lovejournal.util.UpdateManager // 💖 新增引用
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.TimeUnit

class SettingFragment : Fragment() {

    private lateinit var tvAccountName: TextView
    private lateinit var tvBindPartner: TextView
    private lateinit var cardAccountInfo: MaterialCardView
    private lateinit var btnLogout: Button
    private lateinit var switchHideRecents: MaterialSwitch

    // 💖 新增：更新管理器
    private val updateManager by lazy { UpdateManager(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_setting, container, false)

        tvAccountName = view.findViewById(R.id.tv_account_name)
        tvBindPartner = view.findViewById(R.id.tv_bind_partner)
        cardAccountInfo = view.findViewById(R.id.card_account_info)
        switchHideRecents = view.findViewById(R.id.switch_hide_recents)
        val tvServerManage = view.findViewById<TextView>(R.id.tv_server_manage)
        val tvAMapManage = view.findViewById<TextView>(R.id.tv_amap_manage)

        val tvLocationLogs = view.findViewById<TextView>(R.id.tv_location_logs)

        // 💖 绑定通知管理按钮
        val tvNotifManage = view.findViewById<TextView>(R.id.tv_notif_manage)

        // 💖 核心新增：绑定检查更新按钮
        val tvCheckUpdate = view.findViewById<TextView>(R.id.tv_check_update)

        btnLogout = view.findViewById(R.id.btn_logout)

        refreshUI()
        checkLatestStatus()

        cardAccountInfo.setOnClickListener {
            if (UserPrefs.getPartnerId(requireContext()) <= 0) {
                showBindDialog()
            } else {
                showAlreadyBoundInfo()
            }
        }

        switchHideRecents.isChecked = UserPrefs.isHideRecentsEnabled(requireContext())
        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            UserPrefs.setHideRecentsEnabled(requireContext(), isChecked)
            applyHideRecents(isChecked)
        }

        tvServerManage.setOnClickListener { showServerSettingDialog() }
        tvAMapManage.setOnClickListener { showAMapSettingDialog() }

        tvLocationLogs?.setOnClickListener { showLocationLogsDialog() }

        // 💖 监听通知管理点击
        tvNotifManage?.setOnClickListener { showNotifManageDialog() }

        // 💖 监听手动检查更新点击
        tvCheckUpdate?.setOnClickListener { manualCheckUpdate() }

        btnLogout.setOnClickListener { showLogoutConfirmDialog() }

        return view
    }

    /**
     * 💖 核心新增：手动触发版本检查逻辑
     */
    private fun manualCheckUpdate() {
        val fullCommand = UserPrefs.getRemoteCommand(requireContext())
        // 尝试解析最后一次收到的 OTA 指令
        if (fullCommand.startsWith("ota_update|")) {
            val parts = fullCommand.split("|")
            if (parts.size >= 5) {
                try {
                    val serverCode = parts[1].toInt()
                    val versionName = parts[2]
                    val updateLog = parts[3].replace("\\n", "\n")
                    val downloadUrl = parts[4]

                    // 调用手动检查方法，跳过忽略逻辑直接弹窗
                    updateManager.manualCheckShow(serverCode, versionName, updateLog, downloadUrl)
                    return
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        // 如果没有 OTA 指令或解析失败
        Toast.makeText(requireContext(), "当前已是最新版本", Toast.LENGTH_SHORT).show()
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
                setTextColor(android.graphics.Color.GRAY)
                textSize = 12f
            })
            val et = EditText(requireContext()).apply {
                this.hint = hint
                setHintTextColor(android.graphics.Color.LTGRAY)
                if (current.isNotEmpty()) setText(current)
                maxLines = 1
            }
            layout.addView(et)
            layout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
            return et
        }

        val etTitle = createIn("通知总标题：", "情侣手记实时守护中", UserPrefs.getNotifTitle(requireContext()))
        val etNormal = createIn("正常运行状态：", "守护中：网络正常", UserPrefs.getNotifNormal(requireContext()))
        val etOffline = createIn("断网连接状态：", "无网络连接，已暂停后台同步", UserPrefs.getNotifOffline(requireContext()))
        val etMoving = createIn("位移/基站切换状态：", "🚶 移动基站切换中...", UserPrefs.getNotifMoving(requireContext()))
        val etError = createIn("信号盲区/异常状态：", "⚠️ 信号盲区/波动", UserPrefs.getNotifError(requireContext()))

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("通知横幅管理")
            .setView(scroll)
            .setPositiveButton("确认保存") { _, _ ->
                UserPrefs.saveNotifTitle(requireContext(), etTitle.text.toString().trim())
                UserPrefs.saveNotifNormal(requireContext(), etNormal.text.toString().trim())
                UserPrefs.saveNotifOffline(requireContext(), etOffline.text.toString().trim())
                UserPrefs.saveNotifMoving(requireContext(), etMoving.text.toString().trim())
                UserPrefs.saveNotifError(requireContext(), etError.text.toString().trim())

                val intent = Intent(requireContext(), LocationService::class.java).apply {
                    action = "ACTION_UPDATE_NOTIF"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
                }
                Toast.makeText(requireContext(), "通知文案已更新并生效", Toast.LENGTH_SHORT).show()
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

        lateinit var adapter: LocationLogAdapter

        val updateUi = {
            val count = adapter.selectedItems.size
            val total = adapter.itemCount
            btnDelete.visibility = if (count > 0) View.VISIBLE else View.GONE
            btnDelete.text = "删除选中($count)"

            cbSelectAll.setOnCheckedChangeListener(null)
            cbSelectAll.isChecked = (count > 0 && count == total)
            cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) adapter.selectAll() else adapter.clearSelection()
                val newCount = adapter.selectedItems.size
                btnDelete.visibility = if (newCount > 0) View.VISIBLE else View.GONE
                btnDelete.text = "删除选中($newCount)"
            }
        }

        adapter = LocationLogAdapter(emptyList()) { _ ->
            updateUi()
        }
        rv.adapter = adapter

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            val logs = db.locationDao().getRecentLogs()
            withContext(Dispatchers.Main) {
                adapter.updateData(logs)
                updateUi()
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setPositiveButton("关闭", null)
            .show()

        cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) adapter.selectAll() else adapter.clearSelection()
            updateUi()
        }

        btnDelete.setOnClickListener {
            val toDelete = adapter.selectedItems.toList()
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext())
                db.locationDao().deleteLogs(toDelete)
                val newLogs = db.locationDao().getRecentLogs()
                withContext(Dispatchers.Main) {
                    adapter.updateData(newLogs)
                    adapter.clearSelection()
                    updateUi()
                    Toast.makeText(requireContext(), "已删除所选记录", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("确认清空")
                .setMessage("确定要清空本地所有定位记录吗？此操作不可撤销。")
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).locationDao().clearAll()
                        withContext(Dispatchers.Main) {
                            adapter.updateData(emptyList())
                            adapter.clearSelection()
                            updateUi()
                            Toast.makeText(requireContext(), "已清空所有定位日志", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.setNegativeButton("取消", null).show()
        }
    }

    private fun refreshUI() {
        val user = UserPrefs.getNickname(requireContext()) ?: UserPrefs.getUsername(requireContext())
        tvAccountName.text = "当前账号：${user ?: "未知"}"

        val partnerName = UserPrefs.getPartnerNickname(requireContext())
        if (UserPrefs.getPartnerId(requireContext()) > 0) {
            tvBindPartner.text = if (partnerName != null) "已与 $partnerName 紧紧相连 ❤️" else "情侣状态：已紧紧相连 ❤️"
            tvBindPartner.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            tvBindPartner.text = "情侣状态：未绑定 (点击绑定)"
            tvBindPartner.setTextColor(android.graphics.Color.parseColor("#FF5252"))
        }
    }

    private fun showServerSettingDialog() {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_server, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_server_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_custom)
        val rbOfficial = v.findViewById<RadioButton>(R.id.rb_official)
        val et = v.findViewById<EditText>(R.id.et_server_url)
        val layout = v.findViewById<View>(R.id.layout_custom_input)
        val btnTest = v.findViewById<Button>(R.id.btn_test_conn)

        et.setText(UserPrefs.getCustomServerUrlRaw(requireContext()))

        if (UserPrefs.isUsingCustomServer(requireContext())) {
            rbCustom.isChecked = true
            layout.visibility = View.VISIBLE
        } else {
            rbOfficial.isChecked = true
            layout.visibility = View.GONE
        }

        rg.setOnCheckedChangeListener { _, id ->
            layout.visibility = if (id == R.id.rb_custom) View.VISIBLE else View.GONE
        }

        btnTest.setOnClickListener {
            val url = formatUrl(et.text.toString())
            if (url.isNotEmpty()) testConn(url)
        }

        MaterialAlertDialogBuilder(requireContext()).setView(v)
            .setPositiveButton("确认保存") { _, _ ->
                val useCustom = rbCustom.isChecked
                val url = et.text.toString().trim()
                val oldUrl = UserPrefs.getCustomServerUrlRaw(requireContext())
                val oldUseCustom = UserPrefs.isUsingCustomServer(requireContext())

                if (useCustom != oldUseCustom || (useCustom && url != oldUrl)) {
                    showVanishWarning(useCustom, url)
                } else {
                    UserPrefs.saveServerConfig(requireContext(), useCustom, url)
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showVanishWarning(useCustom: Boolean, newUrl: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ 更换服务器警告")
            .setMessage("更换服务器将清空当前所有本地数据并退出登录。在新服务器上，如果账号不存在将自动注册，但历史轨迹和经期数据无法跨服迁移。确定切换吗？")
            .setPositiveButton("确定清空并切换") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext()).locationDao().clearAll()

                    withContext(Dispatchers.Main) {
                        requireContext().stopService(Intent(requireContext(), LocationService::class.java))
                        UserPrefs.clear(requireContext())
                        UserPrefs.saveServerConfig(requireContext(), useCustom, newUrl)
                        val intent = Intent(requireContext(), LoginActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        requireActivity().finish()
                    }
                }
            }
            .setNegativeButton("取消", null).show()
    }

    private fun testConn(baseUrl: String) {
        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
                val request = okhttp3.Request.Builder().url("${baseUrl}love_api/check.php").build()
                val response = client.newCall(request).execute()
                activity?.runOnUiThread {
                    if (response.isSuccessful) Toast.makeText(context, "✅ 连通成功", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(context, "⚠️ 异常: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { Toast.makeText(context, "❌ 连通失败", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun formatUrl(input: String): String {
        var url = input.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http")) url = "http://$url"
        if (url.contains("/love_api")) url = url.substring(0, url.indexOf("/love_api"))
        if (!url.endsWith("/")) url += "/"
        return url
    }

    private fun showBindDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 0)
        }

        val etNickname = EditText(requireContext()).apply {
            hint = "填写你的昵称（设置后不可更改）"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
        }

        val etCode = EditText(requireContext()).apply {
            hint = "输入 TA 的 6 位邀请码"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            isAllCaps = true
        }

        layout.addView(etNickname)
        layout.addView(View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })
        layout.addView(etCode)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("心动连接")
            .setMessage("你的专属邀请码：${UserPrefs.getCoupleCode(requireContext())}")
            .setView(layout)
            .setPositiveButton("确认绑定", null)
            .setNegativeButton("取消", null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase()

            if (nickname.isEmpty()) {
                Toast.makeText(requireContext(), "请输入你的昵称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (code.length < 6) {
                Toast.makeText(requireContext(), "请输入完整的 6 位邀请码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            doBind(nickname, code, dialog)
        }
    }

    private fun doBind(nickname: String, code: String, dialog: AlertDialog) {
        val myId = UserPrefs.getUserId(requireContext())
        NetworkClient.getApi(requireContext()).bind(userId = myId, nickname = nickname, targetCode = code)
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val res = response.body()
                    if (res?.status == "success") {
                        UserPrefs.saveNickname(requireContext(), nickname)
                        UserPrefs.savePartnerNickname(requireContext(), res.partner_nickname ?: "TA")
                        UserPrefs.savePartnerId(requireContext(), res.partner_id ?: -1)
                        dialog.dismiss()
                        refreshUI()
                        Toast.makeText(requireContext(), "绑定成功！", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), res?.message ?: "邀请码无效", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    Toast.makeText(requireContext(), "网络错误", Toast.LENGTH_SHORT).show()
                }
            })
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

                        if ((res.partner_id ?: 0) > 0 && res.partner_id != UserPrefs.getPartnerId(requireContext())) {
                            UserPrefs.savePartnerId(requireContext(), res.partner_id!!)
                        }
                        refreshUI()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {}
            })
    }

    private fun showAlreadyBoundInfo() {
        val myUsername = UserPrefs.getUsername(requireContext()) ?: "未知账号"
        val partner = UserPrefs.getPartnerNickname(requireContext()) ?: "TA"

        val message = "当前账号：$myUsername\n\n" +
                "你已经和 $partner 紧紧相连啦 ❤️\n\n" +
                "祝地久天长白头偕老"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("温馨提示")
            .setMessage(message)
            .setPositiveButton("我知道了", null).show()
    }

    private fun showLogoutConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("退出账号").setMessage("退出后将停止守护，确定吗？")
            .setPositiveButton("确定退出") { _, _ -> performLogout() }.setNegativeButton("取消", null).show()
    }

    private fun performLogout() {
        val uid = UserPrefs.getUserId(requireContext())
        val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)

        if (uid > 0) {
            NetworkClient.getApi(requireContext()).logout(userId = uid, deviceId = deviceId)
                .enqueue(object : Callback<UserResponse> {
                    override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {}
                    override fun onFailure(call: Call<UserResponse>, t: Throwable) {}
                })
        }

        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).locationDao().clearAll()
            withContext(Dispatchers.Main) {
                requireContext().stopService(Intent(requireContext(), LocationService::class.java))
                UserPrefs.clear(requireContext())
                val intent = Intent(requireContext(), LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
            }
        }
    }

    private fun showAMapSettingDialog() {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_amap_config, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_amap_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_amap_custom)
        val rbOfficial = v.findViewById<RadioButton>(R.id.rb_amap_official)
        val et = v.findViewById<EditText>(R.id.et_amap_key)
        val layout = v.findViewById<View>(R.id.layout_amap_input)
        val tvDebugInfo = v.findViewById<TextView>(R.id.tv_amap_debug_info)

        et.setText(UserPrefs.getCustomAMapKeyRaw(requireContext()))
        if (UserPrefs.isUsingCustomAMap(requireContext())) { rbCustom.isChecked = true; layout.visibility = View.VISIBLE }
        else { rbOfficial.isChecked = true; layout.visibility = View.GONE }
        rg.setOnCheckedChangeListener { _, id -> layout.visibility = if (id == R.id.rb_amap_custom) View.VISIBLE else View.GONE }

        tvDebugInfo.setOnClickListener { showDebugInfoDialog() }

        MaterialAlertDialogBuilder(requireContext()).setView(v).setPositiveButton("确认配置") { _, _ ->
            UserPrefs.saveAMapConfig(requireContext(), rbCustom.isChecked, et.text.toString().trim())
        }.setNegativeButton("取消", null).show()
    }

    private fun showDebugInfoDialog() {
        val packageName = "cn.xtay.lovejournal"
        val sha1 = "98:D5:0F:99:D8:76:5B:55:95:AF:A7:04:3F:E1:DF:3E:72:5D:1D:E6"

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvPkg = TextView(requireContext()).apply {
            text = "包名: $packageName"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }
        val btnCopyPkg = Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply {
            text = "复制包名"
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            setOnClickListener { copyToClipboard("包名", packageName) }
        }
        val layoutPkg = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(tvPkg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnCopyPkg)
        }

        val tvSha1 = TextView(requireContext()).apply {
            text = "SHA1: $sha1"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
        }
        val btnCopySha1 = Button(requireContext(), null, android.R.attr.borderlessButtonStyle).apply {
            text = "复制 SHA1"
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            setOnClickListener { copyToClipboard("SHA1", sha1) }
        }
        val layoutSha1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(tvSha1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnCopySha1)
        }

        container.addView(layoutPkg)
        container.addView(layoutSha1)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("调试信息")
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "$label 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun applyHideRecents(en: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                val am = requireActivity().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks?.firstOrNull()?.setExcludeFromRecents(en)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}