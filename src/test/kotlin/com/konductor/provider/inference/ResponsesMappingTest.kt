package com.konductor.provider.inference

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.UserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ResponsesMappingTest {
    private val ts = Instant.parse("2026-07-09T00:00:00Z")

    @Test
    fun `serializeHistory maps a compaction entry to an input item instead of throwing`() {
        val history = listOf(
            UserEntry(Uuid.random(), null, ts, "hi"),
            CompactionEntry(Uuid.random(), null, ts, "## Goal\ndo things", Uuid.random(), 1_000),
            AssistantEntry(Uuid.random(), null, ts, "done"),
        )

        val items = serializeHistory(history)

        // One input item per entry; the compaction entry maps to a summary message (previously it threw).
        assertEquals(3, items.size)
    }
}
