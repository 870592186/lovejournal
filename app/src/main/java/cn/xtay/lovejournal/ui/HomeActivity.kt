package cn.xtay.lovejournal.ui // ⚠️ 注意修改为你真实的包名

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.xtay.lovejournal.MainActivity
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.UserPrefs
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class HomeActivity : AppCompatActivity() {

    private lateinit var btnSendHeart: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnSettings: ImageButton

    // 防止用户疯狂连点
    private var isSending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 拦截未登录状态：如果没有 userId，直接去登录页
        if (UserPrefs.getUserId(this) == 0) {
            val intent = Intent(this, cn.xtay.lovejournal.LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        btnSendHeart = findViewById(R.id.btn_send_heart)
        tvStatus = findViewById(R.id.tv_status)
        btnSettings = findViewById(R.id.btn_settings)

        // 2. 爱心按钮点击事件
        btnSendHeart.setOnClickListener {
            if (isSending) return@setOnClickListener

            val partnerId = UserPrefs.getPartnerId(this)
            if (partnerId == 0) {
                tvStatus.text = "请先在设置中绑定另一半"
                return@setOnClickListener
            }

            playHeartBeatAnimation(it)
            sendHeartCommandToServer(partnerId)
        }

        // 3. 右下角设置按钮点击事件：跳转到原来的主界面
        btnSettings.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * 点击时给爱心加一个心跳 Q 弹的动画
     */
    private fun playHeartBeatAnimation(view: View) {
        val scaleDownX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 0.8f, 1.1f, 1.0f)
        val scaleDownY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 0.8f, 1.1f, 1.0f)
        ObjectAnimator.ofPropertyValuesHolder(view, scaleDownX, scaleDownY).apply {
            duration = 500
            interpolator = OvershootInterpolator()
            start()
        }
    }

    /**
     * 核心网络请求逻辑：向服务器发送 fly_heart 指令
     */
    private fun sendHeartCommandToServer(partnerId: Int) {
        isSending = true
        tvStatus.text = "正在准备浪漫魔法..."
        tvStatus.setTextColor(android.graphics.Color.parseColor("#FF9800")) // 橙色提示中

        val userId = UserPrefs.getUserId(this)
        val timeMs = System.currentTimeMillis()

        // 调用我们刚刚在 ApiService 里加的接口
        NetworkClient.getApi(this).sendCommand(
            userId = userId,
            partnerId = partnerId,
            command = "fly_heart",
            time = timeMs
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                isSending = false
                val resBody = response.body()

                if (response.isSuccessful && resBody != null) {
                    if (resBody.status == "success") {
                        tvStatus.text = "发送成功！正在飞向 TA 的屏幕..."
                        tvStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50")) // 绿色成功

                        // 3 秒后清空提示文字
                        tvStatus.postDelayed({ tvStatus.text = "" }, 3000)
                    } else {
                        tvStatus.text = "发送失败：${resBody.message}"
                        tvStatus.setTextColor(android.graphics.Color.parseColor("#FF5252")) // 红色报错
                    }
                } else {
                    tvStatus.text = "服务器未响应，请稍后再试"
                    tvStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
                }
            }

            override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                isSending = false
                tvStatus.text = "网络连接失败: ${t.message}"
                tvStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
            }
        })
    }
}