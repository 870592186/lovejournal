package cn.xtay.lovejournal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import android.widget.Toast
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.UserResponse
import cn.xtay.lovejournal.net.NetworkClient
import cn.xtay.lovejournal.util.UserPrefs
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class CoupleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_CLICK_AVATAR = "cn.xtay.lovejournal.ACTION_WIDGET_AVATAR_CLICK"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CoupleWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds.isNotEmpty()) {
                val intent = Intent(context, CoupleWidgetProvider::class.java)
                intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_couple_status)

            val intent = Intent(context, CoupleWidgetProvider::class.java).apply {
                action = ACTION_CLICK_AVATAR
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_avatar_container, pendingIntent)

            renderData(context, views)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_CLICK_AVATAR) {
            sendHeartCommand(context)

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CoupleWidgetProvider::class.java)
            val views = RemoteViews(context.packageName, R.layout.widget_couple_status)

            views.setViewPadding(R.id.widget_avatar_container, 25, 25, 25, 25)
            appWidgetManager.updateAppWidget(componentName, views)

            Handler(Looper.getMainLooper()).postDelayed({
                views.setViewPadding(R.id.widget_avatar_container, 5, 5, 5, 5)
                appWidgetManager.updateAppWidget(componentName, views)
            }, 150)
        }
    }

    private fun renderData(context: Context, views: RemoteViews) {
        // 1. 加载头像
        loadCustomAvatar(context, views)

        // 2. 读取原始数据
        val appName = UserPrefs.getWidgetPartnerApp(context)
        val rawBattery = UserPrefs.getWidgetPartnerBattery(context)
        val address = UserPrefs.getWidgetPartnerAddress(context)

        // 💖 3. 状态解析：直接使用中文应用名
        val statusText = parseAppStatus(appName)
        views.setTextViewText(R.id.widget_tv_status, "TA正在：$statusText")

        // 💖 4. 电量解析：> 100 表示充电中
        val isCharging = rawBattery > 100
        val actualBattery = if (isCharging) rawBattery - 100 else rawBattery
        val batteryStr = if (isCharging) "⚡ 充电中 $actualBattery%" else "🔋 $actualBattery%"
        views.setTextViewText(R.id.widget_tv_battery, batteryStr)

        // 💖 5. 位置全显
        val displayAddress = if (address.isEmpty()) "📍 位置保密中 🤫" else "📍 $address"
        views.setTextViewText(R.id.widget_tv_address, displayAddress)
    }

    /**
     * 读取并裁切圆形头像
     */
    private fun loadCustomAvatar(context: Context, views: RemoteViews) {
        val avatarFile = File(context.filesDir, "widget_avatar.jpg")
        if (avatarFile.exists()) {
            try {
                val originalBitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 250, 250, true)
                val circularBitmap = getCircularBitmap(scaledBitmap)
                views.setImageViewBitmap(R.id.widget_avatar, circularBitmap)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        views.setImageViewResource(R.id.widget_avatar, R.drawable.ic_big_heart)
    }

    /**
     * 转换为完美圆形
     */
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK
        }
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val rectSrc = Rect(xOffset, yOffset, xOffset + size, yOffset + size)
        val rectDest = Rect(0, 0, size, size)
        val rectF = RectF(rectDest)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawRoundRect(rectF, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rectSrc, rectDest, paint)

        return output
    }

    /**
     * 💖 直接使用你底层传过来的中文应用名称进行美化匹配
     */
    private fun parseAppStatus(appName: String): String {
        if (appName.isEmpty()) return "未知状态 ❓"
        if (appName.contains("息屏")) return "$appName 💤"
        if (appName == "桌面") return "闲逛桌面 👀"

        // 匹配常用 App 加点 Emoji，没匹配到的直接显示
        return when (appName) {
            "微信", "QQ" -> "聊$appName 💬"
            "抖音", "快手", "微视", "小红书" -> "刷$appName 📱"
            "王者荣耀", "和平精英", "原神", "金铲铲之战" -> "打$appName 🎮"
            "哔哩哔哩", "腾讯视频", "爱奇艺", "优酷视频" -> "看$appName 📺"
            "淘宝", "京东", "拼多多", "闲鱼" -> "逛$appName 🛒"
            "网易云音乐", "QQ音乐", "酷狗音乐" -> "听$appName 🎵"
            "知乎", "微博" -> "看$appName 📰"
            else -> "用 $appName 📱"
        }
    }

    private fun sendHeartCommand(context: Context) {
        val partnerId = UserPrefs.getPartnerId(context)
        if (partnerId <= 0) {
            Toast.makeText(context, "请先在 App 中绑定伴侣", Toast.LENGTH_SHORT).show()
            return
        }
        val userId = UserPrefs.getUserId(context)
        Toast.makeText(context, "正在发送浪漫魔法...", Toast.LENGTH_SHORT).show()
        NetworkClient.getApi(context).sendCommand(
            userId = userId, partnerId = partnerId, command = "fly_heart", time = System.currentTimeMillis()
        ).enqueue(object : Callback<UserResponse> {
            override fun onResponse(call: Call<UserResponse>, response: Response<UserResponse>) {
                if (response.isSuccessful && response.body()?.status == "success") {
                    Toast.makeText(context, "💖 爱心已送达对方屏幕！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "发送失败：${response.body()?.message}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<UserResponse>, t: Throwable) {
                Toast.makeText(context, "网络错误，无法发送爱心", Toast.LENGTH_SHORT).show()
            }
        })
    }
}