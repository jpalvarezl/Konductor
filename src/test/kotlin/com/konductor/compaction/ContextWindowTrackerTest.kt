package com.konductor.compaction

import com.konductor.core.models.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextWindowTrackerTest {
    private fun usage(total: Int) = Usage(inputTokens = 0, outputTokens = 0, totalTokens = total)

    @Test
    fun `threshold is contextWindow minus reserveTokens`() {
        val tracker = ContextWindowTracker(CompactionSettings(contextWindow = 10_000, reserveTokens = 1_000))
        assertEquals(9_000, tracker.threshold)
    }

    @Test
    fun `threshold is never negative`() {
        val tracker = ContextWindowTracker(CompactionSettings(contextWindow = 100, reserveTokens = 200))
        assertEquals(0, tracker.threshold)
    }

    @Test
    fun `does not compact until the reported size exceeds the threshold`() {
        val tracker = ContextWindowTracker(
            CompactionSettings(enabled = true, contextWindow = 10_000, reserveTokens = 1_000),
        )
        assertFalse(tracker.shouldCompact()) // 0 tokens reported so far

        tracker.update(usage(9_000)) // exactly at the threshold, not over
        assertFalse(tracker.shouldCompact())

        tracker.update(usage(9_001))
        assertTrue(tracker.shouldCompact())
    }

    @Test
    fun `disabled auto-compaction never triggers`() {
        val tracker = ContextWindowTracker(
            CompactionSettings(enabled = false, contextWindow = 100, reserveTokens = 0),
        )
        tracker.update(usage(10_000))
        assertFalse(tracker.shouldCompact())
    }

    @Test
    fun `reset drops the tracked size so it will not immediately re-trigger`() {
        val tracker = ContextWindowTracker(
            CompactionSettings(enabled = true, contextWindow = 100, reserveTokens = 0),
        )
        tracker.update(usage(500))
        assertTrue(tracker.shouldCompact())

        tracker.reset()

        assertEquals(0, tracker.contextTokens)
        assertFalse(tracker.shouldCompact())
    }
}
