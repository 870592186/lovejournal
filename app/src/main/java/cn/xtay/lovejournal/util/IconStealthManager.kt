package cn.xtay.lovejournal.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconStealthManager {

    /**
     * 切换 App 身份
     * @param useFake 是否开启伪装
     */
    fun switchAppIdentity(context: Context, useFake: Boolean) {
        val pm = context.packageManager

        val realComp = ComponentName(context, "${context.packageName}.RealLauncher")
        val fakeComp = ComponentName(context, "${context.packageName}.FakeLauncher")

        if (useFake) {
            // 启用伪装分身，禁用真实分身
            pm.setComponentEnabledSetting(fakeComp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
            pm.setComponentEnabledSetting(realComp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        } else {
            // 启用真实分身，禁用伪装分身
            pm.setComponentEnabledSetting(realComp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
            pm.setComponentEnabledSetting(fakeComp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}