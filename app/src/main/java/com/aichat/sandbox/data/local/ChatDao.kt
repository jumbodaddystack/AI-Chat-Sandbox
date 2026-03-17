package com.aichat.sandbox.data.local

import androidx.room.*
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun getAllChats(): Flow<List<Chat>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): Chat?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatByIdFlow(chatId: String): Flow<Chat?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: Chat)

    @Update
    suspend fun updateChat(chat: Chat)

    @Delete
    suspend fun deleteChat(chat: Chat)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun getMessagesForChat(chatId: String): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Delete
    suspend fun deleteMessage(message: Message)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun clearChatHistory(chatId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun getMessageCount(chatId: String): Int
}
