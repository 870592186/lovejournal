package cn.xtay.lovejournal.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object StrategyManager {

    // 记录当前的豁免状态，避免重复触发上传和弹窗
    private var currentExemptState: String? = null

    /**
     * 💖 核心功能 1：拦截器判定
     * @return true 表示需要拦截并静默，false 表示放行正常定位
     */
    fun shouldIntercept(context: Context, batteryLevel: Int, onEnterExempt: (String) -> Unit): Boolean {

        // 1. 判断低电量豁免 (最高优先级)
        val isBatteryExemptEnabled = UserPrefs.isBatteryExemptEnabled(context)
        val batteryThreshold = UserPrefs.getBatteryThreshold(context)
        if (isBatteryExemptEnabled && batteryLevel <= batteryThreshold) {
            val stateMsg = "进入低电量优化模式 (电量 ${batteryLevel}%)"
            return handleStateChange(context, stateMsg, onEnterExempt)
        }

        // 2. 判断免打扰模式豁免 (你需要确保 DeviceUtil 里有个 isDndModeOn 方法)
        val isDndExemptEnabled = UserPrefs.isDndExemptEnabled(context)
        // 假设 DeviceUtil.isDndModeOn(context) 返回当前是否在免打扰模式
        if (isDndExemptEnabled && DeviceUtil.isDndModeOn(context)) {
             val stateMsg = "进入免打扰静默模式"
             return handleStateChange(context, stateMsg, onEnterExempt)
         }

        /// 3. 判断特定应用豁免 (仅在亮屏时生效)
        if (DeviceUtil.isScreenOn(context)) {
            val exemptApps = UserPrefs.getExemptApps(context)
            if (exemptApps.isNotEmpty()) {
                // 💖 这里的变量名改为 fgAppName，因为它拿到的是 "王者荣耀"
                val fgAppName = DeviceUtil.getForegroundApp(context)

                // 直接比对服务器下发的中文名列表
                if (exemptApps.contains(fgAppName)) {
                    val stateMsg = "进入网络优化状态：正在使用 $fgAppName"
                    return handleStateChange(context, stateMsg, onEnterExempt)
                }
            }
        }

        // 4. 不满足任何豁免条件，清空状态，放行
        currentExemptState = null
        return false
    }

    /**
     * 处理状态切换逻辑
     */
    private fun handleStateChange(context: Context, newStateMsg: String, onEnterExempt: (String) -> Unit): Boolean {
        if (currentExemptState != newStateMsg) {
            currentExemptState = newStateMsg

            // 第一次进入该状态，主线程弹窗提示用户
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "🛡️ $newStateMsg", Toast.LENGTH_SHORT).show()
            }

            // 执行回调，通知 LocationService 立即发送一条特殊状态给服务器
            onEnterExempt(newStateMsg)
        }
        // 只要满足条件，就一直返回 true，将心跳拦截在空转状态
        return true
    }

    /**
     * 强行重置状态（用于息屏、切网等高优事件破盾）
     */
    fun reset() {
        currentExemptState = null
    }

    /**
     * 💖 核心功能 2：检查并分发远控命令
     * 这个方法会在每次心跳时被调用
     */
    fun checkAndExecuteRemoteCommand(context: Context, onCommandExecuted: (Long) -> Unit) {
        val command = UserPrefs.getRemoteCommand(context)
        val commandTime = UserPrefs.getCommandTime(context)

        // 如果命令不为空，且时间戳大于0（说明是个合法的新命令）
        if (command.isNotEmpty() && commandTime > 0) {

            // 1. 呼叫“执行部队”去干活
            RemoteCommandExecutor.execute(context, command)

            // 2. 擦除本地缓存，防止下个心跳重复执行
            UserPrefs.clearRemoteCommandLocally(context)

            // 3. 呼叫回调，告诉网络层：把这个时间戳带给服务器，让服务器销毁命令！
            onCommandExecuted(commandTime)
        }
    }
}