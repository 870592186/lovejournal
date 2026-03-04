package cn.xtay.lovejournal

import android.app.Application
import cn.xtay.lovejournal.util.UserPrefs
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.google.android.material.color.DynamicColors

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. 保留原有功能：全应用开启莫奈动态取色
        DynamicColors.applyToActivitiesIfAvailable(this)

        // 2. 高德地图 API 动态替换逻辑
        val useCustomAMap = UserPrefs.isUsingCustomAMap(this)
        val customKey = UserPrefs.getAMapKey(this)

        if (useCustomAMap && customKey.isNotEmpty()) {
            // 如果用户选了“自建 Key”并填了内容，强制注入
            // 这一步会覆盖 AndroidManifest.xml 中的静态配置
            MapsInitializer.setApiKey(customKey)
            AMapLocationClient.setApiKey(customKey)
        }

        // 3. 高德地图隐私合规（2026年SDK必须在初始化前调用，否则无法加载地图）
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
    }
}