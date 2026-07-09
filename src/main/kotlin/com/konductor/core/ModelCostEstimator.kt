package com.konductor.core

import com.konductor.core.models.Usage
import java.util.Locale

/**
 * Best-effort status-bar cost estimates. Pricing changes outside the codebase, so unknown models deliberately
 * return null and render as "cost n/a" instead of pretending to be authoritative.
 */
object ModelCostEstimator {
    private val pricingByModel = mapOf(
        // USD per 1M tokens; update from provider pricing when the deployed model catalog is known.
        "gpt-5" to ModelPricing(inputUsdPerMillion = 1.25, outputUsdPerMillion = 10.0),
        "gpt-5-mini" to ModelPricing(inputUsdPerMillion = 0.25, outputUsdPerMillion = 2.0),
        "gpt-5-nano" to ModelPricing(inputUsdPerMillion = 0.05, outputUsdPerMillion = 0.4),
    )

    fun estimateUsd(modelName: String?, usage: Usage?): Double? {
        if (modelName == null || usage == null) return null
        val pricing = pricingByModel[modelName.lowercase(Locale.ROOT)] ?: return null
        return (usage.inputTokens * pricing.inputUsdPerMillion + usage.outputTokens * pricing.outputUsdPerMillion) /
            TOKENS_PER_MILLION
    }

    private data class ModelPricing(
        val inputUsdPerMillion: Double,
        val outputUsdPerMillion: Double,
    )

    private const val TOKENS_PER_MILLION = 1_000_000.0
}
