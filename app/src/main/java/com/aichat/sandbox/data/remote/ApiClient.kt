package com.aichat.sandbox.data.remote

import android.util.Log
import com.aichat.sandbox.BuildConfig
import com.aichat.sandbox.data.model.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
    data object Loading : ApiResult<Nothing>()
}

sealed class StreamEvent {
    data class Delta(val content: String) : StreamEvent()
    data class Complete(val usage: Usage?) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

@Singleton
class ApiClient @Inject constructor() {
    private val gson = Gson()
    private val apiCache = mutableMapOf<String, OpenAiApi>()
    private val retryPolicy = RetryPolicy()

    private fun buildApi(baseUrl: String, apiKey: String): OpenAiApi {
        val cacheKey = "$baseUrl|$apiKey"
        return apiCache.getOrPut(cacheKey) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OpenAiApi::class.java)
        }
    }

    suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null
    ): ApiResult<ChatCompletionResponse> {
        return try {
            val api = buildApi(baseUrl, apiKey)
            val apiMessages = buildApiMessages(chat, messages)
            val request = ChatCompletionRequest(
                model = chat.model,
                messages = apiMessages,
                temperature = chat.temperature,
                topP = chat.topP,
                maxTokens = chat.maxTokens,
                presencePenalty = chat.presencePenalty,
                frequencyPenalty = chat.frequencyPenalty,
                stream = false
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt
            ) {
                api.createChatCompletion(request)
            }
            if (response.isSuccessful) {
                response.body()?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error("Empty response body")
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    gson.fromJson(errorBody, ApiErrorResponse::class.java).error.message
                        ?: "Unknown error"
                } catch (e: Exception) {
                    errorBody ?: "HTTP ${response.code()}"
                }
                ApiResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    fun sendMessageStream(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null
    ): Flow<StreamEvent> = flow {
        try {
            val api = buildApi(baseUrl, apiKey)
            val apiMessages = buildApiMessages(chat, messages)
            val request = ChatCompletionRequest(
                model = chat.model,
                messages = apiMessages,
                temperature = chat.temperature,
                topP = chat.topP,
                maxTokens = chat.maxTokens,
                presencePenalty = chat.presencePenalty,
                frequencyPenalty = chat.frequencyPenalty,
                stream = true
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt
            ) {
                api.createChatCompletionStream(request)
            }
            if (response.isSuccessful) {
                val reader = response.body()?.byteStream()?.bufferedReader()
                    ?: throw Exception("Empty response body")
                processStream(reader, this)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    gson.fromJson(errorBody, ApiErrorResponse::class.java).error.message
                        ?: "Unknown error"
                } catch (e: Exception) {
                    errorBody ?: "HTTP ${response.code()}"
                }
                emit(StreamEvent.Error(errorMsg))
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun processStream(
        reader: BufferedReader,
        collector: kotlinx.coroutines.flow.FlowCollector<StreamEvent>
    ) {
        reader.use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                kotlinx.coroutines.coroutineContext.ensureActive()
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        collector.emit(StreamEvent.Complete(null))
                        return
                    }
                    try {
                        val chunk = gson.fromJson(data, ChatCompletionResponse::class.java)
                        val content = chunk.choices?.firstOrNull()?.delta?.content
                        if (content != null) {
                            collector.emit(StreamEvent.Delta(content))
                        }
                        if (chunk.usage != null) {
                            collector.emit(StreamEvent.Complete(chunk.usage))
                        }
                    } catch (e: JsonSyntaxException) {
                        Log.w("ApiClient", "Skipping malformed stream chunk: ${data.take(100)}", e)
                    }
                }
            }
            collector.emit(StreamEvent.Complete(null))
        }
    }

    private fun buildApiMessages(chat: Chat, messages: List<Message>): List<ApiMessage> {
        val apiMessages = mutableListOf<ApiMessage>()
        if (chat.systemMessage.isNotBlank()) {
            apiMessages.add(ApiMessage(role = "system", content = chat.systemMessage))
        }
        messages.forEach { msg ->
            apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
        }
        return apiMessages
    }
}
