package com.konductor.core

import com.konductor.core.models.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelCostEstimatorTest {
    @Test
    fun `estimates known model cost from input and output tokens`() {
        val usage = Usage(inputTokens = 1_000_000, outputTokens = 500_000, totalTokens = 1_500_000)

        assertEquals(6.25, ModelCostEstimator.estimateUsd("GPT-5", usage))
    }

    @Test
    fun `unknown models render as unavailable instead of guessing`() {
        assertNull(ModelCostEstimator.estimateUsd("custom-deployment", Usage(1, 1, 2)))
    }
}
