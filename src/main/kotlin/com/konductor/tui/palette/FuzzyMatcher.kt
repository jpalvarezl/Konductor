package com.konductor.tui.palette

import java.util.Locale

/** Deterministic, bounded, code-point-aware subsequence ranking for command and option labels. */
object FuzzyMatcher {
    private const val MAX_QUERY_CODE_POINTS = 128
    private const val MAX_TERM_CODE_POINTS = 512

    fun <T> rank(
        query: String,
        candidates: List<T>,
        searchTerms: (T) -> Iterable<String>,
    ): List<T> {
        val normalizedQuery = normalizedCodePoints(query, MAX_QUERY_CODE_POINTS)
        if (normalizedQuery.isEmpty()) return candidates.toList()

        return candidates.mapIndexedNotNull { index, candidate ->
            val score = searchTerms(candidate)
                .mapNotNull { term -> score(normalizedQuery, normalizedCodePoints(term, MAX_TERM_CODE_POINTS)) }
                .minOrNull()
            score?.let { Ranked(candidate, it, index) }
        }.sortedWith(compareBy<Ranked<T>> { it.score }.thenBy { it.sourceIndex })
            .map(Ranked<T>::value)
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

    private fun normalizedCodePoints(value: String, limit: Int): IntArray =
        value.lowercase(Locale.ROOT).codePoints().limit(limit.toLong()).toArray()

    private data class Ranked<T>(
        val value: T,
        val score: Int,
        val sourceIndex: Int,
    )
}
