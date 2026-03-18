package cn.xtay.lovejournal.util

import android.content.Context
import android.os.Handler
import android.os.Looper

object StrategyManager {

    /**
     * 💖 极简安全净化版：检查并分发远控命令
     * 只有弹爱心功能，拒绝一切流氓指令，且保证业务闭环
     */
    fun checkAndExecuteRemoteCommand(context: Context, onCommandExecuted: (Long) -> Unit) {
        val command = UserPrefs.getRemoteCommand(context)
        val commandTime = UserPrefs.getCommandTime(context)

        // 如果命令不为空，且时间戳大于0（合法新命令）
        if (command.isNotEmpty() && commandTime > 0) {

            // 🛡️ 绝对安全白名单：只放行弹出浪漫爱心，其余一律静默丢弃
            if (command == "fly_heart") {
                Handler(Looper.getMainLooper()).post {
                    cn.xtay.lovejournal.util.HeartEffectUtil.showFloatingHeart(context)
                }
            }

            // 擦除本地缓存
            UserPrefs.clearRemoteCommandLocally(context)

            // ⚠️ 关键恢复：必须回调告诉 LocationService，去向服务器发 clear_command 销毁此指令！
            onCommandExecuted(commandTime)
        }
    }
}