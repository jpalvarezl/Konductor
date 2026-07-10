package com.konductor.core

import java.util.Locale

/**
 * Max context-window size (in tokens) per model family. Unlike price-per-token, the window is a stable constant
 * per model version, so a small hardcoded table is accurate and low-maintenance. Values come from the community
 * reference at https://github.com/taylorwilsdon/llm-context-limits.
 *
 * Foundry deployment names carry a version suffix (e.g. `gpt-5.2`, `gpt-4.1-mini`), so lookup matches the longest
 * key that the (lower-cased) model name starts with — `gpt-5.2` and `gpt-5-mini` both resolve to the gpt-5 family.
 * Unknown models return null so the caller can fall back to a configured default.
 */
object ModelContextWindow {
    private val windowByPrefix: List<Pair<String, Int>> = listOf(
        "gpt-4.1" to 1_047_576,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-5" to 400_000,
        "o3" to 200_000,
        "o4" to 200_000,
    ).sortedByDescending { it.first.length }

    /** Max context window (tokens) for [modelName], or null when it isn't in the table. */
    fun forModel(modelName: String?): Int? {
        val name = modelName?.lowercase(Locale.ROOT)?.trim() ?: return null
        return windowByPrefix.firstOrNull { name.startsWith(it.first) }?.second
    }
}
