package cn.xtay.lovejournal.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.util.UserPrefs
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ExternalCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 核对暗号前缀
        if (intent.action != "cn.xtay.lovejournal.ACTION_EXTERNAL_CMD") return

        // 获取具体指令
        val command = intent.getStringExtra("cmd") ?: return

        // 🛡️ 安全净化：只保留发射爱心的功能，干掉所有可能被杀毒软件误判为后门的控制指令
        when (command) {
            "fly_heart" -> handleFlyHeart(context)
            else -> {
                // 收到其他指令一律静默丢弃，绝不执行任何静默修改操作
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