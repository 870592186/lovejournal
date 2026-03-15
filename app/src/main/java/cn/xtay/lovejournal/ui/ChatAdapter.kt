package cn.xtay.lovejournal.ui

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.local.ChatEntity
import cn.xtay.lovejournal.model.local.AppDatabase
import cn.xtay.lovejournal.util.CryptoUtil
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat

class ChatAdapter(
    private val currentUserId: Int,
    private val onSecureLockChange: (Boolean) -> Unit,
    private val onMessageBurned: (ChatEntity) -> Unit,
    private val onReplyClick: (ChatEntity) -> Unit,
    private val onQuoteClick: (String) -> Unit,
    private val onImageClick: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatEntity>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var highlightMsgId: String? = null

    private val downloadingSet = mutableSetOf<String>()
    private val decryptFailedSet = mutableSetOf<String>()
    private val revealedSet = mutableSetOf<String>()
    private val burningEndTimes = mutableMapOf<String, Long>()

    // 🚀 记录绑定的上下文，用于独立协程的数据库操作
    private var attachedContext: Context? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedContext = recyclerView.context
    }

    fun submitList(newList: List<ChatEntity>) {
        messages.clear()
        messages.addAll(newList)
        notifyDataSetChanged()
    }

    fun findPositionById(msgId: String): Int {
        return messages.indexOfFirst { it.msgId == msgId }
    }

    fun triggerHighlight(msgId: String) {
        highlightMsgId = msgId
        notifyItemChanged(findPositionById(msgId))
        adapterScope.launch {
            delay(2000)
            highlightMsgId = null
            notifyItemChanged(findPositionById(msgId))
        }
    }

    fun forceBurnAllActive() {
        // 🚀 包含所有正在倒计时和已经被揭开的面罩
        val activeIds = (burningEndTimes.keys + revealedSet).distinct()
        val context = attachedContext

        if (context != null && activeIds.isNotEmpty()) {
            // 🚀 核心修复 1：利用独立协程脱离 Activity 生命周期，确保退出页面瞬间必定执行数据库物理销毁
            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context)
                activeIds.forEach { msgId ->
                    val msg = messages.find { it.msgId == msgId }
                    if (msg != null && msg.msgType != "text") {
                        try { Uri.parse(msg.content).path?.let { File(it).delete() } } catch (e: Exception) {}
                    }
                    // 执行硬销毁
                    db.chatDao().markAsBurned(msgId)
                }
            }
        }

        activeIds.forEach { msgId ->
            val msg = messages.find { it.msgId == msgId }
            if (msg != null) {
                onMessageBurned(msg)
            }
        }
        burningEndTimes.clear()
        revealedSet.clear()
        decryptFailedSet.clear()
        adapterScope.coroutineContext.cancelChildren()
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == currentUserId) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layoutId = if (viewType == 1) R.layout.item_chat_right else R.layout.item_chat_left
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int {
        return messages.size
    }

    private fun getFileSizeStr(context: Context, pathOrUri: String): String {
        var size: Long = 0
        try {
            if (pathOrUri.startsWith("content://")) {
                val cursor = context.contentResolver.query(Uri.parse(pathOrUri), null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            size = it.getLong(sizeIndex)
                        }
                    }
                }
            } else if (pathOrUri.startsWith("file://")) {
                val path = Uri.parse(pathOrUri).path
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) size = file.length()
                }
            } else if (pathOrUri.startsWith("http")) {
                return "云端文件"
            } else {
                val file = File(pathOrUri)
                if (file.exists()) size = file.length()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (size <= 0L) return "未知大小"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private var thumbnailJob: Job? = null
        private var burnJob: Job? = null

        val tvText: TextView = itemView.findViewById(R.id.tvText)
        val layoutContent: MaterialCardView = itemView.findViewById(R.id.layoutContent) as MaterialCardView
        val layoutMask: View? = itemView.findViewById(R.id.layoutMask)
        val layoutBurned: View? = itemView.findViewById(R.id.layoutBurned)
        val tvCountdown: TextView? = itemView.findViewById(R.id.tvCountdown)
        val ivImage: ImageView? = itemView.findViewById(R.id.ivImage)
        val tvVideoInfo: TextView? = itemView.findViewById(R.id.tvVideoInfo)
        val tvBurnFlag: TextView? = itemView.findViewById(R.id.tvBurnFlag)
        val ivStatus: ImageView? = itemView.findViewById(R.id.ivStatus)

        val tvTime: TextView? = itemView.findViewById(R.id.tvTime)

        var currentMsgId: String? = null

        fun bind(msg: ChatEntity) {
            currentMsgId = msg.msgId

            // 🚀 核心修复 3：检测到已被物理销毁/删除（isRead 标志），直接将容器连根拔起，杜绝出现白框框
            if (msg.isRead) {
                itemView.visibility = View.GONE
                itemView.layoutParams = RecyclerView.LayoutParams(0, 0)
                return
            } else {
                itemView.visibility = View.VISIBLE
                itemView.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            tvTime?.text = timeFormat.format(java.util.Date(msg.timestamp))
            tvTime?.visibility = View.VISIBLE
            burnJob?.cancel()

            tvVideoInfo?.let {
                val bg = GradientDrawable()
                bg.setColor(Color.parseColor("#80000000"))
                bg.cornerRadius = 12f
                it.background = bg
            }

            layoutContent.setOnClickListener(null)

            // 🚀 核心修复 2：只有对方发来的阅后即焚，才禁用长按菜单。自己发出的依然可以唤起“删除本地记录”
            if (msg.isBurn && msg.senderId != currentUserId) {
                layoutContent.setOnLongClickListener(null)
            } else {
                layoutContent.setOnLongClickListener {
                    showActionMenu(msg)
                    true
                }
            }

            val isRemoteLink = msg.content.startsWith("http")
            val decFile = File(itemView.context.filesDir, "chat_media/dec_${msg.msgId}")
            val isDecrypted = decFile.exists()

            if (isRemoteLink && !isDecrypted && msg.msgType != "text") {
                tvText.visibility = View.VISIBLE
                tvText.text = "🔒 正在安全解密中..."
                ivImage?.visibility = View.GONE
                tvVideoInfo?.visibility = View.GONE

                if (!downloadingSet.contains(msg.msgId)) {
                    downloadingSet.add(msg.msgId)
                    adapterScope.launch(Dispatchers.IO) {
                        try {
                            val client = OkHttpClient()
                            val credential = Credentials.basic("guest", "guest")
                            val request = Request.Builder()
                                .url(msg.content)
                                .header("Authorization", credential)
                                .build()

                            val response = client.newCall(request).execute()
                            val responseBody = response.body()
                            if (response.isSuccessful && responseBody != null) {
                                val encFile = File(itemView.context.cacheDir, "enc_${msg.msgId}.tmp")

                                responseBody.byteStream().use { input ->
                                    FileOutputStream(encFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                }

                                decFile.parentFile?.mkdirs()
                                CryptoUtil.decryptFile(encFile, decFile, itemView.context)
                                encFile.delete()

                                withContext(Dispatchers.Main) {
                                    notifyItemChanged(adapterPosition)
                                }
                            } else {
                                throw Exception("HTTP ${response.code()}")
                            }
                        } catch (e: Exception) {
                            downloadingSet.remove(msg.msgId)
                            decryptFailedSet.add(msg.msgId)
                            withContext(Dispatchers.Main) {
                                tvText.text = "❌ 解密失败: ${e.message}"
                                notifyItemChanged(adapterPosition)
                            }
                        }
                    }
                }
            } else {
                val targetPath = if (isRemoteLink) Uri.fromFile(decFile).toString() else msg.content

                when (msg.msgType) {
                    "image" -> {
                        tvText.visibility = View.GONE
                        tvVideoInfo?.visibility = View.GONE
                        ivImage?.visibility = View.VISIBLE
                        try {
                            ivImage?.setImageURI(Uri.parse(targetPath))
                            layoutContent.setOnClickListener { onImageClick(targetPath) }
                        } catch (e: Exception) {
                            ivImage?.setImageResource(android.R.drawable.ic_menu_report_image)
                        }
                    }
                    "video" -> {
                        tvText.visibility = View.GONE
                        ivImage?.visibility = View.VISIBLE
                        tvVideoInfo?.visibility = View.VISIBLE

                        loadVideoThumbnail(targetPath)

                        val parts = msg.originalName.split("|")
                        val duration = if(parts.size > 1) parts[1] else "00:00"
                        val sizeStr = getFileSizeStr(itemView.context, targetPath)
                        tvVideoInfo?.text = "🎥 视频 • $duration • $sizeStr"

                        layoutContent.setOnClickListener {
                            val intent = Intent(itemView.context, VideoPlayerActivity::class.java)
                            intent.putExtra("videoUri", targetPath)
                            itemView.context.startActivity(intent)
                        }
                    }
                    "file" -> {
                        tvText.visibility = View.VISIBLE
                        ivImage?.visibility = View.GONE
                        tvVideoInfo?.visibility = View.GONE

                        val fileName = msg.originalName.split("|")[0]
                        val sizeStr = getFileSizeStr(itemView.context, targetPath)
                        tvText.text = "📄 $fileName\n$sizeStr"

                        layoutContent.setOnClickListener {
                            openFile(itemView.context, targetPath, fileName)
                        }
                    }
                    else -> {
                        tvText.visibility = View.VISIBLE
                        ivImage?.visibility = View.GONE
                        tvVideoInfo?.visibility = View.GONE
                        tvText.text = msg.content
                        if (msg.replyToMsgId != null) {
                            layoutContent.setOnClickListener {
                                onQuoteClick(msg.replyToMsgId)
                            }
                        }
                    }
                }
            }

            if (ivStatus != null) {
                if (msg.senderId == currentUserId) {
                    ivStatus.visibility = View.VISIBLE
                    when (msg.status) {
                        0 -> {
                            ivStatus.setImageResource(android.R.drawable.ic_popup_sync)
                            ivStatus.alpha = 0.5f
                        }
                        1 -> {
                            ivStatus.setImageResource(android.R.drawable.presence_online)
                            ivStatus.clearColorFilter()
                            ivStatus.alpha = 0.5f
                        }
                        2 -> {
                            ivStatus.setImageResource(android.R.drawable.checkbox_on_background)
                            ivStatus.clearColorFilter()
                            ivStatus.alpha = 0.5f
                        }
                        3 -> {
                            ivStatus.setImageResource(android.R.drawable.checkbox_on_background)
                            ivStatus.alpha = 1.0f
                            ivStatus.setColorFilter(Color.parseColor("#2196F3"))
                        }
                        else -> {
                            ivStatus.visibility = View.GONE
                        }
                    }
                } else {
                    ivStatus.visibility = View.GONE
                }
            }

            if (msg.msgId == highlightMsgId) {
                val originalColor = layoutContent.cardBackgroundColor.defaultColor
                val highlightColor = Color.parseColor("#FFF9C4")
                val animator = ObjectAnimator.ofInt(layoutContent, "cardBackgroundColor", originalColor, highlightColor, originalColor)
                animator.setEvaluator(ArgbEvaluator())
                animator.duration = 800
                animator.repeatCount = 1
                animator.start()
            }

            if (msg.isBurn) {
                if (msg.senderId == currentUserId) {
                    tvBurnFlag?.visibility = View.VISIBLE
                    if (msg.status == 3) {
                        tvBurnFlag?.text = "💨"
                        tvBurnFlag?.setTextColor(Color.parseColor("#9E9E9E"))
                    } else {
                        tvBurnFlag?.text = "🔥"
                        tvBurnFlag?.setTextColor(Color.parseColor("#FF5252"))
                    }
                } else {
                    tvBurnFlag?.visibility = View.GONE
                }
            } else {
                tvBurnFlag?.visibility = View.GONE
            }

            if (msg.isBurn && msg.senderId != currentUserId) {
                val isRevealed = revealedSet.contains(msg.msgId) || burningEndTimes.containsKey(msg.msgId)

                if (!isRevealed) {
                    layoutContent.visibility = View.GONE
                    layoutMask?.visibility = View.VISIBLE
                    layoutBurned?.visibility = View.GONE
                    tvCountdown?.visibility = View.GONE

                    layoutMask?.setOnClickListener {
                        revealedSet.add(msg.msgId)
                        onSecureLockChange(true)
                        notifyItemChanged(adapterPosition)
                    }
                } else {
                    layoutContent.visibility = View.VISIBLE
                    layoutMask?.visibility = View.GONE
                    layoutBurned?.visibility = View.GONE

                    val isReadyToBurn = msg.msgType == "text" || decFile.exists() || decryptFailedSet.contains(msg.msgId)

                    if (!isReadyToBurn) {
                        tvCountdown?.visibility = View.VISIBLE
                        tvCountdown?.text = "⏳"
                    } else {
                        var endTime = burningEndTimes[msg.msgId]

                        if (endTime == null) {
                            var burnDurationMs = 15000L
                            if (msg.msgType == "text") {
                                burnDurationMs = 10000L
                            } else if (msg.msgType == "file") {
                                burnDurationMs = 30000L
                            } else if (msg.msgType == "video") {
                                burnDurationMs = 30000L
                                try {
                                    val parts = msg.originalName.split("|")
                                    if (parts.size > 1) {
                                        val timeParts = parts[1].split(":")
                                        if (timeParts.size == 2) {
                                            val min = timeParts[0].toLong()
                                            val sec = timeParts[1].toLong()
                                            burnDurationMs = (min * 60 + sec) * 1000L + 15000L
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                            endTime = System.currentTimeMillis() + burnDurationMs
                            burningEndTimes[msg.msgId] = endTime
                        }

                        val remainMs = endTime - System.currentTimeMillis()
                        if (remainMs > 0) {
                            tvCountdown?.visibility = View.VISIBLE
                            burnJob = adapterScope.launch {
                                var currentRemain = remainMs
                                while (currentRemain > 0) {
                                    tvCountdown?.text = (currentRemain / 1000).toString()
                                    delay(500)
                                    currentRemain = endTime - System.currentTimeMillis()
                                }
                                burningEndTimes.remove(msg.msgId)
                                revealedSet.remove(msg.msgId)
                                tvCountdown?.visibility = View.GONE
                                layoutContent.visibility = View.GONE
                                layoutBurned?.visibility = View.VISIBLE
                                onSecureLockChange(false)
                                onMessageBurned(msg)
                            }
                        } else {
                            burningEndTimes.remove(msg.msgId)
                            revealedSet.remove(msg.msgId)
                            layoutContent.visibility = View.GONE
                            layoutMask?.visibility = View.GONE
                            layoutBurned?.visibility = View.VISIBLE
                            onSecureLockChange(false)
                            onMessageBurned(msg)
                        }
                    }
                }
            } else {
                layoutContent.visibility = View.VISIBLE
                layoutMask?.visibility = View.GONE
                layoutBurned?.visibility = View.GONE
                tvCountdown?.visibility = View.GONE
            }
        }

        private fun showActionMenu(msg: ChatEntity) {
            val actions = mutableListOf("引用该消息", "删除本地记录")
            if (msg.msgType == "text") {
                actions.add(0, "复制内容")
            }
            if (msg.msgType != "text") {
                actions.add(0, "保存到公共目录")
            }

            MaterialAlertDialogBuilder(itemView.context)
                .setTitle("操作")
                .setItems(actions.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        "复制内容" -> {
                            val cm = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("love", msg.content))
                        }
                        "引用该消息" -> {
                            onReplyClick(msg)
                        }
                        "保存到公共目录" -> {
                            val fileName = msg.originalName.split("|")[0]
                            val decFile = File(itemView.context.filesDir, "chat_media/dec_${msg.msgId}")
                            val targetPath = if (decFile.exists()) Uri.fromFile(decFile).toString() else msg.content
                            openFile(itemView.context, targetPath, fileName)
                        }
                        "删除本地记录" -> {
                            onMessageBurned(msg)
                        }
                    }
                }.show()
        }

        private fun openFile(context: Context, filePath: String, originalName: String) {
            try {
                val file = File(Uri.parse(filePath).path ?: "")
                if (!file.exists()) {
                    Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                    return
                }

                val targetFileName = if (originalName.isNotBlank()) originalName else file.name

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "*/*")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LoveJournal")
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            FileInputStream(file).use { inputStream ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (inputStream.read(buffer).also { read = it } != -1) {
                                    outputStream.write(buffer, 0, read)
                                }
                            }
                        }
                        Toast.makeText(context, "已导出至 Downloads/LoveJournal", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LoveJournal")
                    if (!publicDir.exists()) publicDir.mkdirs()
                    val publicFile = File(publicDir, targetFileName)
                    file.copyTo(publicFile, overwrite = true)
                    Toast.makeText(context, "已导出至 Downloads/LoveJournal", Toast.LENGTH_SHORT).show()
                }

                val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

            } catch (e: Exception) {
                Toast.makeText(context, "请在文件管理器中查看", Toast.LENGTH_LONG).show()
            }
        }

        private fun loadVideoThumbnail(videoPath: String) {
            thumbnailJob?.cancel()
            thumbnailJob = adapterScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(itemView.context, Uri.parse(videoPath))
                        retriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (e: Exception) {
                        null
                    } finally {
                        try {
                            retriever.release()
                        } catch (e: Exception) {}
                    }
                }
                if (bitmap != null) {
                    ivImage?.setImageBitmap(bitmap)
                }
            }
        }

        fun cancelThumbnailLoad() {
            thumbnailJob?.cancel()
        }
    }
}