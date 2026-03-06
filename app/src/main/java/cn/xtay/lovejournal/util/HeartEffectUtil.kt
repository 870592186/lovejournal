package cn.xtay.lovejournal.util

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import cn.xtay.lovejournal.R  // ⚠️ 注意：如果报错，请换成你自己的包名下的 R
import com.bumptech.glide.Glide // 💖 导入 Glide

object HeartEffectUtil {

    fun showFloatingHeart(context: Context) {
        // 确保在主线程运行，悬浮窗是 UI 操作
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { showFloatingHeart(context) }
            return
        }

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 1. 创建 ImageView 来承载动画
            val heartView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }

            // 💖 2. 使用 Glide 丝滑加载透明 GIF 动图
            Glide.with(context)
                .asGif()
                .load(R.drawable.ic_heart_anim) // 你的 GIF 文件名
                .into(heartView)

            // 3. 配置无焦点、可穿透的悬浮窗参数
            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT

                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                // 🌟 灵魂所在：不可触碰 (穿透) + 无焦点 + 允许延伸到刘海屏
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

                format = PixelFormat.TRANSLUCENT // 窗口背景完全透明
                gravity = Gravity.CENTER
            }

            // 4. 添加到屏幕
            windowManager.addView(heartView, params)

            // 5. 定时销毁：这里设置 4000 毫秒（4秒）。
            // 💡 如果你的 GIF 比较长，可以把这个数字改大一点，比如 6000！
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    windowManager.removeView(heartView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 4000)

        } catch (e: Exception) {
            e.printStackTrace() // 防止没给悬浮窗权限时崩溃
        }
    }
}