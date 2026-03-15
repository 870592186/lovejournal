package cn.xtay.lovejournal.util

import android.content.Context
import android.content.SharedPreferences

object UserPrefs {
    private const val PREFS_NAME = "love_journal_prefs"

    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_COUPLE_CODE = "couple_code"
    private const val KEY_PARTNER_ID = "partner_id"
    private const val KEY_LOCAL_PERIODS = "local_periods"

    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_HIDE_RECENTS = "hide_recents"
    private const val KEY_PARTNER_DATA_JSON = "partner_data_json"

    private const val KEY_USE_CUSTOM_SERVER = "use_custom_server"
    private const val KEY_CUSTOM_SERVER_URL = "custom_server_url"
    private const val DEFAULT_SERVER = "https://x.xtay.cn/"

    private const val KEY_USE_CUSTOM_AMAP = "use_custom_amap"
    private const val KEY_CUSTOM_AMAP_KEY = "custom_amap_key"

    private const val KEY_STEP_BASELINE = "step_baseline"
    private const val KEY_STEP_DAY = "step_day"

    private const val KEY_EXEMPT_APPS = "exempt_apps"
    private const val KEY_ENABLE_BATTERY_EXEMPT = "enable_battery_exempt"
    private const val KEY_BATTERY_THRESHOLD = "battery_threshold"
    private const val KEY_ENABLE_DND_EXEMPT = "enable_dnd_exempt"
    private const val KEY_REMOTE_COMMAND = "remote_command"
    private const val KEY_COMMAND_TIME = "command_time"

    private const val KEY_MY_NICKNAME = "my_nickname"
    private const val KEY_PARTNER_NICKNAME = "partner_nickname"

    private const val KEY_NOTIF_TITLE = "notif_title"
    private const val KEY_NOTIF_NORMAL = "notif_normal"
    private const val KEY_NOTIF_OFFLINE = "notif_offline"
    private const val KEY_NOTIF_MOVING = "notif_moving"
    private const val KEY_NOTIF_ERROR = "notif_error"
    private const val KEY_NOTIF_NEW_MSG = "notif_new_msg"

    private const val KEY_OTA_IGNORED_VERSION = "ota_ignored_version"

    private const val KEY_WIDGET_PARTNER_BATTERY = "widget_partner_battery"
    private const val KEY_WIDGET_PARTNER_APP = "widget_partner_app"
    private const val KEY_WIDGET_PARTNER_ADDRESS = "widget_partner_address"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Widget 相关 ---
    fun saveWidgetData(context: Context, battery: Int, app: String, address: String) {
        getPrefs(context).edit().apply {
            putInt(KEY_WIDGET_PARTNER_BATTERY, battery)
            putString(KEY_WIDGET_PARTNER_APP, app)
            putString(KEY_WIDGET_PARTNER_ADDRESS, address)
            apply()
        }
    }

    fun getWidgetPartnerBattery(context: Context): Int = getPrefs(context).getInt(KEY_WIDGET_PARTNER_BATTERY, 0)
    fun getWidgetPartnerApp(context: Context): String = getPrefs(context).getString(KEY_WIDGET_PARTNER_APP, "") ?: ""
    fun getWidgetPartnerAddress(context: Context): String = getPrefs(context).getString(KEY_WIDGET_PARTNER_ADDRESS, "") ?: ""

