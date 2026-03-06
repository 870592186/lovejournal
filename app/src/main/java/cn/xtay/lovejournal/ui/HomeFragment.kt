package cn.xtay.lovejournal.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.UserPrefs
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class HomeFragment : Fragment() {

    private lateinit var btnSendHeart: ImageView
    private lateinit var tvStatus: TextView
    private var isSending = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // 加载我们刚才改好名字的布局
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnSendHeart = view.findViewById(R.id.btn_send_heart)
        tvStatus = view.findViewById(R.id.tv_status)

        btnSendHeart.setOnClickListener {
            if (isSending) return@setOnClickListener

            val partnerId = UserPrefs.getPartnerId(requireContext())
            if (partnerId == 0) {
                tvStatus.text = "请先在设置中绑定另一半"
                return@setOnClickListener
            }

            playHeartBeatAnimation(it)
            sendHeartCommandToServer(partnerId)
        }
    }

    private fun playHeartBeatAnimation(view: View) {
        val scaleDownX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 0.8f, 1.1f, 1.0f)
        val scaleDownY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 0.8f, 1.1f, 1.0f)
        ObjectAnimator.ofPropertyValuesHolder(view, scaleDownX, scaleDownY).apply {
            duration = 500
            interpolator = OvershootInterpolator()
            start()
        }
    }

    private fun sendHeartCommandToServer(partnerId: Int) {
        isSending = true
        tvStatus.text = "正在准备浪漫魔法..."
        tvStatus.setTextColor(Color.parseColor("#FF9800"))

        val userId = UserPrefs.getUserId(requireContext())
        val timeMs = System.currentTimeMillis()

        NetworkClient.getApi(requireContext()).sendCommand(
            userId = userId,
            partnerId = partnerId,
            command = "fly_heart",
            time = timeMs
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                isSending = false
                val resBody = response.body()

                // 确保 Fragment 还没被销毁才更新 UI
                if (!isAdded) return

                if (response.isSuccessful && resBody != null) {
                    if (resBody.status == "success") {
                        tvStatus.text = "发送成功！正在飞向 TA 的屏幕..."
                        tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                        tvStatus.postDelayed({ if(isAdded) tvStatus.text = "" }, 3000)
                    } else {
                        tvStatus.text = "发送失败：${resBody.message}"
                        tvStatus.setTextColor(Color.parseColor("#FF5252"))
                    }
                } else {
                    tvStatus.text = "服务器未响应，请稍后再试"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))
                }
            }

            override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                isSending = false
                if (!isAdded) return
                tvStatus.text = "网络连接失败: ${t.message}"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        })
    }
}