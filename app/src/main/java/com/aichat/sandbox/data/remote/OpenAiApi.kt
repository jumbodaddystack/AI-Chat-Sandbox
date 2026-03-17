package com.aichat.sandbox.data.remote

import com.aichat.sandbox.data.model.ChatCompletionRequest
import com.aichat.sandbox.data.model.ChatCompletionResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming

interface OpenAiApi {
    @POST("chat/completions")
    suspend fun createChatCompletion(
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>

    @Streaming
    @POST("chat/completions")
    suspend fun createChatCompletionStream(
        @Body request: ChatCompletionRequest
    ): Response<ResponseBody>
}