    // --- 个人昵称相关 (修复 ChatActivity 爆红) ---
    fun saveUserNickname(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_MY_NICKNAME, name).apply()
    }
    fun getUserNickname(context: Context): String = getPrefs(context).getString(KEY_MY_NICKNAME, "") ?: ""

    // 保留原有方法名防止其他地方调用
    fun saveNickname(context: Context, name: String) = saveUserNickname(context, name)
    fun getNickname(context: Context): String? = getPrefs(context).getString(KEY_MY_NICKNAME, null)

    // --- 对方昵称相关 ---
    fun savePartnerNickname(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_PARTNER_NICKNAME, name).apply()
    }
    fun getPartnerNickname(context: Context): String = getPrefs(context).getString(KEY_PARTNER_NICKNAME, "") ?: "TA"

    // --- 服务器配置同步 ---
    fun saveServerConfigData(context: Context, config: cn.xtay.lovejournal.model.ServerConfig) {
        getPrefs(context).edit().apply {
            putString(KEY_EXEMPT_APPS, config.exempt_apps ?: "")
            putBoolean(KEY_ENABLE_BATTERY_EXEMPT, config.enable_battery_exempt)
            putInt(KEY_BATTERY_THRESHOLD, config.battery_threshold)
            putBoolean(KEY_ENABLE_DND_EXEMPT, config.enable_dnd_exempt)
            putString(KEY_REMOTE_COMMAND, config.remote_command ?: "")
            putLong(KEY_COMMAND_TIME, config.command_time)
            apply()
        }
    }

    fun getExemptApps(context: Context): List<String> {
        val str = getPrefs(context).getString(KEY_EXEMPT_APPS, "") ?: ""
        return if (str.isBlank()) emptyList() else str.split(",").map { it.trim() }
    }

    fun isBatteryExemptEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ENABLE_BATTERY_EXEMPT, false)
    fun getBatteryThreshold(context: Context): Int = getPrefs(context).getInt(KEY_BATTERY_THRESHOLD, 15)
    fun isDndExemptEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ENABLE_DND_EXEMPT, false)
    fun getRemoteCommand(context: Context): String = getPrefs(context).getString(KEY_REMOTE_COMMAND, "") ?: ""
    fun getCommandTime(context: Context): Long = getPrefs(context).getLong(KEY_COMMAND_TIME, 0L)

    fun clearRemoteCommandLocally(context: Context) {
        getPrefs(context).edit().putString(KEY_REMOTE_COMMAND, "").putLong(KEY_COMMAND_TIME, 0L).apply()
    }

    // --- 步数相关 ---
    fun saveStepBaseline(context: Context, steps: Int) {
        getPrefs(context).edit().putInt(KEY_STEP_BASELINE, steps).apply()
    }
    fun getStepBaseline(context: Context): Int = getPrefs(context).getInt(KEY_STEP_BASELINE, 0)

    fun saveStepDay(context: Context, day: String) {
        getPrefs(context).edit().putString(KEY_STEP_DAY, day).apply()
    }
    fun getStepDay(context: Context): String? = getPrefs(context).getString(KEY_STEP_DAY, null)

    // --- 服务器 URL 相关 ---
    fun getServerUrl(context: Context): String {
        val useCustom = getPrefs(context).getBoolean(KEY_USE_CUSTOM_SERVER, false)
        return if (useCustom) {
            getPrefs(context).getString(KEY_CUSTOM_SERVER_URL, DEFAULT_SERVER) ?: DEFAULT_SERVER
        } else {
            DEFAULT_SERVER
        }
    }

    fun getCustomServerUrlRaw(context: Context): String = getPrefs(context).getString(KEY_CUSTOM_SERVER_URL, "") ?: ""

    fun saveServerConfig(context: Context, useCustom: Boolean, url: String) {
        var formattedUrl = url.trim()
        if (formattedUrl.isNotEmpty() && !formattedUrl.endsWith("/")) {
            formattedUrl += "/"
        }
        getPrefs(context).edit().apply {
            putBoolean(KEY_USE_CUSTOM_SERVER, useCustom)
            putString(KEY_CUSTOM_SERVER_URL, formattedUrl)
            apply()
        }
    }

    fun isUsingCustomServer(context: Context): Boolean = getPrefs(context).getBoolean(KEY_USE_CUSTOM_SERVER, false)

    // --- 高德 AMap 相关 ---
    fun isUsingCustomAMap(context: Context): Boolean = getPrefs(context).getBoolean(KEY_USE_CUSTOM_AMAP, false)
    fun getCustomAMapKeyRaw(context: Context): String = getPrefs(context).getString(KEY_CUSTOM_AMAP_KEY, "") ?: ""
    fun getAMapKey(context: Context): String = getPrefs(context).getString(KEY_CUSTOM_AMAP_KEY, "") ?: ""

    fun saveAMapConfig(context: Context, useCustom: Boolean, key: String) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_USE_CUSTOM_AMAP, useCustom)
            putString(KEY_CUSTOM_AMAP_KEY, key.trim())
            apply()
        }
    }

    // --- 登录状态相关 ---
    fun saveIsLoggedIn(context: Context, logged: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_LOGGED_IN, logged).apply()
    }

    fun isLoggedIn(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_LOGGED_IN, false) || getUserId(context) != -1
    }

    fun saveUserId(context: Context, userId: Int) {
        getPrefs(context).edit().putInt(KEY_USER_ID, userId).apply()
    }
    fun getUserId(context: Context): Int = getPrefs(context).getInt(KEY_USER_ID, -1)

    fun savePartnerId(context: Context, partnerId: Int) {
        getPrefs(context).edit().putInt(KEY_PARTNER_ID, partnerId).apply()
    }
    fun getPartnerId(context: Context): Int = getPrefs(context).getInt(KEY_PARTNER_ID, -1)

    fun saveLoginInfo(context: Context, id: Int, name: String, code: String, partnerId: Int?) {
        getPrefs(context).edit().apply {
            putInt(KEY_USER_ID, id)
            putString(KEY_USERNAME, name)
            putString(KEY_COUPLE_CODE, code)
            putInt(KEY_PARTNER_ID, partnerId ?: -1)
            putBoolean(KEY_IS_LOGGED_IN, true)
            apply()
        }
    }

    fun getUsername(context: Context): String? = getPrefs(context).getString(KEY_USERNAME, null)
    fun getCoupleCode(context: Context): String? = getPrefs(context).getString(KEY_COUPLE_CODE, null)

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    // --- 隐私相关 ---
    fun setHideRecentsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HIDE_RECENTS, enabled).apply()
    }
    fun isHideRecentsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HIDE_RECENTS, false)

    // --- 数据存储相关 ---
    fun saveLocalPeriods(context: Context, json: String) {
        getPrefs(context).edit().putString(KEY_LOCAL_PERIODS, json).apply()
    }
    fun getLocalPeriods(context: Context): String? = getPrefs(context).getString(KEY_LOCAL_PERIODS, null)

    fun savePartnerDeviceJson(context: Context, json: String) {
        getPrefs(context).edit().putString(KEY_PARTNER_DATA_JSON, json).apply()
    }
    fun getPartnerDeviceJson(context: Context): String? = getPrefs(context).getString(KEY_PARTNER_DATA_JSON, null)

    // --- 通知文案自定义 ---
    fun saveNotifTitle(context: Context, v: String) = getPrefs(context).edit().putString(KEY_NOTIF_TITLE, v).apply()
    fun getNotifTitle(context: Context): String = getPrefs(context).getString(KEY_NOTIF_TITLE, "") ?: ""

    fun saveNotifNormal(context: Context, v: String) = getPrefs(context).edit().putString(KEY_NOTIF_NORMAL, v).apply()
    fun getNotifNormal(context: Context): String = getPrefs(context).getString(KEY_NOTIF_NORMAL, "") ?: ""

    fun saveNotifOffline(context: Context, v: String) = getPrefs(context).edit().putString(KEY_NOTIF_OFFLINE, v).apply()
    fun getNotifOffline(context: Context): String = getPrefs(context).getString(KEY_NOTIF_OFFLINE, "") ?: ""

    fun saveNotifMoving(context: Context, v: String) = getPrefs(context).edit().putString(KEY_NOTIF_MOVING, v).apply()
    fun getNotifMoving(context: Context): String = getPrefs(context).getString(KEY_NOTIF_MOVING, "") ?: ""

    fun saveNotifError(context: Context, v: String) = getPrefs(context).edit().putString(KEY_NOTIF_ERROR, v).apply()
    fun getNotifError(context: Context): String = getPrefs(context).getString(KEY_NOTIF_ERROR, "") ?: ""

    fun saveNotifNewMsg(context: Context, v: String) = getPrefs(context).edit().putString(KEY_NOTIF_NEW_MSG, v).apply()
    fun getNotifNewMsg(context: Context): String = getPrefs(context).getString(KEY_NOTIF_NEW_MSG, "") ?: ""

    // --- OTA 版本相关 ---
    fun saveIgnoredVersion(context: Context, version: Int) {
        getPrefs(context).edit().putInt(KEY_OTA_IGNORED_VERSION, version).apply()
    }
    fun getIgnoredVersion(context: Context): Int = getPrefs(context).getInt(KEY_OTA_IGNORED_VERSION, 0)
}