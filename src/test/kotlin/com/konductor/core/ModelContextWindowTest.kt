package com.konductor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelContextWindowTest {
    @Test
    fun `resolves model families by longest matching prefix`() {
        assertEquals(400_000, ModelContextWindow.forModel("gpt-5"))
        assertEquals(400_000, ModelContextWindow.forModel("gpt-5.2")) // Foundry version suffix
        assertEquals(400_000, ModelContextWindow.forModel("gpt-5-mini"))
        assertEquals(1_047_576, ModelContextWindow.forModel("gpt-4.1")) // not the shorter gpt-4o entry
        assertEquals(128_000, ModelContextWindow.forModel("gpt-4o-mini"))
        assertEquals(200_000, ModelContextWindow.forModel("o3-mini"))
    }

    @Test
    fun `is case-insensitive`() {
        assertEquals(400_000, ModelContextWindow.forModel("GPT-5.2"))
    }

    @Test
    fun `returns null for unknown or missing models so the caller can fall back`() {
        assertNull(ModelContextWindow.forModel("custom-deployment"))
        assertNull(ModelContextWindow.forModel(null))
    }
}
