package com.konductor.tui.palette

import java.util.Locale

/**
 * Deterministic, code-point-aware subsequence ranking with hard work/output bounds. Raw strings are truncated before
 * case normalization so Unicode lowercase expansion cannot bypass the input limits.
 */
object FuzzyMatcher {
    /** Maximum raw query code points normalized for one ranking pass. */
    internal const val MAX_QUERY_CODE_POINTS = 128

    /** Maximum raw code points normalized from any one candidate term. */
    internal const val MAX_TERM_CODE_POINTS = 512

    /** Maximum terms inspected for each candidate. */
    internal const val MAX_TERMS_PER_CANDIDATE = 8

    /** Maximum source-order candidates inspected from a catalog. */
    internal const val MAX_INSPECTED_CANDIDATES = 2_000

    /** Maximum ranked candidates returned to palette state/rendering. */
    internal const val MAX_RETURNED_RESULTS = 100

    fun <T> rank(
        query: String,
        candidates: List<T>,
        searchTerms: (T) -> Iterable<String>,
    ): List<T> {
        val normalizedQuery = normalizedCodePoints(query, MAX_QUERY_CODE_POINTS)
        if (normalizedQuery.isEmpty()) {
            return candidates.asSequence()
                .take(MAX_INSPECTED_CANDIDATES)
                .take(MAX_RETURNED_RESULTS)
                .toList()
        }

        return candidates.asSequence()
            .take(MAX_INSPECTED_CANDIDATES)
            .mapIndexedNotNull { index, candidate ->
                val score = searchTerms(candidate).asSequence()
                    .take(MAX_TERMS_PER_CANDIDATE)
                    .mapNotNull { term -> score(normalizedQuery, normalizedCodePoints(term, MAX_TERM_CODE_POINTS)) }
                    .minOrNull()
                score?.let { Ranked(candidate, it, index) }
            }
            .sortedWith(compareBy<Ranked<T>> { it.score }.thenBy { it.sourceIndex })
            .take(MAX_RETURNED_RESULTS)
            .map(Ranked<T>::value)
            .toList()
    }

    private fun score(query: IntArray, candidate: IntArray): Int? {
        if (query.size > candidate.size) return null
        if (query.contentEquals(candidate)) return -20_000

        var candidateIndex = 0
        var firstMatch = -1
        var previousMatch = -1
        var gaps = 0
        var runs = 0

        query.forEach { queryPoint ->
            while (candidateIndex < candidate.size && candidate[candidateIndex] != queryPoint) candidateIndex++
            if (candidateIndex >= candidate.size) return null
            if (firstMatch < 0) firstMatch = candidateIndex
            if (previousMatch < 0 || candidateIndex != previousMatch + 1) runs++
            if (previousMatch >= 0) gaps += candidateIndex - previousMatch - 1
            previousMatch = candidateIndex
            candidateIndex++
        }

        val prefixBonus = if (firstMatch == 0) -5_000 else 0
        return prefixBonus + firstMatch * 100 + gaps * 20 + runs * 10 + (candidate.size - query.size)
    }

    private fun normalizedCodePoints(value: String, rawLimit: Int): IntArray {
        val truncated = value.codePoints().limit(rawLimit.toLong()).toArray()
        val raw = String(truncated, 0, truncated.size)
        return raw.lowercase(Locale.ROOT).codePoints().toArray()
    }

    private data class Ranked<T>(
        val value: T,
        val score: Int,
        val sourceIndex: Int,
    )
}
