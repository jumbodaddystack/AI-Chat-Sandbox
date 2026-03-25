package com.aichat.sandbox.data.model

data class ModelPricing(
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double
) {
    fun estimateCost(promptTokens: Int, completionTokens: Int): Double {
        return (promptTokens * inputPricePerMillion / 1_000_000.0) +
            (completionTokens * outputPricePerMillion / 1_000_000.0)
    }

    companion object {
        private val pricingTable = listOf(
            // OpenAI GPT-4.1 series
            "gpt-4.1-nano" to ModelPricing(0.10, 0.40),
            "gpt-4.1-mini" to ModelPricing(0.40, 1.60),
            "gpt-4.1" to ModelPricing(2.00, 8.00),
            // OpenAI GPT-4o series
            "gpt-4o-mini" to ModelPricing(0.15, 0.60),
            "gpt-4o" to ModelPricing(2.50, 10.00),
            // OpenAI reasoning models
            "o3-mini" to ModelPricing(1.10, 4.40),
            "o3" to ModelPricing(2.00, 8.00),
            "o4-mini" to ModelPricing(1.10, 4.40),
            // Legacy OpenAI
            "gpt-4-turbo" to ModelPricing(10.00, 30.00),
            "gpt-4" to ModelPricing(30.00, 60.00),
            "gpt-3.5" to ModelPricing(0.50, 1.50),
            // Anthropic Claude
            "claude-opus-4-6" to ModelPricing(15.00, 75.00),
            "claude-sonnet-4-6" to ModelPricing(3.00, 15.00),
            "claude-opus" to ModelPricing(15.00, 75.00),
            "claude-sonnet" to ModelPricing(3.00, 15.00),
            "claude-haiku" to ModelPricing(0.80, 4.00),
            "claude-3-5-sonnet" to ModelPricing(3.00, 15.00),
            "claude-3-opus" to ModelPricing(15.00, 75.00),
            "claude-3-haiku" to ModelPricing(0.25, 1.25),
            // Google Gemini
            "gemini-2.5-pro" to ModelPricing(1.25, 10.00),
            "gemini-2.5-flash" to ModelPricing(0.15, 0.60),
            "gemini-2.0-flash" to ModelPricing(0.10, 0.40)
        )

        private val defaultPricing = ModelPricing(2.50, 10.00)

        fun forModel(model: String): ModelPricing {
            return pricingTable.firstOrNull { model.contains(it.first) }?.second
                ?: defaultPricing
        }
    }
}
