package cn.xtay.lovejournal.model.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatDao {
    // 🚀 终极修复：KSP 要求协程的 Insert 必须返回 Long (插入的行ID)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(chatEntity: ChatEntity): Long

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<ChatEntity>

    @Query("DELETE FROM chat_messages WHERE msgId = :msgId")
    suspend fun deleteMessageByMsgId(msgId: String): Int

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll(): Int

    @Query("UPDATE chat_messages SET isRead = 1, content = '' WHERE msgId = :msgId")
    suspend fun markAsBurned(msgId: String): Int

    // ==========================================
    // 🚀 第一战役新增方法：状态流转与溯源
    // ==========================================

    // 1. 根据服务器或对方的 ACK 回执，更新本地消息的送达/已读状态
    @Query("UPDATE chat_messages SET status = :status WHERE msgId = :msgId")
    suspend fun updateMessageStatus(msgId: String, status: Int): Int

    // 2. 根据引用识别码 (replyToMsgId) 瞬间提取原消息实体，用于跳转定位
    @Query("SELECT * FROM chat_messages WHERE msgId = :msgId LIMIT 1")
    suspend fun getMessageById(msgId: String): ChatEntity?
}