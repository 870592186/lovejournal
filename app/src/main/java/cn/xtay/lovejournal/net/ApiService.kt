package cn.xtay.lovejournal.net

import cn.xtay.lovejournal.model.UserResponse
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import okhttp3.ResponseBody
import retrofit2.http.GET

interface ApiService {
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun login(
        @Field("action") action: String = "login",
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("device_id") deviceId: String // 💖 新增设备标识
    ): Call<UserResponse>

    // 💖 新增退出接口，用于释放设备绑定
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun logout(
        @Field("action") action: String = "logout",
        @Field("user_id") userId: Int,
        @Field("device_id") deviceId: String
    ): Call<UserResponse>

    // 💖 核心修改：增加 nickname 字段，绑定时顺便提交昵称
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun bind(
        @Field("action") action: String = "bind",
        @Field("user_id") userId: Int,
        @Field("nickname") nickname: String, // 💖 新增：绑定时提交自己的昵称
        @Field("target_code") targetCode: String
    ): Call<UserResponse>

    @FormUrlEncoded
    @POST("love_api/api.php")
    fun syncAll(
        @Field("action") action: String = "sync_all",
        @Field("user_id") userId: Int,
        @Field("device_id") deviceId: String, // 💖 心跳携带设备标识，用于被踢检测
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
        @Field("clear_command_time") clearCommandTime: Long? = null // 💖 核心新增：用于向服务器核销已执行的远控命令！
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

    // 💖 新增接口：向对方发送远控指令 (比如 fly_heart)
    @FormUrlEncoded
    @POST("love_api/api.php")
    fun sendCommand(
        @Field("action") action: String = "send_command",
        @Field("user_id") userId: Int,       // 自己的 ID
        @Field("partner_id") partnerId: Int, // 对方的 ID
        @Field("command") command: String,   // 指令内容 (fly_heart)
        @Field("time") time: Long            // 时间戳
    ): Call<UserResponse>


    // 💖 全新加入：直接拉取服务器上的 update.json 文件
    @GET("love_api/update.json")
    fun checkAppUpdate(): Call<ResponseBody>

}