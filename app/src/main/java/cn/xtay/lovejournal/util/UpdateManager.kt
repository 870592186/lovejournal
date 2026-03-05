package cn.xtay.lovejournal.util

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat // 💖 新增导入：用于完美兼容各版本广播注册
import androidx.core.content.FileProvider
import cn.xtay.lovejournal.BuildConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

class UpdateManager(private val context: Context) {

    companion object {
        // 💖 内存标志位：控制“稍后提醒”逻辑
        // 只要 App 进程还在，点过稍后就不再弹窗，直到下次 App 彻底重启
        var isDelayedInSession = false
    }

    /**
     * 核心方法：检查并显示更新弹窗
     */
    fun checkAndShowDialog(serverCode: Int, versionName: String, log: String, url: String) {
        // 💖 核心新增：只要收到更新指令，哪怕被拦截了，也把这套“最新版本信息”永久存到专属缓存里！
        val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("cached_server_code", serverCode)
            .putString("cached_version_name", versionName)
            .putString("cached_log", log)
            .putString("cached_url", url)
            .apply()

        // 获取本地真实安装的版本号
        val localCode = BuildConfig.VERSION_CODE
        val ignoredVersion = UserPrefs.getIgnoredVersion(context)

        // 🔴 真正的对比：如果服务器发来的版本，小于等于你手机上真实安装的版本，绝对不弹
        if (serverCode <= localCode) return

        // 🔴 拦截 2：被忽略的版本，不弹
        if (serverCode == ignoredVersion) return

        // 🔴 拦截 3：本次运行点过稍后，不弹
        if (isDelayedInSession) return

        // 🟢 满足所有条件，弹出 Material 对话框
        MaterialAlertDialogBuilder(context)
            .setTitle("发现新版本 $versionName")
            .setMessage(log.ifEmpty { "检测到重要更新，建议立即升级体验最新功能。" })
            .setCancelable(false) // 强制用户必须选一个
            .setPositiveButton("立即升级") { _, _ ->
                startDownload(url, versionName)
            }
            .setNeutralButton("以后再说") { _, _ ->
                // 设置内存标志位，本次运行不再骚扰
                isDelayedInSession = true
                Toast.makeText(context, "将在下次启动时再次提醒您", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("忽略此版本") { _, _ ->
                // 记录忽略的版本号到本地
                UserPrefs.saveIgnoredVersion(context, serverCode)
                Toast.makeText(context, "已忽略该版本", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 调用系统下载管理器进行下载
     */
    private fun startDownload(url: String, versionName: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("正在下载情侣手记 $versionName")
                setDescription("版本升级中，完成后将自动安装")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

                // 下载到系统公共目录
                val fileName = "LoveJournal_Update_${versionName}.apk"
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                setMimeType("application/vnd.android.package-archive")
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)

            // 💖 修复点 1：将广播接收器提取为变量，方便安全注销
            val onDownloadComplete = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        // 下载完成，直接获取 Uri 并安装
                        val apkUri = dm.getUriForDownloadedFile(downloadId)
                        installApk(apkUri)
                        // 💖 修复点 2：使用 applicationContext 注销，避免内存泄漏
                        context.applicationContext.unregisterReceiver(this)
                    }
                }
            }

            // 💖 修复点 3：使用 ContextCompat 注册，并添加 RECEIVER_EXPORTED 标志
            // 因为系统 DownloadManager 属于外部服务，必须显式声明 EXPORTED 才能接收它的广播
            ContextCompat.registerReceiver(
                context.applicationContext,
                onDownloadComplete,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )

            Toast.makeText(context, "已启动后台下载，请查看通知栏进度", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(context, "下载异常: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 调起系统安装页面
     */
    private fun installApk(uri: Uri?) {
        if (uri == null) return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法唤起安装器，请到下载管理手动安装", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 供设置页面手动检查更新使用的无拦截弹窗
     */
    fun manualCheckShow(serverCode: Int, versionName: String, log: String, url: String) {
        val localCode = BuildConfig.VERSION_CODE
        if (serverCode <= localCode) {
            Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
            return
        }

        // 手动检查时不检查忽略版本和稍后标志位，直接弹
        MaterialAlertDialogBuilder(context)
            .setTitle("检查到更新 $versionName")
            .setMessage(log)
            .setPositiveButton("立即下载") { _, _ -> startDownload(url, versionName) }
            .setNegativeButton("取消", null)
            .show()
    }
}