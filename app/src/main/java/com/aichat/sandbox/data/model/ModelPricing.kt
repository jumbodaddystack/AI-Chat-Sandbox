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
            "gpt-4o-mini" to ModelPricing(0.15, 0.60),
            "gpt-4o" to ModelPricing(2.50, 10.00),
            "gpt-4-turbo" to ModelPricing(10.00, 30.00),
            "gpt-4" to ModelPricing(30.00, 60.00),
            "gpt-3.5" to ModelPricing(0.50, 1.50),
            "o1-preview" to ModelPricing(15.00, 60.00),
            "o1-mini" to ModelPricing(3.00, 12.00),
            "claude-3-opus" to ModelPricing(15.00, 75.00),
            "claude-3-5-sonnet" to ModelPricing(3.00, 15.00),
            "claude-sonnet" to ModelPricing(3.00, 15.00),
            "claude-3-haiku" to ModelPricing(0.25, 1.25),
            "claude-haiku" to ModelPricing(0.25, 1.25),
            "claude-opus" to ModelPricing(15.00, 75.00)
        )

        private val defaultPricing = ModelPricing(2.50, 10.00)

        fun forModel(model: String): ModelPricing {
            return pricingTable.firstOrNull { model.contains(it.first) }?.second
                ?: defaultPricing
        }
    }
}
