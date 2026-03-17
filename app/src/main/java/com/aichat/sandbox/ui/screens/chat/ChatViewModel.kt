package com.aichat.sandbox.ui.screens.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
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
    val showSystemMessageDialog: Boolean = false
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

            _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "") }

            // Get all messages including the new one
            val allMessages = _uiState.value.messages + userMessage

            // Use streaming
            streamJob = viewModelScope.launch {
                val streamContent = StringBuilder()
                repository.sendMessageStream(chat, allMessages).collect { event ->
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

                            _uiState.update { it.copy(isLoading = false, streamingContent = "") }
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
        when (val result = repository.sendMessage(chat, messages)) {
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

                _uiState.update { it.copy(isLoading = false, streamingContent = "") }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(isLoading = false, error = result.message, streamingContent = "") }
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
        // Approximate pricing per 1M tokens
        val (inputPrice, outputPrice) = when {
            model.contains("gpt-4o-mini") -> 0.15 to 0.60
            model.contains("gpt-4o") -> 2.50 to 10.00
            model.contains("gpt-4-turbo") -> 10.00 to 30.00
            model.contains("gpt-4") -> 30.00 to 60.00
            model.contains("gpt-3.5") -> 0.50 to 1.50
            model.contains("o1-preview") -> 15.00 to 60.00
            model.contains("o1-mini") -> 3.00 to 12.00
            model.contains("claude-3-opus") -> 15.00 to 75.00
            model.contains("claude-3-5-sonnet") || model.contains("claude-sonnet") -> 3.00 to 15.00
            model.contains("claude-3-haiku") || model.contains("claude-haiku") -> 0.25 to 1.25
            model.contains("claude-opus") -> 15.00 to 75.00
            else -> 2.50 to 10.00
        }
        return (promptTokens * inputPrice / 1_000_000.0) + (completionTokens * outputPrice / 1_000_000.0)
    }
}
