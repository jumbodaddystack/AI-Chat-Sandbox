package com.aichat.sandbox.data.model

data class ChatSettings(
    val temperature: Float = Defaults.TEMPERATURE,
    val topP: Float = Defaults.TOP_P,
    val maxTokens: Int = Defaults.MAX_TOKENS,
    val presencePenalty: Float = Defaults.PRESENCE_PENALTY,
    val frequencyPenalty: Float = Defaults.FREQUENCY_PENALTY
) {
    object Defaults {
        const val MODEL = "gpt-4.1"
        const val TEMPERATURE = 0.1f
        const val TOP_P = 1.0f
        const val MAX_TOKENS = 131072
        const val MAX_TOKENS_LIMIT = 131072f
        const val PRESENCE_PENALTY = 0.0f
        const val FREQUENCY_PENALTY = 0.0f
        const val API_BASE_URL = "https://api.openai.com/v1/"
        const val DARK_MODE = true
    }
}

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
                "gpt-4.1",
                "gpt-4.1-mini",
                "gpt-4.1-nano",
                "gpt-4o",
                "gpt-4o-mini",
                "o3",
                "o3-mini",
                "o4-mini"
            )
        )
        val Anthropic = ApiProvider(
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1/",
            models = listOf(
                "claude-opus-4-6",
                "claude-sonnet-4-6",
                "claude-haiku-4-5-20251001",
                "claude-sonnet-4-20250514",
                "claude-opus-4-20250514"
            )
        )
        val Google = ApiProvider(
            name = "Google",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            models = listOf(
                "gemini-2.5-pro",
                "gemini-2.5-flash",
                "gemini-2.0-flash"
            )
        )

        val defaults = listOf(OpenAI, Anthropic, Google)
    }
}
