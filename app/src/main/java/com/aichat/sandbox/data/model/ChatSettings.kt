package com.aichat.sandbox.data.model

data class ChatSettings(
    val temperature: Float = 0.1f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 131072,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f
)

data class ApiProvider(
    val name: String,
    val baseUrl: String,
    val models: List<String>
) {
    companion object {
        val OpenAI = ApiProvider(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1/",
            models = listOf(
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4-turbo",
                "gpt-4",
                "gpt-3.5-turbo",
                "o1-preview",
                "o1-mini"
            )
        )
        val Anthropic = ApiProvider(
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1/",
            models = listOf(
                "claude-opus-4-20250514",
                "claude-sonnet-4-20250514",
                "claude-haiku-4-5-20251001",
                "claude-3-5-sonnet-20241022",
                "claude-3-opus-20240229"
            )
        )

        val defaults = listOf(OpenAI, Anthropic)
    }
}
