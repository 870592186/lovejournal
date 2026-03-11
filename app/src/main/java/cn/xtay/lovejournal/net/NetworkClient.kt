package cn.xtay.lovejournal.net

import android.content.Context
import cn.xtay.lovejournal.util.UserPrefs
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkClient {

    // 缓存当前的 URL 和对应的 ApiService 实例
    private var currentUrl: String? = null
    private var cachedService: ApiService? = null

    // 共享同一个 OkHttpClient 配置
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 【核心改进】：根据 UserPrefs 动态获取 Api 实例
     * 每次请求时都会校验 URL 是否发生变化
     */
    fun getApi(context: Context): ApiService {
        val latestUrl = UserPrefs.getServerUrl(context)

        // 如果 URL 没有变化，且已经有了缓存的 Service，直接返回
        if (latestUrl == currentUrl && cachedService != null) {
            return cachedService!!
        }

        // 只要地址变了，就重新构建 Retrofit 实例
        currentUrl = latestUrl
        val retrofit = Retrofit.Builder()
            .baseUrl(latestUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        cachedService = retrofit.create(ApiService::class.java)
        return cachedService!!
    }


}