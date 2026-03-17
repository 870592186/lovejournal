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
    private const val TAG = "MqttManagerEngine"

    private var mqttClient: MqttAsyncClient? = null
    private var appContext: Context? = null
    var isConnected = false
        private set

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
            "tcp://$host:1883"
        } catch (e: Exception) {
            e.printStackTrace()
            "tcp://love.sraiy.com:1883"
        }
    }

    fun connect(context: Context, userId: Int) {
        if (isConnected || userId <= 0) return
        appContext = context.applicationContext
        currentUserId = userId

        val brokerUrl = getMqttBrokerUrl(context)
        val clientId = "user_$userId"

        try {
            if (mqttClient == null) {
                mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            }

            val options = MqttConnectOptions().apply {
                isCleanSession = false
                // 💡 极其省电的 4.5 分钟长心跳：给 Doze 休眠留足空间，防止频繁唤醒基带耗电
                keepAliveInterval = 270
                isAutomaticReconnect = false
                connectionTimeout = 10
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    Log.d("RTC_DEBUG", "🚀 MQTT 引擎连接成功! 是否为断线自动重连: $reconnect")

                    val myTopic = "lovejournal/down/$userId"
                    try {
                        mqttClient?.subscribe(myTopic, 1)
                    } catch (e: Exception) { e.printStackTrace() }

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

                        if (action == "receive_from_partner") {
                            val command = json.optString("command")
                            Log.d("RTC_DEBUG", "✅ 解析出业务命令: $command")

                            val dataObj = json.opt("data")
                            val dataString = dataObj?.toString() ?: ""

                            mainHandler.post {
                                listeners.forEach { it.onCommandReceived(command, dataString) }
                            }
                        }
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

    fun sendRawJson(jsonString: String) {
        try {
            val topic = "lovejournal/up"
            val message = MqttMessage(jsonString.toByteArray()).apply {
                qos = 1
            }
            mqttClient?.publish(topic, message)
            Log.d("RTC_DEBUG", "📤 向上行主题发布: $jsonString")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            // 依然保留强杀手段，防止极端情况下的线程暴走
            mqttClient?.disconnectForcibly(1000, 1000)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mqttClient = null
            isConnected = false
            currentUserId = -1
        }
    }
}