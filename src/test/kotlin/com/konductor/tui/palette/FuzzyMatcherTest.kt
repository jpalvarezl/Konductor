package com.konductor.tui.palette

import kotlin.test.Test
import kotlin.test.assertEquals

class FuzzyMatcherTest {
    @Test
    fun ranksPrefixesAndGaps() {
        val candidates = listOf("/connections", "/compact", "/model", "/new")

        assertEquals(listOf("/compact", "/connections"), FuzzyMatcher.rank("co", candidates) { listOf(it) })
        assertEquals(listOf("/model"), FuzzyMatcher.rank("mdl", candidates) { listOf(it) })
    }

    @Test
    fun preservesSourceOrderOnTies() {
        val candidates = listOf("/cat", "/car", "/can")

        assertEquals(candidates, FuzzyMatcher.rank("ca", candidates) { listOf(it) })
        assertEquals(candidates, FuzzyMatcher.rank("", candidates) { listOf(it) })
    }

    @Test
    fun matchesAliasesUsageAndCase() {
        val candidates = listOf("quit", "model")
        val terms = mapOf(
            "quit" to listOf("/quit", "/exit"),
            "model" to listOf("/model", "/model <deployment>"),
        )

        assertEquals(listOf("quit"), FuzzyMatcher.rank("EXIT", candidates) { terms.getValue(it) })
        assertEquals(listOf("model"), FuzzyMatcher.rank("deployment", candidates) { terms.getValue(it) })
    }

    @Test
    fun truncatesRawQueriesAndTermsBeforeNormalization() {
        assertEquals(
            listOf("x".repeat(FuzzyMatcher.MAX_QUERY_CODE_POINTS)),
            FuzzyMatcher.rank(
                "x".repeat(FuzzyMatcher.MAX_QUERY_CODE_POINTS + 1),
                listOf("x".repeat(FuzzyMatcher.MAX_QUERY_CODE_POINTS)),
            ) { listOf(it) },
        )
        assertEquals(
            emptyList(),
            FuzzyMatcher.rank(
                "z",
                listOf("x".repeat(FuzzyMatcher.MAX_TERM_CODE_POINTS) + "z"),
            ) { listOf(it) },
        )

        // U+0130 expands to two code points under Locale.ROOT lowercase. Truncating after lowercase would make the
        // shorter first candidate look like an exact match and violate the raw-code-point contract.
        val shortExpanded = "İ".repeat(FuzzyMatcher.MAX_QUERY_CODE_POINTS / 2)
        val fullRawLimit = "İ".repeat(FuzzyMatcher.MAX_QUERY_CODE_POINTS)
        assertEquals(
            listOf(fullRawLimit),
            FuzzyMatcher.rank(fullRawLimit, listOf(shortExpanded, fullRawLimit)) { listOf(it) },
        )
    }

    @Test
    fun capsTermsCandidatesAndReturnedResultsInSourceOrder() {
        val hiddenTerm = List(FuzzyMatcher.MAX_TERMS_PER_CANDIDATE) { "unmatched-$it" } + "needle"
        assertEquals(emptyList(), FuzzyMatcher.rank("needle", listOf("candidate")) { hiddenTerm })

        val oversizedCatalog = (0..FuzzyMatcher.MAX_INSPECTED_CANDIDATES).toList()
        assertEquals(
            emptyList(),
            FuzzyMatcher.rank("needle", oversizedCatalog) { index ->
                listOf(if (index == FuzzyMatcher.MAX_INSPECTED_CANDIDATES) "needle" else "other")
            },
        )

        val ties = (0 until FuzzyMatcher.MAX_RETURNED_RESULTS + 25).toList()
        assertEquals(
            ties.take(FuzzyMatcher.MAX_RETURNED_RESULTS),
            FuzzyMatcher.rank("x", ties) { listOf("x") },
        )
        assertEquals(
            ties.take(FuzzyMatcher.MAX_RETURNED_RESULTS),
            FuzzyMatcher.rank("", ties) { listOf("unused") },
        )
    }

    @Test
    fun matchesUnicodeCodePoints() {
        val candidates = listOf("/model-😀", "/model-é")

        assertEquals(listOf("/model-😀"), FuzzyMatcher.rank("😀", candidates) { listOf(it) })
    }
}
