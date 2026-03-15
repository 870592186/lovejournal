package cn.xtay.lovejournal.ui

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class VideoPlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        rootLayout.addView(videoView)
        setContentView(rootLayout)

        val videoUriString = intent.getStringExtra("videoUri")
        if (videoUriString == null) {
            Toast.makeText(this, "视频地址无效", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val videoUri = Uri.parse(videoUriString)
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)

        videoView.setMediaController(mediaController)
        videoView.setVideoPath(videoUri.path)

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        videoView.setOnPreparedListener { it.start() }
        videoView.setOnCompletionListener { finish() }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "播放失败，文件可能已损毁", Toast.LENGTH_SHORT).show()
            finish()
            true
        }
    }
}