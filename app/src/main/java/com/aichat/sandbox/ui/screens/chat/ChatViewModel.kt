package com.aichat.sandbox.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.ModelPricing
import com.aichat.sandbox.data.remote.ApiResult
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.data.repository.ChatRepository
import com.google.gson.GsonBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val showSettingsPanel: Boolean = false,
    val showSystemMessageDialog: Boolean = false,
    val retryAttempt: Int = 0,
    val editingMessageId: String? = null,
    val editingContent: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChatRepository
) : ViewModel() {

    private val chatId: String = savedStateHandle.get<String>("chatId") ?: ""

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getChatById(chatId).collect { chat ->
                _uiState.update { it.copy(chat = chat) }
            }
        }
        viewModelScope.launch {
            repository.getMessagesForChat(chatId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun sendMessage(content: String) {
        val chat = _uiState.value.chat ?: return
        if (content.isBlank()) return
        if (content.length > MAX_MESSAGE_LENGTH) {
            _uiState.update { it.copy(error = "Message too long (max ${MAX_MESSAGE_LENGTH / 1000}K characters)") }
            return
        }

        viewModelScope.launch {
            // Save user message
            val userMessage = Message(
                chatId = chatId,
                role = MessageRole.USER.value,
                content = content.trim()
            )
            repository.insertMessage(userMessage)

            // Update chat title if it's the first message
            if (_uiState.value.messages.isEmpty()) {
                val title = content.trim().take(40)
                repository.updateChat(chat.copy(title = title))
            }

            _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "", retryAttempt = 0) }

            // Get all messages including the new one
            val allMessages = _uiState.value.messages + userMessage

            // Use streaming
            streamJob = viewModelScope.launch {
                val streamContent = StringBuilder()
                val onRetry: (Int) -> Unit = { attempt ->
                    _uiState.update { it.copy(retryAttempt = attempt) }
                }
                repository.sendMessageStream(chat, allMessages, onRetry).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            streamContent.append(event.content)
                            _uiState.update { it.copy(streamingContent = streamContent.toString()) }
                        }
                        is StreamEvent.Complete -> {
                            val assistantMessage = Message(
                                chatId = chatId,
                                role = MessageRole.ASSISTANT.value,
                                content = streamContent.toString(),
                                tokenCount = event.usage?.totalTokens ?: estimateTokens(streamContent.toString())
                            )
                            repository.insertMessage(assistantMessage)

                            // Update token count and cost
                            val totalTokens = (event.usage?.totalTokens ?: estimateTokens(streamContent.toString()))
                            val cost = estimateCost(chat.model, event.usage?.promptTokens ?: 0, event.usage?.completionTokens ?: 0)
                            repository.updateChat(chat.copy(
                                totalTokens = chat.totalTokens + totalTokens,
                                totalCost = chat.totalCost + cost
                            ))

                            _uiState.update { it.copy(isLoading = false, streamingContent = "", retryAttempt = 0) }
                        }
                        is StreamEvent.Error -> {
                            // If streaming fails, fall back to non-streaming
                            handleNonStreamingFallback(chat, allMessages)
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleNonStreamingFallback(chat: Chat, messages: List<Message>) {
        val onRetry: (Int) -> Unit = { attempt ->
            _uiState.update { it.copy(retryAttempt = attempt) }
        }
        when (val result = repository.sendMessage(chat, messages, onRetry)) {
            is ApiResult.Success -> {
                val response = result.data
                val content = response.choices?.firstOrNull()?.message?.content ?: ""
                val usage = response.usage

                val assistantMessage = Message(
                    chatId = chatId,
                    role = MessageRole.ASSISTANT.value,
                    content = content,
                    tokenCount = usage?.totalTokens ?: estimateTokens(content)
                )
                repository.insertMessage(assistantMessage)

                val totalTokens = usage?.totalTokens ?: estimateTokens(content)
                val cost = estimateCost(chat.model, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0)
                repository.updateChat(chat.copy(
                    totalTokens = chat.totalTokens + totalTokens,
                    totalCost = chat.totalCost + cost
                ))

                _uiState.update { it.copy(isLoading = false, streamingContent = "", retryAttempt = 0) }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(isLoading = false, error = result.message, streamingContent = "", retryAttempt = 0) }
            }
            is ApiResult.Loading -> {}
        }
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamJob = null
        val streamingContent = _uiState.value.streamingContent
        if (streamingContent.isNotEmpty()) {
            viewModelScope.launch {
                val assistantMessage = Message(
                    chatId = chatId,
                    role = MessageRole.ASSISTANT.value,
                    content = streamingContent
                )
                repository.insertMessage(assistantMessage)
            }
        }
        _uiState.update { it.copy(isLoading = false, streamingContent = "") }
    }

    fun regenerateLastResponse() {
        val chat = _uiState.value.chat ?: return
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        // Find the last assistant message
        val lastMessage = messages.last()
        if (lastMessage.role != MessageRole.ASSISTANT.value) return

        viewModelScope.launch {
            // Delete the last assistant message
            repository.deleteMessage(lastMessage)

            // Re-send using the remaining message history (which ends with the user message)
            val remainingMessages = messages.dropLast(1)
            if (remainingMessages.isEmpty()) return@launch

            _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "", retryAttempt = 0) }

            streamJob = viewModelScope.launch {
                val streamContent = StringBuilder()
                val onRetry: (Int) -> Unit = { attempt ->
                    _uiState.update { it.copy(retryAttempt = attempt) }
                }
                repository.sendMessageStream(chat, remainingMessages, onRetry).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            streamContent.append(event.content)
                            _uiState.update { it.copy(streamingContent = streamContent.toString()) }
                        }
                        is StreamEvent.Complete -> {
                            val assistantMessage = Message(
                                chatId = chatId,
                                role = MessageRole.ASSISTANT.value,
                                content = streamContent.toString(),
                                tokenCount = event.usage?.totalTokens ?: estimateTokens(streamContent.toString())
                            )
                            repository.insertMessage(assistantMessage)

                            val totalTokens = (event.usage?.totalTokens ?: estimateTokens(streamContent.toString()))
                            val cost = estimateCost(chat.model, event.usage?.promptTokens ?: 0, event.usage?.completionTokens ?: 0)
                            repository.updateChat(chat.copy(
                                totalTokens = chat.totalTokens + totalTokens,
                                totalCost = chat.totalCost + cost
                            ))

                            _uiState.update { it.copy(isLoading = false, streamingContent = "", retryAttempt = 0) }
                        }
                        is StreamEvent.Error -> {
                            handleNonStreamingFallback(chat, remainingMessages)
                        }
                    }
                }
            }
        }
    }

    fun startEditing(message: Message) {
        _uiState.update { it.copy(editingMessageId = message.id, editingContent = message.content) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessageId = null, editingContent = null) }
    }

    fun submitEdit(newContent: String) {
        val chat = _uiState.value.chat ?: return
        val editingId = _uiState.value.editingMessageId ?: return
        if (newContent.isBlank()) return

        val editedMessage = _uiState.value.messages.find { it.id == editingId } ?: return

        viewModelScope.launch {
            // Delete all messages at or after the edited message's timestamp
            // (this removes the old version and any subsequent assistant replies)
            repository.deleteMessagesFrom(chatId, editedMessage.createdAt)

            // Clear editing state
            _uiState.update { it.copy(editingMessageId = null, editingContent = null) }

            // Send the edited content as a new message (reuses existing sendMessage flow)
            sendMessage(newContent.trim())
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            repository.deleteMessage(message)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearChatHistory(chatId)
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(totalTokens = 0, totalCost = 0.0))
            }
        }
    }

    fun updateModel(model: String) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(model = model))
            }
        }
    }

    fun updateSystemMessage(message: String) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(systemMessage = message))
            }
        }
    }

    fun updateTemperature(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(temperature = value))
            }
        }
    }

    fun updateTopP(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(topP = value))
            }
        }
    }

    fun updateMaxTokens(value: Int) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(maxTokens = value))
            }
        }
    }

    fun updatePresencePenalty(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(presencePenalty = value))
            }
        }
    }

    fun updateFrequencyPenalty(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(frequencyPenalty = value))
            }
        }
    }

    fun toggleSettingsPanel() {
        _uiState.update { it.copy(showSettingsPanel = !it.showSettingsPanel) }
    }

    fun toggleSystemMessageDialog() {
        _uiState.update { it.copy(showSystemMessageDialog = !it.showSystemMessageDialog) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun getShareContentAsMarkdown(): String {
        val chat = _uiState.value.chat ?: return ""
        val messages = _uiState.value.messages
        val sb = StringBuilder()
        sb.appendLine("## ${chat.title}")
        sb.appendLine("Model: ${chat.model}\n")
        messages.forEach { msg ->
            val role = if (msg.role == "user") "**User**" else "**Assistant**"
            sb.appendLine("$role:\n${msg.content}\n")
        }
        return sb.toString()
    }

    fun getShareContentAsJson(): String {
        val chat = _uiState.value.chat ?: return "{}"
        val messages = _uiState.value.messages
        val data = mapOf(
            "title" to chat.title,
            "model" to chat.model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) }
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4.0).toInt()
    }

    private fun estimateCost(model: String, promptTokens: Int, completionTokens: Int): Double {
        return ModelPricing.forModel(model).estimateCost(promptTokens, completionTokens)
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 100_000
    }
}
