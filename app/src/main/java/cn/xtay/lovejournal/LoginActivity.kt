package cn.xtay.lovejournal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.UserPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val etUser = findViewById<TextInputEditText>(R.id.et_username)
        val etPass = findViewById<TextInputEditText>(R.id.et_password)
        val btnLogin = findViewById<MaterialButton>(R.id.btn_login)
        val tvCfgServer = findViewById<TextView>(R.id.tv_cfg_server)
        val tvCfgAmap = findViewById<TextView>(R.id.tv_cfg_amap)

        btnLogin.setOnClickListener {
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "账号或密码不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 获取设备唯一标识
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            NetworkClient.getApi(this).login(username = user, password = pass, deviceId = deviceId)
                .enqueue(object : Callback<UserResponse> {
                    override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                        val res = response.body() ?: return
                        if (res.status == "success") {
                            val currentUserId = res.user_id ?: -1
                            val currentPartnerId = res.partner_id ?: -1
                            val currentCode = res.couple_code ?: ""

                            UserPrefs.saveLoginInfo(
                                context = this@LoginActivity,
                                id = currentUserId,
                                name = user,
                                code = currentCode,
                                partnerId = currentPartnerId
                            )

                            // 💖 核心新增：接住服务器下发的昵称数据并保存到本地
                            res.my_nickname?.let { UserPrefs.saveNickname(this@LoginActivity, it) }
                            res.partner_nickname?.let { UserPrefs.savePartnerNickname(this@LoginActivity, it) }

                            if (currentPartnerId > 0) gotoMain() else showBindDialog()
                        } else {
                            // 🛑 如果被别的设备占用，会在这里直接弹出后台返回的错误提示！
                            Toast.makeText(this@LoginActivity, res.message ?: "登录失败", Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                        Toast.makeText(this@LoginActivity, "连接至服务器失败，请检查配置", Toast.LENGTH_SHORT).show()
                    }
                })
        }

        tvCfgServer.setOnClickListener { showServerSettingDialog() }
        tvCfgAmap.setOnClickListener { showAMapSettingDialog() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { }
        })
    }

    private fun showServerSettingDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_custom_server, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_server_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_custom)
        val rbOfficial = v.findViewById<RadioButton>(R.id.rb_official)
        val et = v.findViewById<EditText>(R.id.et_server_url)
        val layout = v.findViewById<View>(R.id.layout_custom_input)
        val btnTest = v.findViewById<Button>(R.id.btn_test_conn)

        et.setText(UserPrefs.getCustomServerUrlRaw(this))

        if (UserPrefs.isUsingCustomServer(this)) {
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
            if (url.isNotEmpty()) testConn(url) else Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show()
        }

        MaterialAlertDialogBuilder(this).setView(v)
            .setPositiveButton("确认配置") { _, _ ->
                UserPrefs.saveServerConfig(this, rbCustom.isChecked, et.text.toString().trim())
                Toast.makeText(this, "服务器配置已保存", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun testConn(baseUrl: String) {
        Thread {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url("${baseUrl}love_api/check.php").build()
                val response = client.newCall(request).execute()
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this, "✅ 连通成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "⚠️ 服务器响应异常: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "❌ 连通失败，请检查地址", Toast.LENGTH_SHORT).show() }
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

    private fun showAMapSettingDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_amap_config, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_amap_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_amap_custom)
        val rbOfficial = v.findViewById<RadioButton>(R.id.rb_amap_official)
        val et = v.findViewById<EditText>(R.id.et_amap_key)
        val layout = v.findViewById<View>(R.id.layout_amap_input)
        val tvDebugInfo = v.findViewById<TextView>(R.id.tv_amap_debug_info)

        et.setText(UserPrefs.getCustomAMapKeyRaw(this))
        if (UserPrefs.isUsingCustomAMap(this)) {
            rbCustom.isChecked = true
            layout.visibility = View.VISIBLE
        } else {
            rbOfficial.isChecked = true
            layout.visibility = View.GONE
        }

        rg.setOnCheckedChangeListener { _, id ->
            layout.visibility = if (id == R.id.rb_amap_custom) View.VISIBLE else View.GONE
        }

        tvDebugInfo.setOnClickListener { showDebugInfoDialog() }

        MaterialAlertDialogBuilder(this).setView(v)
            .setPositiveButton("确认配置") { _, _ ->
                UserPrefs.saveAMapConfig(this, rbCustom.isChecked, et.text.toString().trim())
                Toast.makeText(this, "地图配置已更新", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun showDebugInfoDialog() {
        val packageName = "cn.xtay.lovejournal"
        val sha1 = "98:D5:0F:99:D8:76:5B:55:95:AF:A7:04:3F:E1:DF:3E:72:5D:1D:E6"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val tvPkg = TextView(this).apply {
            text = "包名: $packageName"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }
        val btnCopyPkg = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "复制包名"
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            setOnClickListener { copyToClipboard("包名", packageName) }
        }
        val layoutPkg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(tvPkg, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnCopyPkg)
        }

        val tvSha1 = TextView(this).apply {
            text = "SHA1: $sha1"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
        }
        val btnCopySha1 = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "复制 SHA1"
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            setOnClickListener { copyToClipboard("SHA1", sha1) }
        }
        val layoutSha1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(tvSha1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnCopySha1)
        }

        container.addView(layoutPkg)
        container.addView(layoutSha1)

        MaterialAlertDialogBuilder(this)
            .setTitle("调试信息")
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label 已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    // 💖 核心新增：同步了 SettingFragment 中的双输入（昵称+邀请码）弹窗样式
    private fun showBindDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 0)
        }

        val tvHintCode = TextView(this).apply {
            text = "你的专属邀请码：${UserPrefs.getCoupleCode(this@LoginActivity)}\n绑定后即可开启实时守护功能"
            setTextColor(android.graphics.Color.parseColor("#757575"))
            textSize = 14f
            setPadding(0, 0, 0, 40)
        }

        val etNickname = EditText(this).apply {
            hint = "填写你的昵称"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
        }

        val tvNicknameHint = TextView(this).apply {
            text = "建议：使用昵称或真实姓名（设置后不可修改）"
            setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
            textSize = 12f
            setPadding(0, 8, 0, 40)
        }

        val etCode = EditText(this).apply {
            hint = "输入 TA 的 6 位邀请码"
            maxLines = 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            isAllCaps = true
        }

        layout.addView(tvHintCode)
        layout.addView(etNickname)
        layout.addView(tvNicknameHint)
        layout.addView(etCode)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("心动连接")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("立即绑定", null) // 设为 null，防止点击空输入时直接关闭
            .setNegativeButton("稍后跳过") { _, _ -> gotoMain() }
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase()

            if (nickname.isEmpty()) {
                Toast.makeText(this, "请填写自己的昵称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (code.isEmpty() || code.length < 6) {
                Toast.makeText(this, "请填写完整的 6 位邀请码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            performBind(nickname, code, dialog)
        }
    }

    // 💖 核心新增：带上昵称一起去绑定
    private fun performBind(nickname: String, code: String, dialog: androidx.appcompat.app.AlertDialog) {
        val uid = UserPrefs.getUserId(this)
        NetworkClient.getApi(this).bind(userId = uid, nickname = nickname, targetCode = code)
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val res = response.body() ?: return
                    if (res.status == "success") {
                        // 绑定成功，把两人的昵称都存下来
                        UserPrefs.saveNickname(this@LoginActivity, nickname)
                        UserPrefs.savePartnerNickname(this@LoginActivity, res.partner_nickname ?: "TA")
                        UserPrefs.savePartnerId(this@LoginActivity, res.partner_id ?: -1)

                        dialog.dismiss()
                        gotoMain()
                    } else {
                        Toast.makeText(this@LoginActivity, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    Toast.makeText(this@LoginActivity, "网络错误，请稍后再试", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun gotoMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}