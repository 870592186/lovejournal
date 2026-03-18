package cn.xtay.lovejournal.ui

import android.app.Dialog
import android.app.NotificationManager
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.xtay.lovejournal.R
import cn.xtay.lovejournal.model.local.AppDatabase
import cn.xtay.lovejournal.model.local.ChatEntity
import cn.xtay.lovejournal.net.WebSocketManager
import cn.xtay.lovejournal.util.UserPrefs
import cn.xtay.lovejournal.util.CryptoUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var toggleBurn: ToggleButton
    private lateinit var btnAddMedia: ImageView
    private lateinit var tvPartnerName: TextView
    private lateinit var btnBack: ImageView
    private lateinit var btnChatMenu: ImageView // 🚀 菜单按钮

    private lateinit var layoutReplyPreview: View
    private lateinit var tvReplyContent: TextView
    private lateinit var btnCancelReply: ImageView
    private var replyingMsg: ChatEntity? = null

    private lateinit var adapter: ChatAdapter
    private val database by lazy { AppDatabase.getDatabase(this) }
    private val myUserId by lazy { UserPrefs.getUserId(this) }
    private val partnerId by lazy { UserPrefs.getPartnerId(this) }

    private var secureLockCount = 0
    private val MSG_NOTIF_ID = 2027

    // 广播接收器：实时监听新消息
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.xtay.lovejournal.ACTION_CHAT_MSG") {
                lifecycleScope.launch {
                    delay(300)
                    loadMessagesFromDb()
                }
            }
        }
    }
    // 🚀 加在 MainActivity.kt 和 ChatActivity.kt 里面
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // 刷新当前的 Intent

        // （对于 ChatActivity）如果你有刷新消息列表的方法，可以在这里调用一下
        // 比如： loadMessages()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 解决输入框遮挡问题
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_chat)

        initViews()
        initRecyclerView()
        setupListeners()
        loadMessagesFromDb()

        // 尝试推送上次未成功发送的消息
        pushUnsentMessages()

        val filter = IntentFilter("cn.xtay.lovejournal.ACTION_CHAT_MSG")
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            messageReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        clearMessageNotification()
    }

    private fun initViews() {
        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        toggleBurn = findViewById(R.id.toggleBurn)
        btnAddMedia = findViewById(R.id.btnAddMedia)
        tvPartnerName = findViewById(R.id.tvPartnerName)
        btnBack = findViewById(R.id.btnBack)
        btnChatMenu = findViewById(R.id.btnChatMenu)

        layoutReplyPreview = findViewById(R.id.layoutReplyPreview)
        tvReplyContent = findViewById(R.id.tvReplyContent)
        btnCancelReply = findViewById(R.id.btnCancelReply)

        tvPartnerName.text = UserPrefs.getPartnerNickname(this)
        btnSend.visibility = View.VISIBLE

        updateBurnToggleUI(toggleBurn, false)
    }

    private fun initRecyclerView() {
        adapter = ChatAdapter(
            currentUserId = myUserId,
            onSecureLockChange = { isLock -> handleSecureLock(isLock) },
            onMessageBurned = { burnedMsg -> handleMessageBurned(burnedMsg) },
            onReplyClick = { msg -> setupReply(msg) },
            onQuoteClick = { quotedMsgId -> scrollToMessage(quotedMsgId) },
            onImageClick = { imagePath -> showFullScreenImage(imagePath) }
        )
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvChat.layoutManager = layoutManager
        rvChat.adapter = adapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        // 🚀 聊天设置/历史记录菜单入口
        btnChatMenu.setOnClickListener {
            showChatMenuDialog()
        }

        toggleBurn.setOnCheckedChangeListener { _, isChecked ->
            updateBurnToggleUI(toggleBurn, isChecked)
        }

        // 🚀 精美底部抽屉菜单
        btnAddMedia.setOnClickListener {
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.layout_bottom_menu, null)

            view.findViewById<View>(R.id.menuImage).setOnClickListener {
                dialog.dismiss()
                openImagePicker()
            }
            view.findViewById<View>(R.id.menuVideo).setOnClickListener {
                dialog.dismiss()
                openVideoPicker()
            }
            view.findViewById<View>(R.id.menuFile).setOnClickListener {
                dialog.dismiss()
                openFilePicker()
            }
            dialog.setContentView(view)
            dialog.show()
        }

        btnCancelReply.setOnClickListener { cancelReply() }

        layoutReplyPreview.setOnClickListener {
            replyingMsg?.let { scrollToMessage(it.msgId) }
        }

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            // 引用逻辑：构造引用文本头
            val finalContent = if (replyingMsg != null) {
                val quoteFrom = if(replyingMsg?.senderId == myUserId) "我" else "TA"
                val quoteText = if(replyingMsg?.msgType == "text") replyingMsg?.content else "[媒体文件]"
                "「引用 $quoteFrom: $quoteText」\n---\n$text"
            } else { text }

            sendTextMessage(finalContent, toggleBurn.isChecked, replyingMsg?.msgId)

            etMessage.text.clear()
            toggleBurn.isChecked = false
            cancelReply()
        }
    }

    // 🚀 聊天菜单弹窗方法
    private fun showChatMenuDialog() {
        val options = arrayOf("清空聊天记录", "取消")
        MaterialAlertDialogBuilder(this)
            .setTitle("聊天设置")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        // 🚀 二次确认防止误触
                        MaterialAlertDialogBuilder(this@ChatActivity)
                            .setTitle("⚠️ 确认清空")
                            .setMessage("确定要清空与 TA 的所有本地聊天记录及媒体文件吗？此操作不可撤销。")
                            .setPositiveButton("彻底清空") { _, _ ->
                                performClearChatHistory()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> dialog.dismiss()
                }
            }
            .show()
    }

    // 🚀 执行清空聊天记录逻辑（物理清理文件 + 数据库销毁）
    private fun performClearChatHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 获取所有消息
                val allMsgs = database.chatDao().getAllMessages()

                // 2. 遍历删除媒体文件及数据库记录
                allMsgs.forEach { msg ->
                    // 清理关联的本地媒体文件和解密缓存
                    if (msg.msgType != "text") {
                        try { Uri.parse(msg.content).path?.let { File(it).delete() } } catch (e: Exception) {}
                        try { File(filesDir, "chat_media/dec_${msg.msgId}").delete() } catch (e: Exception) {}
                    }
                    // 调用现有的销毁方法（此方法将隐去 UI 且标记删除）
                    database.chatDao().markAsBurned(msg.msgId)
                }

                // 3. 切换回主线程刷新页面
                withContext(Dispatchers.Main) {
                    loadMessagesFromDb()
                    Toast.makeText(this@ChatActivity, "聊天记录及媒体文件已清空", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "清空失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            data.data?.let { uri ->
                handleSelectedMedia(uri)
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        intent.type = "image/*"
        startActivityForResult(intent, 101)
    }

    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        intent.type = "video/*"
        startActivityForResult(intent, 102)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, 103)
    }

    private fun handleSelectedMedia(sourceUri: Uri) {
        val mimeType = contentResolver.getType(sourceUri) ?: ""
        val fileName = getFileName(sourceUri)
        val duration = if (mimeType.startsWith("video")) getVideoDuration(sourceUri) else ""

        lifecycleScope.launch(Dispatchers.IO) {
            val internalUri = copyUriToInternal(sourceUri)
            withContext(Dispatchers.Main) {
                if (internalUri != null) {
                    val finalInfo = if (duration.isNotEmpty()) "$fileName|$duration" else fileName
                    showMediaConfirmDialog(internalUri, mimeType, finalInfo)
                }
            }
        }
    }

    private fun copyUriToInternal(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val mediaDir = File(filesDir, "chat_media").apply { if (!exists()) mkdirs() }
            val destFile = File(mediaDir, "media_${System.currentTimeMillis()}")
            inputStream.use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } }
            Uri.fromFile(destFile)
        } catch (e: Exception) { null }
    }

    private fun showMediaConfirmDialog(mediaUri: Uri, mimeType: String, fileNameInfo: String) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_image_confirm)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)

        val ivPreview = dialog.findViewById<ImageView>(R.id.ivPreview)
        val toggleDialogBurn = dialog.findViewById<ToggleButton>(R.id.toggleDialogBurn)
        val btnConfirmSend = dialog.findViewById<Button>(R.id.btnConfirmSend)
        val isVideo = mimeType.startsWith("video")
        val isImage = mimeType.startsWith("image")

        if (isImage) {
            ivPreview.setImageURI(mediaUri)
            btnConfirmSend.text = "发送图片"
        } else if (isVideo) {
            ivPreview.setImageResource(android.R.drawable.presence_video_online)
            val duration = fileNameInfo.split("|").lastOrNull() ?: ""
            btnConfirmSend.text = "发送视频 ($duration)"
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_menu_save)
            val realName = fileNameInfo.split("|").firstOrNull() ?: "文件"
            btnConfirmSend.text = "发送文件: $realName"
        }

        updateBurnToggleUI(toggleDialogBurn, false)
        toggleDialogBurn.setOnCheckedChangeListener { _, isChecked ->
            updateBurnToggleUI(toggleDialogBurn, isChecked)
        }

        btnConfirmSend.setOnClickListener {
            val type = if(isImage) "image" else if(isVideo) "video" else "file"
            btnConfirmSend.text = "安全传输中..."
            btnConfirmSend.isEnabled = false
            sendMediaMessage(mediaUri, toggleDialogBurn.isChecked, type, fileNameInfo, replyingMsg?.msgId)
            dialog.dismiss()
            cancelReply()
        }
        dialog.show()
    }

    private fun sendMediaMessage(mediaUri: Uri, isBurn: Boolean, type: String, fileNameInfo: String, replyMsgId: String?) {
        val msgId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val serverName = fileNameInfo.split("|")[0]

        val entity = ChatEntity(
            msgId = msgId, senderId = myUserId, receiverId = partnerId,
            msgType = type, content = mediaUri.toString(), originalName = fileNameInfo,
            isBurn = isBurn, timestamp = timestamp, isRead = false,
            replyToMsgId = replyMsgId, status = 0
        )

        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().insertMessage(entity)
            withContext(Dispatchers.Main) { loadMessagesFromDb() }

            try {
                val sourceFile = File(mediaUri.path!!)
                val encFile = File(cacheDir, "up_enc_${msgId}")
                CryptoUtil.encryptFile(sourceFile, encFile, this@ChatActivity)
                val hash = CryptoUtil.getSha256(encFile)

                val serverUrl = UserPrefs.getServerUrl(this@ChatActivity)
                val apiUrl = "${serverUrl}love_api/api_media.php"
                val client = okhttp3.OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

                val checkBody = okhttp3.FormBody.Builder().add("action", "check_hash").add("hash", hash).build()
                val checkRes = client.newCall(okhttp3.Request.Builder().url(apiUrl).post(checkBody).build()).execute()
                val checkJson = JSONObject(checkRes.body()?.string() ?: "{}")

                var finalFileUrl = ""
                if (checkJson.optBoolean("exists", false)) {
                    finalFileUrl = checkJson.optString("file_url")
                } else {
                    val reqBody = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("action", "upload_file")
                        .addFormDataPart("user_id", myUserId.toString())
                        .addFormDataPart("hash", hash)
                        .addFormDataPart("original_name", serverName)
                        .addFormDataPart("file", serverName, okhttp3.RequestBody.create(null, encFile))
                        .build()
                    val uploadRes = client.newCall(okhttp3.Request.Builder().url(apiUrl).post(reqBody).build()).execute()
                    val uploadJson = JSONObject(uploadRes.body()?.string() ?: "{}")
                    finalFileUrl = uploadJson.optString("file_url")
                }

                encFile.delete()

                if (finalFileUrl.isNotEmpty() && WebSocketManager.isConnected) {
                    val payload = JSONObject().apply {
                        put("msg_id", msgId); put("msg_type", type); put("content", finalFileUrl)
                        put("original_name", fileNameInfo); put("is_burn", if (isBurn) 1 else 0)
                        put("timestamp", timestamp); replyMsgId?.let { put("reply_to", it) }
                    }
                    WebSocketManager.sendMessage("chat_msg", partnerId, "chat_msg", payload)
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun sendTextMessage(content: String, isBurn: Boolean, replyMsgId: String?) {
        val msgId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val entity = ChatEntity(
            msgId = msgId, senderId = myUserId, receiverId = partnerId,
            msgType = "text", content = content, originalName = "",
            isBurn = isBurn, timestamp = timestamp, isRead = false,
            replyToMsgId = replyMsgId, status = 0
        )

        lifecycleScope.launch(Dispatchers.IO) {
            database.chatDao().insertMessage(entity)
            withContext(Dispatchers.Main) { loadMessagesFromDb() }
        }

        if (WebSocketManager.isConnected) {
            val payload = JSONObject().apply {
                put("msg_id", msgId); put("msg_type", "text"); put("content", content)
                put("is_burn", if (isBurn) 1 else 0); put("timestamp", timestamp)
                replyMsgId?.let { put("reply_to", it) }
            }
            WebSocketManager.sendMessage("chat_msg", partnerId, "chat_msg", payload)
        }
    }

    private fun loadMessagesFromDb() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = database.chatDao().getAllMessages()
            val unreadIds = list.filter { it.senderId == partnerId && it.status != 3 }.map { it.msgId }
            if (unreadIds.isNotEmpty()) {
                unreadIds.forEach { database.chatDao().updateMessageStatus(it, 3) }
                if (WebSocketManager.isConnected) {
                    WebSocketManager.sendMessage("send_to_partner", partnerId, "read_ack", JSONObject().apply { put("msg_ids", JSONArray(unreadIds)) })
                }
            }
            withContext(Dispatchers.Main) {
                if (!isDestroyed && !isFinishing) {
                    adapter.submitList(list)
                    if (list.isNotEmpty()) rvChat.scrollToPosition(list.size - 1)
                }
            }
        }
    }

    private fun showFullScreenImage(imagePath: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageURI(Uri.parse(imagePath))
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(imageView)
        dialog.show()
    }

    private fun scrollToMessage(targetMsgId: String) {
        val position = adapter.findPositionById(targetMsgId)
        if (position != -1) {
            rvChat.smoothScrollToPosition(position)
            adapter.triggerHighlight(targetMsgId)
        }
    }

    private fun updateBurnToggleUI(toggle: ToggleButton, isChecked: Boolean) {
        if (isChecked) {
            toggle.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FFCDD2")) }
            toggle.alpha = 1.0f
        } else {
            val outValue = android.util.TypedValue()
            toggle.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            toggle.setBackgroundResource(outValue.resourceId); toggle.alpha = 0.5f
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) result = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: 0)
            }
        }
        return result ?: "file_${System.currentTimeMillis()}"
    }

    private fun getVideoDuration(uri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            String.format("%02d:%02d", (time / 1000) / 60, (time / 1000) % 60)
        } catch (e: Exception) { "" } finally { try { retriever.release() } catch (e: Exception) {} }
    }

    private fun handleSecureLock(isLock: Boolean) {
        if (isLock) {
            secureLockCount++
            if (secureLockCount == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            secureLockCount--
            if (secureLockCount <= 0) { secureLockCount = 0; window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    private fun handleMessageBurned(msg: ChatEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (msg.msgType != "text") {
                try { Uri.parse(msg.content).path?.let { File(it).delete() } } catch (e: Exception) {}
            }
            database.chatDao().markAsBurned(msg.msgId)
            withContext(Dispatchers.Main) { loadMessagesFromDb() }
        }
    }

    private fun pushUnsentMessages() {
        if (!WebSocketManager.isConnected) return
        lifecycleScope.launch(Dispatchers.IO) {
            val unsent = database.chatDao().getAllMessages().filter { it.status == 0 && it.senderId == myUserId }
            unsent.forEach { msg ->
                if (msg.msgType != "text" && !msg.content.startsWith("http")) return@forEach
                val payload = JSONObject().apply {
                    put("msg_id", msg.msgId); put("msg_type", msg.msgType); put("content", msg.content)
                    put("original_name", msg.originalName); put("is_burn", if (msg.isBurn) 1 else 0)
                    put("timestamp", msg.timestamp); msg.replyToMsgId?.let { put("reply_to", it) }
                }
                WebSocketManager.sendMessage("chat_msg", partnerId, "chat_msg", payload)
                delay(100)
            }
        }
    }

    private fun setupReply(msg: ChatEntity) {
        replyingMsg = msg
        layoutReplyPreview.visibility = View.VISIBLE
        tvReplyContent.text = if (msg.msgType == "text") msg.content else "[媒体文件]"
        etMessage.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .showSoftInput(etMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReply() { replyingMsg = null; layoutReplyPreview.visibility = View.GONE }

    private fun clearMessageNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(MSG_NOTIF_ID)
    }

    override fun onResume() { super.onResume(); clearMessageNotification() }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.forceBurnAllActive()
        super.onDestroy()
        unregisterReceiver(messageReceiver)
    }
}