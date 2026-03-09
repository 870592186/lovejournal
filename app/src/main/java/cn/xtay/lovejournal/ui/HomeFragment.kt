package cn.xtay.lovejournal.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.util.UserPrefs

class HomeFragment : Fragment() {

    private lateinit var btnSendHeart: ImageView
    private lateinit var tvStatus: TextView
    private var isSending = false

    // 🛡️ 核心隐秘拦截网：检查是否开启了深度伪装
    private fun checkDevSleepIntercept(): Boolean {
        val state = requireContext().getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE).getInt("dev_sleep_state", 0)
        if (state == 1 || state == 2) {
            Toast.makeText(requireContext(), "⚠️ 已开启深度省电，此功能暂时禁用", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnSendHeart = view.findViewById(R.id.btn_send_heart)
        tvStatus = view.findViewById(R.id.tv_status)

        btnSendHeart.setOnClickListener {
            // 🛡️ 如果开启了深度伪装，直接拦截发送，保护自己！
            if (checkDevSleepIntercept()) return@setOnClickListener

            if (isSending) return@setOnClickListener

            val partnerId = UserPrefs.getPartnerId(requireContext())
            if (partnerId <= 0) {
                tvStatus.text = "请先在设置中绑定另一半"
                return@setOnClickListener
            }

            // 检查 WebSocket 通道是否通畅
            if (!WebSocketManager.isConnected) {
                tvStatus.text = "通道未连接，请稍后重试"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
                // 尝试重连一下
                val uid = UserPrefs.getUserId(requireContext())
                if (uid > 0) WebSocketManager.connect(requireContext(), uid)
                return@setOnClickListener
            }

            playHeartBeatAnimation(it)
            sendHeartCommandToWebSocket(partnerId)
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

    // 💖 核心改造：使用 WebSocket 毫秒级发送指令
    private fun sendHeartCommandToWebSocket(partnerId: Int) {
        isSending = true

        // 直接通过 WebSocket 瞬间发送出去！
        WebSocketManager.sendMessage("send_to_partner", partnerId, "fly_heart")

        // 假装有个处理过程，其实已经飞出去了（为了动画的连贯性）
        tvStatus.text = "正在准备浪漫魔法..."
        tvStatus.setTextColor(Color.parseColor("#FF9800"))

        tvStatus.postDelayed({
            if (isAdded) {
                isSending = false
                tvStatus.text = "发送成功！魔法已送达 TA 的屏幕 💖"
                tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                tvStatus.postDelayed({ if (isAdded) tvStatus.text = "" }, 3000)
            }
        }, 500) // 延迟 0.5 秒给动画一点时间
    }
}