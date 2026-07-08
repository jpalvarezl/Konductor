package com.konductor.session

import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.UserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionHistoryTest {
    private val ts = Instant.parse("2026-07-08T10:00:00Z")

    private fun user(text: String) = UserEntry(Uuid.random(), null, ts, text)

    @Test
    fun `no compaction returns the full transcript unchanged`() {
        val entries = listOf(user("a"), user("b"), user("c"))
        assertEquals(entries, reconstructHistory(entries))
    }

    @Test
    fun `compaction keeps the summary and entries from firstKeptEntryId onward`() {
        val old1 = user("old1")
        val old2 = user("old2")
        val keep = user("keep")
        val after = user("after")
        // Layout: [summarized..., compaction marker, kept...].
        val compaction = CompactionEntry(Uuid.random(), null, ts, "summary", keep.id, 1_000)
        val all = listOf(old1, old2, compaction, keep, after)

        assertEquals(listOf(compaction, keep, after), reconstructHistory(all))
    }

    @Test
    fun `only the latest compaction is honored`() {
        val old = user("old")
        val k1 = user("k1")
        val comp1 = CompactionEntry(Uuid.random(), null, ts, "first", k1.id, 1_000)
        val k2 = user("k2")
        val comp2 = CompactionEntry(Uuid.random(), null, ts, "second", k2.id, 2_000)
        val tail = user("tail")
        val all = listOf(old, comp1, k1, comp2, k2, tail)

        assertEquals(listOf(comp2, k2, tail), reconstructHistory(all))
    }
}
