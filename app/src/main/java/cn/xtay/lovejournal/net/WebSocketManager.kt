package cn.xtay.lovejournal.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.xtay.lovejournal.util.UserPrefs
import okhttp3.*
import org.json.JSONObject
import java.lang.Compiler.command
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object WebSocketManager {
    private const val TAG = "WebSocketManager"

    private var webSocket: WebSocket? = null
    private var appContext: Context? = null
    var isConnected = false
        private set

    // 💖 升级为多路内存监听器列表
    interface MessageListener {
        fun onCommandReceived(command: String, data: String)
    }

    // 🚀 优化：使用 CopyOnWriteArrayList 保证多线程注册/注销监听器时的绝对安全
    private val listeners = CopyOnWriteArrayList<MessageListener>()

    fun addListener(listener: MessageListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: MessageListener) {
        listeners.remove(listener)
    }

    private val client = OkHttpClient.Builder().pingInterval(40, TimeUnit.SECONDS).build()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectCount = 0
    private var currentUserId = -1

    private fun getDynamicWsUrl(context: Context): String {
        val httpUrl = UserPrefs.getServerUrl(context)
        return try {
            val host = URL(httpUrl).host
            "ws://$host:8282"
        } catch (e: Exception) {
            e.printStackTrace()
            "wss://x.xtay.cn/ws"
        }
    }

    fun connect(context: Context, userId: Int) {
        if (isConnected || userId <= 0) return
        appContext = context.applicationContext
        currentUserId = userId

        val wsUrl = getDynamicWsUrl(context)
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectCount = 0
                val loginJson = JSONObject().apply {
                    put("action", "login")
                    put("user_id", userId)
                }
                webSocket.send(loginJson.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {

                try {
                    val json = JSONObject(text)
// 🚀 在这里加一行，打印所有收到的原始消息，看看到底服务器发了什么
                    android.util.Log.d("RTC_DEBUG", "收到原始消息: $text")

                    if (json.optString("action") == "receive_from_partner") {
                        val command = json.optString("command")


// ✅ 必须加在这一行之后，command 才是绿色的
                        android.util.Log.d("RTC_DEBUG", "解析出命令: $command")


                        // 🚀 优化：兼容强解。不论 data 是 JSONObject 还是普通 String，都能安全提取
                        val dataObj = json.opt("data")
                        val dataString = dataObj?.toString() ?: ""

                        // 💖 通知所有正在监听的页面或服务
                        Handler(Looper.getMainLooper()).post {
                            listeners.forEach { it.onCommandReceived(command, dataString) }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { isConnected = false }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                scheduleReconnect()
            }
        })
    }

    // 原有的发送格式化消息方法
    fun sendMessage(action: String, targetId: Int, command: String = "", data: JSONObject? = null) {
        if (!isConnected) return
        try {
            val json = JSONObject().apply {
                put("action", action)
                put("target_id", targetId)
                put("command", command)
                if (data != null) put("data", data)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * 🚀 新增：发送原始 JSON 字符串 (LocationService 极速雷达专用)
     */
    fun sendRawJson(jsonString: String) {
        if (!isConnected) return
        try {
            webSocket?.send(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun scheduleReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectCount++
        val delay = Math.min((3000 * Math.pow(2.0, (reconnectCount - 1).toDouble())).toLong(), 30000L)
        reconnectHandler.postDelayed({
            if (currentUserId > 0 && appContext != null) connect(appContext!!, currentUserId)
        }, delay)
    }

    fun disconnect() {
        webSocket?.close(1000, "User Offline")
        webSocket = null
        isConnected = false
        currentUserId = -1
        reconnectHandler.removeCallbacksAndMessages(null)
    }
}