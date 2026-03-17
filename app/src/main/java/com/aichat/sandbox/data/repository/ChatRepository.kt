package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.ChatDao
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.remote.ApiClient
import com.aichat.sandbox.data.remote.ApiResult
import com.aichat.sandbox.data.remote.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val apiClient: ApiClient,
    private val preferencesManager: PreferencesManager
) {
    fun getAllChats(): Flow<List<Chat>> = chatDao.getAllChats()

    fun getChatById(chatId: String): Flow<Chat?> = chatDao.getChatByIdFlow(chatId)

    fun getMessagesForChat(chatId: String): Flow<List<Message>> =
        chatDao.getMessagesForChat(chatId)

    suspend fun createChat(): Chat {
        val model = preferencesManager.defaultModel.first()
        val temperature = preferencesManager.defaultTemperature.first()
        val topP = preferencesManager.defaultTopP.first()
        val maxTokens = preferencesManager.defaultMaxTokens.first()
        val presencePenalty = preferencesManager.defaultPresencePenalty.first()
        val frequencyPenalty = preferencesManager.defaultFrequencyPenalty.first()

        val chat = Chat(
            model = model,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty
        )
        chatDao.insertChat(chat)
        return chat
    }

    suspend fun updateChat(chat: Chat) {
        chatDao.updateChat(chat.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteChat(chatId: String) {
        chatDao.deleteChatById(chatId)
    }

    suspend fun insertMessage(message: Message) {
        chatDao.insertMessage(message)
    }

    suspend fun deleteMessage(message: Message) {
        chatDao.deleteMessage(message)
    }

    suspend fun clearChatHistory(chatId: String) {
        chatDao.clearChatHistory(chatId)
    }

    suspend fun sendMessage(chat: Chat, messages: List<Message>): ApiResult<com.aichat.sandbox.data.model.ChatCompletionResponse> {
        val apiKey = preferencesManager.apiKey.first()
        val baseUrl = preferencesManager.apiBaseUrl.first()
        return apiClient.sendMessage(baseUrl, apiKey, chat, messages)
    }

    fun sendMessageStream(chat: Chat, messages: List<Message>): Flow<StreamEvent> {
        val apiKey: String
        val baseUrl: String
        // We need these synchronously for the flow builder, so we'll pass them through
        return kotlinx.coroutines.flow.flow {
            val key = preferencesManager.apiKey.first()
            val url = preferencesManager.apiBaseUrl.first()
            apiClient.sendMessageStream(url, key, chat, messages).collect { emit(it) }
        }
    }
}
