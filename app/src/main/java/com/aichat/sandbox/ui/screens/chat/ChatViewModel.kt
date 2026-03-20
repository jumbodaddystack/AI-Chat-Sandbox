package com.aichat.sandbox.ui.screens.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ImageAttachment
import com.aichat.sandbox.data.model.ImageMetadata
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.ModelPricing
import com.aichat.sandbox.data.remote.ApiResult
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.data.repository.ChatRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

data class ChatUiState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val showSettingsPanel: Boolean = false,
    val showSystemMessageDialog: Boolean = false,
    val editingMessageId: String? = null,
    val editingContent: String? = null,
    val attachedImages: List<Uri> = emptyList()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChatRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val gson = Gson()

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

    fun addImage(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages + uri) }
    }

    fun removeImage(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages - uri) }
    }

    fun clearImages() {
        _uiState.update { it.copy(attachedImages = emptyList()) }
    }

    fun sendMessage(content: String) {
        val chat = _uiState.value.chat ?: return
        if (content.isBlank() && _uiState.value.attachedImages.isEmpty()) return
        if (content.length > MAX_MESSAGE_LENGTH) {
            _uiState.update { it.copy(error = "Message too long (max ${MAX_MESSAGE_LENGTH / 1000}K characters)") }
            return
        }

        val imageUris = _uiState.value.attachedImages.toList()

        viewModelScope.launch {
            // Encode images to base64 if present
            val hasImages = imageUris.isNotEmpty()
            var metadata: String? = null
            if (hasImages) {
                val imageAttachments = withContext(Dispatchers.IO) {
                    imageUris.mapNotNull { uri -> encodeImageToBase64(uri) }
                }
                if (imageAttachments.isNotEmpty()) {
                    metadata = gson.toJson(ImageMetadata(images = imageAttachments))
                }
            }

            // Save user message
            val userMessage = Message(
                chatId = chatId,
                role = MessageRole.USER.value,
                content = content.trim(),
                contentType = if (hasImages && metadata != null) "multimodal" else "text",
                metadata = metadata
            )
            repository.insertMessage(userMessage)

            // Clear attached images
            _uiState.update { it.copy(attachedImages = emptyList()) }

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
                val content = response.choices?.firstOrNull()?.message?.content?.toString() ?: ""
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

    private fun encodeImageToBase64(uri: Uri): ImageAttachment? {
        return try {
            val inputStream = appContext.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return null

            // Scale down to max 1024px on longest side
            val maxDim = 1024
            val scaledBitmap = if (originalBitmap.width > maxDim || originalBitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(originalBitmap.width, originalBitmap.height)
                val newWidth = (originalBitmap.width * scale).toInt()
                val newHeight = (originalBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true).also {
                    if (it !== originalBitmap) originalBitmap.recycle()
                }
            } else {
                originalBitmap
            }

            // Compress to JPEG quality 80
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64"

            val attachment = ImageAttachment(
                dataUri = dataUri,
                width = scaledBitmap.width,
                height = scaledBitmap.height
            )
            scaledBitmap.recycle()
            attachment
        } catch (e: Exception) {
            null
        }
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
