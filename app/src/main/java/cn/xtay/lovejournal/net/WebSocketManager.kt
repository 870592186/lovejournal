package cn.xtay.lovejournal.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.xtay.lovejournal.util.UserPrefs
import okhttp3.*
import org.json.JSONObject
import java.net.URL
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
    private val listeners = mutableListOf<MessageListener>()

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
            "ws://x.xtay.cn:8282"
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
                    if (json.optString("action") == "receive_from_partner") {
                        val command = json.optString("command")
                        val dataString = json.optJSONObject("data")?.toString() ?: ""

                        // 💖 通知所有正在监听的页面或服务
                        Handler(Looper.getMainLooper()).post {
                            listeners.toList().forEach { it.onCommandReceived(command, dataString) }
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

    fun sendMessage(action: String, targetId: Int, command: String = "", data: JSONObject? = null) {
        if (!isConnected) return
        val json = JSONObject().apply {
            put("action", action)
            put("target_id", targetId)
            put("command", command)
            if (data != null) put("data", data)
        }
        webSocket?.send(json.toString())
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

    // 🚀 新增：专门用于长连接高速推送状态的底层后门
    fun sendRawJson(jsonString: String) {
        webSocket?.send(jsonString)
    }

}