package com.konductor.session

import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry

/**
 * Reconstruct the transcript that a Prompt turn should re-send, honoring compaction.
 *
 * When the transcript contains a [CompactionEntry], everything before its `firstKeptEntryId` has been
 * summarized: we drop those entries and keep the compaction entry (its summary) plus the entries from
 * `firstKeptEntryId` onward. Without a compaction entry this is the identity — the full transcript.
 *
 * M3 never produces a [CompactionEntry] (that lands in M4), so this is the identity today; it is written
 * compaction-aware now so M4 only has to add the summary->input-item mapping in `AzureInferenceClient`,
 * not the slicing. See `docs/spec/sessions.md#reconstructing-responses-input`.
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
