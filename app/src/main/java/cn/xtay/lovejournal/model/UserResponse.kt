package cn.xtay.lovejournal.model

// 💖 核心新增：用于接收服务器下发的策略与远控配置
data class ServerConfig(
    val exempt_apps: String? = null,
    val enable_battery_exempt: Boolean = false,
    val battery_threshold: Int = 15,
    val enable_dnd_exempt: Boolean = false,
    val remote_command: String? = null,
    val command_time: Long = 0L
)

data class UserResponse(
    val status: String,
    val message: String? = null,
    val my_time: String? = null,
    val user_id: Int? = null,
    val couple_code: String? = null,
    val partner_id: Int? = null,
    val nickname: String? = null,
    val periods_data: List<PeriodRecord>? = null,
    val my_periods: List<PeriodRecord>? = null,
    val partner_data: PartnerData? = null,
    val server_config: ServerConfig? = null, // 接收顺风车数据

    // 💖 核心新增：接收服务器搭顺风车发回的昵称数据，用于绑定或重登时恢复
    val my_nickname: String? = null,
    val partner_nickname: String? = null
)

data class PeriodRecord(
    val date: String,
    val status: Int,
    val memo: String?
)

data class PartnerData(
    val location: LocationData? = null,
    val device: DeviceData? = null,
    val periods: List<PeriodRecord>? = null
)

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val updated_at: String?
)

data class DeviceData(
    val battery: Int = 0,
    val net_type: String? = null,
    val wifi_name: String? = null,
    val foreground_app: String? = null,
    val updated_at: String? = null,
    val mic_busy: Int = 0,
    val top_apps_json: String? = null,
    val steps: Int = 0
)