package cn.xtay.lovejournal.net

import cn.xtay.lovejournal.model.UserResponse
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun login(
        @Field("action") action: String = "login",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("device_id") deviceId: String
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun logout(
        @Field("action") action: String = "logout",
        @Field("user_id") userId: Int,
        @Field("device_id") deviceId: String
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun bind(
        @Field("action") action: String = "bind",
        @Field("user_id") userId: Int,
        @Field("nickname") nickname: String,
        @Field("target_code") targetCode: String
    ): Call<UserResponse>

    // 💖 核心新增：专属的修改昵称通道！不和绑定逻辑混在一起！
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun updateNickname(
        @Field("action") action: String = "update_nickname",
        @Field("user_id") userId: Int,
        @Field("nickname") nickname: String
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun syncAll(
        @Field("action") action: String = "sync_all",
        @Field("user_id") userId: Int,
        @Field("device_id") deviceId: String,
        @Field("lat") lat: Double? = null,
        @Field("lng") lng: Double? = null,
        @Field("address") address: String? = null,
        @Field("battery") battery: Int? = null,
        @Field("net_type") netType: String? = null,
        @Field("wifi_name") wifiName: String? = null,
        @Field("fg_app") fgApp: String? = null,
        @Field("mic_busy") micBusy: Int? = null,
        @Field("top_apps_json") topApps: String? = null,
        @Field("steps") steps: Int? = null,
        @Field("clear_command_time") clearCommandTime: Long? = null
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun updatePeriod(
        @Field("action") action: String = "update_period",
        @Field("user_id") userId: Int,
        @Field("date") date: String,
        @Field("status") status: Int,
        @Field("memo") memo: String = ""
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun getPeriods(
        @Field("action") action: String = "get_periods",
        @Field("user_id") userId: Int,
        @Field("partner_id") partnerId: Int
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun getStatus(
        @Field("action") action: String = "sync_all",
        @Field("user_id") userId: Int,
        @Field("partner_id") partnerId: Int
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun sendCommand(
        @Field("action") action: String = "send_command",
        @Field("user_id") userId: Int,
        @Field("partner_id") partnerId: Int,
        @Field("command") command: String,
        @Field("time") time: Long
    ): Call<UserResponse>

    // 🚀 补充完善：将物理抹除指令的动作也纳入标准接口，告别原生代码拼装！
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun clearCommand(
        @Field("action") action: String = "clear_command",
        @Field("user_id") userId: Int
    ): Call<UserResponse>

    @GET("love_api/update.json")
    fun checkAppUpdate(): Call<ResponseBody>
}