package cn.xtay.lovejournal.util

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import cn.xtay.lovejournal.R
import com.bumptech.glide.Glide

object HeartEffectUtil {

    fun showFloatingHeart(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { showFloatingHeart(context) }
            return
        }

        // 💖 终极杀招：检查悬浮窗权限！如果没有，直接提示并跳去设置页！
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "⛔ 无法弹出爱心！请先授予【显示在其他应用上层】权限！", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val heartView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

            Glide.with(context).asGif().load(R.drawable.ic_heart_anim).into(heartView)

            val params = WindowManager.LayoutParams().apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.CENTER
            }

            windowManager.addView(heartView, params)

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 💡 绝杀 CPU 满载的真凶：在移除悬浮窗前，必须强行停止 Glide 的 GIF 渲染引擎！
                    Glide.with(context).clear(heartView)
                    // 彻底清空内存中的图像残留，防止任何内存泄漏
                    heartView.setImageDrawable(null)

                    // 最后再安全地移除幽灵 View
                    windowManager.removeView(heartView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 6000)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "爱心渲染失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}