package cn.xtay.lovejournal.model.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val msgId: String,          // 唯一消息ID (作为时间戳验证码)
    val senderId: Int,          // 发送方ID
    val receiverId: Int,        // 接收方ID
    val msgType: String,        // text, image, video, file, voice
    val content: String,        // 文字内容 或 本地文件路径
    val originalName: String,   // 文件的原始名称/视频时长
    val isBurn: Boolean,        // 是否阅后即焚
    val timestamp: Long,        // 时间戳
    val isRead: Boolean = false,// 是否已读/已销毁 (目前主要用于阅后即焚的焚毁标记)

    // ==========================================
    // 🚀 第一战役新增字段：高级通讯协议底层基石
    // ==========================================

    // 1. 引用识别码：如果引用了某条消息，这里存那条消息的 msgId。允许为空。
    val replyToMsgId: String? = null,

    // 2. 消息状态机：追踪消息全生命周期
    // 0 = 发送中 (转圈)
    // 1 = 暂存服务器/待送达 (单灰勾)
    // 2 = 已送达对方设备 (双灰勾)
    // 3 = 对方已打开并阅读 (双蓝勾)
    val status: Int = 0
)