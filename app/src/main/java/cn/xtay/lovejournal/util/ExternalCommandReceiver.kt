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

        when (command) {
            "toggle_sleep" -> handleToggleSleep(context)
            "enable_sleep" -> setSleepState(context, 1)
            "disable_sleep" -> setSleepState(context, 0)
            "fly_heart" -> handleFlyHeart(context)
            // 🚀 新增：无损电量调试接口
            "mock_battery" -> {
                // 兼容 int 和 string 两种传参方式，默认为 -1（代表恢复真实电量）
                var level = intent.getIntExtra("level", -1)
                if (level == -1) {
                    level = intent.getStringExtra("level")?.toIntOrNull() ?: -1
                }
                setMockBattery(context, level)
            }
        }
    }

    // 🚀 新增：处理无损电量调试
    private fun setMockBattery(context: Context, level: Int) {
        val prefs = context.getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("mock_battery_level", level).apply()

        if (level == -1) {
            Toast.makeText(context, "✅ 已恢复读取真实物理电量", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "🧪 调试模式：全局电量已被锁死为 $level%", Toast.LENGTH_SHORT).show()
        }
    }

    // 处理：一键切换伪装模式
    private fun handleToggleSleep(context: Context) {
        val prefs = context.getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
        val currentState = prefs.getInt("dev_sleep_state", 0)
        val newState = if (currentState == 0) 1 else 0
        setSleepState(context, newState)
    }

    // 设置伪装模式状态
    private fun setSleepState(context: Context, state: Int) {
        val prefs = context.getSharedPreferences("love_journal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("dev_sleep_state", state).apply()

        val msg = if (state == 0) "✅ 伪装模式已关闭" else "💤 伪装模式已开启"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        // 注意：这里不需要手动上传！LocationService 里的 3秒雷达会自动发现 SharedPreferences 的变化并秒发给服务器！
    }

    // 处理：一键发射爱心
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
                userId = uid,               // 👈 极有可能是之前少传了这个参数！
                partnerId = partnerId,
                command = "fly_heart",
                time = (System.currentTimeMillis() / 1000).toLong() // 或者 .toInt()
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