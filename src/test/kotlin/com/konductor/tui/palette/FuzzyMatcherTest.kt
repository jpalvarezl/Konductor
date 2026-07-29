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
    fun boundsQueriesAndTerms() {
        assertEquals(
            listOf("x".repeat(128)),
            FuzzyMatcher.rank("x".repeat(129), listOf("x".repeat(128))) { listOf(it) },
        )
        assertEquals(emptyList(), FuzzyMatcher.rank("z", listOf("x".repeat(512) + "z")) { listOf(it) })
    }

    @Test
    fun matchesUnicodeCodePoints() {
        val candidates = listOf("/model-😀", "/model-é")

        assertEquals(listOf("/model-😀"), FuzzyMatcher.rank("😀", candidates) { listOf(it) })
    }
}
