package cn.xtay.lovejournal.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.xtay.lovejournal.util.DeviceUtil
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

    // 💡 刺客一修复：增加极度严格的防并发锁，拒绝重复触发僵尸线程
    private var isConnecting = false

    private var currentKeepAlive = 270
    private var lastHeartbeatResetTime = 0L

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
        // 🛡️ 防御锁：如果已经连上、或者正在连接中，立刻打回，绝不并发！
        if (isConnected || isConnecting || userId <= 0) return

        appContext = context.applicationContext
        currentUserId = userId
        isConnecting = true // 上锁

        val brokerUrl = getMqttBrokerUrl(context)
        val clientId = "user_$userId"

        try {
            if (mqttClient == null) {
                mqttClient = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())
            }

            val now = System.currentTimeMillis()
            if (now - lastHeartbeatResetTime > 2 * 60 * 60 * 1000L) {
                currentKeepAlive = 270
                lastHeartbeatResetTime = now
            }

            val options = MqttConnectOptions().apply {
                isCleanSession = false
                keepAliveInterval = currentKeepAlive
                isAutomaticReconnect = false
                connectionTimeout = 10
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    Log.d("RTC_DEBUG", "🚀 MQTT 引擎连接成功! 当前采用心跳: ${currentKeepAlive}s")

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
                    Log.e("RTC_DEBUG", "💔 MQTT 底层连接断开: ${cause?.message}")

                    appContext?.let { ctx ->
                        val netInfo = DeviceUtil.getNetworkInfo(ctx).first
                        if (netInfo != "无网络") {
                            if (currentKeepAlive > 60) {
                                currentKeepAlive -= 30
                                if (currentKeepAlive < 60) currentKeepAlive = 60
                            }
                        }
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val text = message?.payload?.let { String(it) } ?: return
                    try {
                        val json = JSONObject(text)
                        val action = json.optString("action")

                        if (action == "receive_from_partner") {
                            val command = json.optString("command")
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

            // 💡 刺客一终极绝杀：注入底层监听器，一旦连接失败，就地正法！
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    isConnecting = false // 成功后解锁
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    isConnecting = false // 失败后解锁
                    Log.e("RTC_DEBUG", "💥 物理连接彻底失败，触发防死循环强制清理: ${exception?.message}")
                    // 强行阻断可能挂起的后台发包线程，根除 CPU 100% 死循环！
                    try { mqttClient?.disconnectForcibly(100, 100) } catch (e: Exception) {}
                    mqttClient = null
                }
            })

        } catch (e: Exception) {
            isConnecting = false // 异常解锁
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun disconnect() {
        try {
            isConnecting = false
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