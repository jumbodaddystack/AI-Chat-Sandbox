package com.aichat.sandbox.data.model

import com.google.gson.annotations.SerializedName

// OpenAI API request/response models
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Float? = null,
    @SerializedName("top_p")
    val topP: Float? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("presence_penalty")
    val presencePenalty: Float? = null,
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Float? = null,
    val stream: Boolean = false
)

data class ApiMessage(
    val role: String,
    val content: String
)

data class ChatCompletionResponse(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>?,
    val usage: Usage?,
    val error: ApiError?
)

data class Choice(
    val index: Int?,
    val message: ApiMessage?,
    val delta: ApiMessage?,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,
    @SerializedName("completion_tokens")
    val completionTokens: Int,
    @SerializedName("total_tokens")
    val totalTokens: Int
)

data class ApiError(
    val message: String?,
    val type: String?,
    val code: String?
)

data class ApiErrorResponse(
    val error: ApiError
)
