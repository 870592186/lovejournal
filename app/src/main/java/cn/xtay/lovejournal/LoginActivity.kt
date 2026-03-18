package cn.xtay.lovejournal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.UserPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

        findViewById<MaterialButton>(R.id.btn_login).setOnClickListener {
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "账号或密码不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performLogin(user, pass)
        }

        findViewById<TextView>(R.id.tv_cfg_server).setOnClickListener { showServerSettingDialog() }
        findViewById<TextView>(R.id.tv_cfg_amap).setOnClickListener { showAMapSettingDialog() }

        // 禁用返回键，防止意外退出登录流程
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    private fun performLogin(user: String, pass: String) {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        NetworkClient.getApi(this).login(username = user, password = pass, deviceId = deviceId)
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val res = response.body() ?: return
                    if (res.status == "success") {
                        val currentPartnerId = res.partner_id ?: -1

                        UserPrefs.saveLoginInfo(
                            this@LoginActivity, res.user_id ?: -1, user,
                            res.couple_code ?: "", currentPartnerId
                        )

                        // 💖 接住服务器下发的昵称数据并保存
                        res.my_nickname?.let { UserPrefs.saveNickname(this@LoginActivity, it) }
                        res.partner_nickname?.let { UserPrefs.savePartnerNickname(this@LoginActivity, it) }

                        if (currentPartnerId > 0) gotoMain() else showBindDialog()
                    } else {
                        // 🛑 被占用或密码错误，直接弹后台提示
                        Toast.makeText(this@LoginActivity, res.message ?: "登录失败", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                    Toast.makeText(this@LoginActivity, "连接至服务器失败，请检查配置", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showServerSettingDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_custom_server, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_server_choice)
        val rbCustom = v.findViewById<RadioButton>(R.id.rb_custom)
        val et = v.findViewById<EditText>(R.id.et_server_url)
        val layout = v.findViewById<View>(R.id.layout_custom_input)

        et.setText(UserPrefs.getCustomServerUrlRaw(this))
        if (UserPrefs.isUsingCustomServer(this)) {
            rbCustom.isChecked = true
            layout.visibility = View.VISIBLE
        }
        rg.setOnCheckedChangeListener { _, id -> layout.visibility = if (id == R.id.rb_custom) View.VISIBLE else View.GONE }

        v.findViewById<Button>(R.id.btn_test_conn).setOnClickListener {
            val url = formatUrl(et.text.toString())
            if (url.isNotEmpty()) testConn(url) else Toast.makeText(this, "请输入地址", Toast.LENGTH_SHORT).show()
        }

        MaterialAlertDialogBuilder(this).setView(v).setPositiveButton("确认配置") { _, _ ->
            UserPrefs.saveServerConfig(this, rbCustom.isChecked, et.text.toString().trim())
            Toast.makeText(this, "服务器配置已保存", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("取消", null).show()
    }

    // 💖 核心净化：使用协程替代陈旧的 Thread 屎山，防内存泄漏
    private fun testConn(baseUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
                val request = Request.Builder().url("${baseUrl}love_api/check.php").build()
                val response = client.newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) Toast.makeText(this@LoginActivity, "✅ 连通成功", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@LoginActivity, "⚠️ 服务器响应异常: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@LoginActivity, "❌ 连通失败，请检查地址", Toast.LENGTH_SHORT).show() }
            }
        }
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
        val et = v.findViewById<EditText>(R.id.et_amap_key)
        val layout = v.findViewById<View>(R.id.layout_amap_input)

        et.setText(UserPrefs.getCustomAMapKeyRaw(this))
        if (UserPrefs.isUsingCustomAMap(this)) { rbCustom.isChecked = true; layout.visibility = View.VISIBLE }
        rg.setOnCheckedChangeListener { _, id -> layout.visibility = if (id == R.id.rb_amap_custom) View.VISIBLE else View.GONE }
        v.findViewById<TextView>(R.id.tv_amap_debug_info).setOnClickListener { showDebugInfoDialog() }

        MaterialAlertDialogBuilder(this).setView(v).setPositiveButton("确认配置") { _, _ ->
            UserPrefs.saveAMapConfig(this, rbCustom.isChecked, et.text.toString().trim())
            Toast.makeText(this, "地图配置已更新", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("取消", null).show()
    }

    private fun showDebugInfoDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
        val addRow = { title: String, txt: String ->
            val tv = TextView(this).apply { text = "$title: $txt"; textSize = 14f; setTextColor(Color.parseColor("#333333")) }
            val btn = Button(this, null, android.R.attr.borderlessButtonStyle).apply { text = "复制"; setTextColor(Color.parseColor("#2196F3"))
                setOnClickListener { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, txt)); Toast.makeText(this@LoginActivity, "$title 已复制", Toast.LENGTH_SHORT).show() }
            }
            container.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(btn) })
        }
        addRow("包名", "cn.xtay.lovejournal")
        addRow("SHA1", "98:D5:0F:99:D8:76:5B:55:95:AF:A7:04:3F:E1:DF:3E:72:5D:1D:E6")
        MaterialAlertDialogBuilder(this).setTitle("调试信息").setView(container).setPositiveButton("关闭", null).show()
    }

    private fun showBindDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(64, 40, 64, 0) }

        layout.addView(TextView(this).apply {
            text = "你的专属邀请码：${UserPrefs.getCoupleCode(this@LoginActivity)}\n绑定后即可开启实时守护功能"
            setTextColor(Color.parseColor("#757575")); textSize = 14f; setPadding(0, 0, 0, 40)
        })

        val etNickname = EditText(this).apply { hint = "填写你的专属昵称"; maxLines = 1; filters = arrayOf(InputFilter.LengthFilter(12)) }
        layout.addView(etNickname)

        // 💖 修正误导文案：去掉了“不可修改”的提示
        layout.addView(TextView(this).apply {
            text = "建议：使用双方都熟悉的专属昵称"
            setTextColor(Color.parseColor("#9E9E9E")); textSize = 12f; setPadding(0, 8, 0, 40)
        })

        val etCode = EditText(this).apply { hint = "输入 TA 的 6 位邀请码"; maxLines = 1; filters = arrayOf(InputFilter.LengthFilter(6)); isAllCaps = true }
        layout.addView(etCode)

        val dialog = MaterialAlertDialogBuilder(this).setTitle("心动连接").setView(layout).setCancelable(false)
            .setPositiveButton("立即绑定", null).setNegativeButton("暂时跳过") { _, _ -> gotoMain() }.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nickname = etNickname.text.toString().trim()
            val code = etCode.text.toString().trim().uppercase()

            if (nickname.isEmpty() || code.length < 6) {
                Toast.makeText(this, "请完整填写昵称和6位邀请码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performBind(nickname, code, dialog)
        }
    }

    private fun performBind(nickname: String, code: String, dialog: AlertDialog) {
        val uid = UserPrefs.getUserId(this)
        NetworkClient.getApi(this).bind(userId = uid, nickname = nickname, targetCode = code)
            .enqueue(object : Callback<UserResponse> {
                override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                    val res = response.body() ?: return
                    if (res.status == "success") {
                        UserPrefs.saveNickname(this@LoginActivity, nickname)
                        UserPrefs.savePartnerNickname(this@LoginActivity, res.partner_nickname ?: "TA")
                        UserPrefs.savePartnerId(this@LoginActivity, res.partner_id ?: -1)
                        dialog.dismiss()
                        gotoMain()
                    } else Toast.makeText(this@LoginActivity, res.message, Toast.LENGTH_SHORT).show()
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