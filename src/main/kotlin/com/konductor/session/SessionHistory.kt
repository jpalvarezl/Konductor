package com.konductor.session

import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry

/**
 * Reconstruct the transcript that a Prompt turn should re-send, honoring compaction.
 *
 * The latest [CompactionEntry] is the active summary boundary: entries before its `firstKeptEntryId` are replaced by
 * that summary, so reconstruction returns the compaction entry plus entries from `firstKeptEntryId` onward. Without a
 * compaction entry this returns the full transcript unchanged. If the kept-entry reference is missing or points at or
 * before the marker, reconstruction clamps the slice to the first entry after the marker.
 * See `docs/spec/sessions.md#reconstructing-responses-input`.
 */
fun reconstructHistory(entries: List<Entry>): List<Entry> {
    val compactionIndex = entries.indexOfLast { it is CompactionEntry }
    if (compactionIndex < 0) return entries.toList()

    val compaction = entries[compactionIndex] as CompactionEntry
    // Kept entries always start *after* the compaction marker. If firstKeptEntryId is missing or resolves to an
    // entry at/before the marker (a malformed transcript), clamp to just after it — never re-include the marker
    // itself or entries that were meant to be summarized away.
    val located = entries.indexOfFirst { it.id == compaction.firstKeptEntryId }
    val keptStart = (if (located > compactionIndex) located else compactionIndex + 1).coerceAtMost(entries.size)
    return listOf(compaction) + entries.subList(keptStart, entries.size)
}
