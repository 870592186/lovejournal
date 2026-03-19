package cn.xtay.lovejournal.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.net.WebSocketManager
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ExternalCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)

        when (intent.action) {
            // 🚀 1. 监听 ADB 专属特权暗号
            "cn.xtay.lovejournal.ACTION_SET_ADB_MODE" -> {
                val isEnable = intent.getBooleanExtra("enable", false)
                prefs.edit().putBoolean("is_adb_mode", isEnable).apply()
                // 可选：给自己弹个Toast确认命令生效了，实战时可以删掉这行保持隐蔽
                // Toast.makeText(context, "ADB特权模式: $isEnable", Toast.LENGTH_SHORT).show()
            }

            // 🛡️ 2. 监听手机重启广播：反侦察机制，重启自动销毁特权！
            Intent.ACTION_BOOT_COMPLETED -> {
                prefs.edit().putBoolean("is_adb_mode", false).apply()
            }

            // 💖 3. 处理原有的外部指令 (爱心发射等)
            "cn.xtay.lovejournal.ACTION_EXTERNAL_CMD" -> {
                val command = intent.getStringExtra("cmd") ?: return
                when (command) {
                    "fly_heart" -> handleFlyHeart(context)
                    else -> {
                        // 收到其他指令一律静默丢弃
                    }
                }
            }
        }
    }

    // 处理：一键发射爱心（安全白名单功能，完全保留）
    private fun handleFlyHeart(context: Context) {
        val partnerId = UserPrefs.getPartnerId(context)
        if (partnerId <= 0) {
            Toast.makeText(context, "未绑定另一半，无法发送", Toast.LENGTH_SHORT).show()
            return
        }

        // 优先走 WebSocket 极速通道
        if (WebSocketManager.isConnected) {
            WebSocketManager.sendMessage("send_to_partner", partnerId, "fly_heart", JSONObject())
            Toast.makeText(context, "💖 浪漫魔法已发射", Toast.LENGTH_SHORT).show()
        } else {
            // 降级走 HTTP 通道
            val uid = UserPrefs.getUserId(context)
            NetworkClient.getApi(context).sendCommand(
                action = "send_command",
                userId = uid,
                partnerId = partnerId,
                command = "fly_heart",
                time = (System.currentTimeMillis() / 1000).toLong()
            ).enqueue(object : Callback<cn.xtay.lovejournal.model.UserResponse> {
                override fun onResponse(call: Call<cn.xtay.lovejournal.model.UserResponse>, response: Response<cn.xtay.lovejournal.model.UserResponse>) {
                    Toast.makeText(context, "💖 浪漫魔法已发射(云端)", Toast.LENGTH_SHORT).show()
                }
                override fun onFailure(call: Call<cn.xtay.lovejournal.model.UserResponse>, t: Throwable) {
                    Toast.makeText(context, "发射失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}