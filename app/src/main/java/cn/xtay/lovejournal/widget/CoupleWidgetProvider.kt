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
        // 💖 1. 加载并裁切自定义圆形头像
        loadCustomAvatar(context, views)

        // 2. 读取其他状态数据
        val rawApp = UserPrefs.getWidgetPartnerApp(context)
        val battery = UserPrefs.getWidgetPartnerBattery(context)
        val address = UserPrefs.getWidgetPartnerAddress(context)

        val statusText = parseAppStatus(rawApp)
        views.setTextViewText(R.id.widget_tv_status, "TA正在：$statusText")
        views.setTextViewText(R.id.widget_tv_battery, "🔋 $battery%")
        views.setTextViewText(R.id.widget_tv_location, "📍 " + extractNeighborhood(address))
        views.setTextViewText(R.id.widget_tv_weather, "☀️ 晴 25°C · 适合穿短袖出街")
    }

    /**
     * 💖 核心新增：读取本地照片，并用底层代码强行裁切成正圆形！
     */
    private fun loadCustomAvatar(context: Context, views: RemoteViews) {
        val avatarFile = File(context.filesDir, "widget_avatar.jpg")
        if (avatarFile.exists()) {
            try {
                // 读取本地图片
                val originalBitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)
                // 压缩图片防止内存溢出导致小组件崩溃
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, 250, 250, true)
                // 切成圆
                val circularBitmap = getCircularBitmap(scaledBitmap)
                // 塞给小组件
                views.setImageViewBitmap(R.id.widget_avatar, circularBitmap)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // 如果没有设置照片，或者读取失败，兜底显示默认爱心
        views.setImageViewResource(R.id.widget_avatar, R.drawable.ic_big_heart)
    }

    /**
     * 🛠️ 黑科技：将任意形状的 Bitmap 转换成完美的圆形
     */
    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        // 取最短边作为正方形的边长，确保图片不会被拉伸
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true // 抗锯齿，让边缘平滑
            color = Color.BLACK
        }

        // 计算居中裁剪的矩形区域
        val xOffset = (bitmap.width - size) / 2
        val yOffset = (bitmap.height - size) / 2
        val rectSrc = Rect(xOffset, yOffset, xOffset + size, yOffset + size)
        val rectDest = Rect(0, 0, size, size)
        val rectF = RectF(rectDest)

        // 画一个圆当遮罩
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawRoundRect(rectF, size / 2f, size / 2f, paint)

        // 叠加图片
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rectSrc, rectDest, paint)

        return output
    }

    // --- 下面是原来的代码保持不变 ---
    private fun parseAppStatus(pkg: String): String {
        return when {
            pkg.contains("tencent.mm") -> "聊微信 💬"
            pkg.contains("aweme") -> "刷抖音 🎵"
            pkg.contains("kuaishou") -> "看快手 📱"
            pkg.contains("王者荣耀") || pkg.contains("peace") -> "打游戏 🎮"
            pkg.contains("bilibili") -> "刷B站 📺"
            pkg.contains("taobao") -> "逛淘宝 🛒"
            pkg.contains("music") || pkg.contains("netease") -> "听音乐 🎵"
            pkg.contains("息屏") -> "$pkg 💤"
            pkg.isEmpty() -> "未知状态 ❓"
            else -> "使用手机中 📱"
        }
    }

    private fun extractNeighborhood(address: String): String {
        if (address.isEmpty()) return "位置保密中 🤫"
        val splitAddr = address.substringAfterLast("区", address)
        val finalAddr = splitAddr.substringAfterLast("路", splitAddr)
        return if (finalAddr.length > 8) finalAddr.take(8) + ".." else finalAddr
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