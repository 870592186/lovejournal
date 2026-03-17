package cn.xtay.lovejournal.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.xtay.lovejournal.util.UserPrefs
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

object WebSocketManager {
    private const val TAG = "MqttManagerEngine" // 内部已彻底升级为 MQTT

    private var mqttClient: MqttAsyncClient? = null
    private var appContext: Context? = null
    var isConnected = false
        private set

    // 💖 多路内存监听器列表
    interface MessageListener {
        fun onCommandReceived(command: String, data: String)
    }

    private val listeners = CopyOnWriteArrayList<MessageListener>()

    fun addListener(listener: MessageListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: MessageListener) {
        listeners.remove(listener)
    }

    private var currentUserId = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getMqttBrokerUrl(context: Context): String {
        val httpUrl = UserPrefs.getServerUrl(context)
        return try {
            val host = URL(httpUrl).host
            "tcp://$host:1883" // 🚀 降维打击：底层纯 TCP 协议，抛弃臃肿的 WS 头
        } catch (e: Exception) {
            e.printStackTrace()
            "tcp://love.sraiy.com:1883" // 兜底容灾
        }
    }

    fun connect(context: Context, userId: Int) {
        if (isConnected || userId <= 0) return
        appContext = context.applicationContext
        currentUserId = userId

        val brokerUrl = getMqttBrokerUrl(context)
        val clientId = "user_$userId" // ⚠️ 极其关键：必须是 user_ID，与 PHP 端 Webhook 提取身份一致

        try {
            if (mqttClient == null) {
                mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            }

            // 🚀 核心黑魔法配置区
            val options = MqttConnectOptions().apply {
                isCleanSession = false // 🔥 核心：持久化会话！断网期间的消息会在 EMQX 服务器为你挂起保留
                keepAliveInterval = 45 // 🔥 极致省电：底层 C 库维持 45s 心跳，无需上层唤醒 CPU
                isAutomaticReconnect = true // 🔥 自动死磕重连：断网后底层自动指数级退避重连，比手写 Handler 稳一万倍
                connectionTimeout = 10
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    Log.d("RTC_DEBUG", "🚀 MQTT 引擎连接成功! 是否为断线自动重连: $reconnect")

                    // 1. 连接成功后，立刻订阅自己的专属下行主题 (QoS 1 保证到达)
                    val myTopic = "lovejournal/down/$userId"
                    try {
                        mqttClient?.subscribe(myTopic, 1)
                    } catch (e: Exception) { e.printStackTrace() }

                    // 2. 触发上线业务逻辑（通知 PHP 派发积压在 MySQL 的特殊离线指令）
                    val loginJson = JSONObject().apply {
                        put("action", "login")
                        put("user_id", userId)
                    }
                    sendRawJson(loginJson.toString())
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    Log.e("RTC_DEBUG", "💔 MQTT 底层连接断开，即将自动重连: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val text = message?.payload?.let { String(it) } ?: return
                    Log.d("RTC_DEBUG", "📥 收到 MQTT 原始消息 [$topic]: $text")

                    try {
                        val json = JSONObject(text)
                        val action = json.optString("action")

                        // 兼容你原有的业务逻辑结构
                        if (action == "receive_from_partner") {
                            val command = json.optString("command")
                            Log.d("RTC_DEBUG", "✅ 解析出业务命令: $command")

                            val dataObj = json.opt("data")
                            val dataString = dataObj?.toString() ?: ""

                            mainHandler.post {
                                listeners.forEach { it.onCommandReceived(command, dataString) }
                            }
                        }
                        // 🚀 修复原有 Bug：将服务端的 pong 单独拦截并抛给上层看门狗
                        else if (action == "pong") {
                            mainHandler.post {
                                listeners.forEach { it.onCommandReceived("pong", "") }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 完美兼容旧有发送接口
    fun sendMessage(action: String, targetId: Int, command: String = "", data: JSONObject? = null) {
        try {
            val json = JSONObject().apply {
                put("action", action)
                put("target_id", targetId)
                put("command", command)
                if (data != null) put("data", data)
            }
            sendRawJson(json.toString())
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * 🚀 核心改造：所有上行消息，全部扔给 lovejournal/up，由 EMQX 的 Webhook 转发给 PHP 处理
     */
    fun sendRawJson(jsonString: String) {
        try {
            val topic = "lovejournal/up"
            val message = MqttMessage(jsonString.toByteArray()).apply {
                qos = 1 // 🚀 QoS 1 黑魔法：至少到达一次。进电梯发消息自动挂起，出电梯瞬间重发！
            }
            mqttClient?.publish(topic, message)
            Log.d("RTC_DEBUG", "📤 向上行主题发布: $jsonString")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: Exception) { e.printStackTrace() }
        mqttClient = null
        isConnected = false
        currentUserId = -1
    }
}