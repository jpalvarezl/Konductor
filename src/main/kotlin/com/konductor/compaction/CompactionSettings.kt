package com.konductor.compaction

/**
 * Tunables for client-side compaction of the Prompt provider's transcript (docs/spec/compaction.md).
 *
 * A plain data class with no dependencies so [com.konductor.config.Configuration] can carry it without a
 * dependency cycle, and so tests can construct one directly.
 *
 * @property enabled Enable **automatic** compaction before a turn. `/compact` works on demand regardless.
 * @property reserveTokens Tokens reserved for the reply — headroom subtracted from the window when deciding
 *   to compact.
 * @property keepRecentTokens Recent tokens kept unsummarized: the cut walks backwards until this many
 *   (estimated) tokens are retained, then rounds to a turn boundary.
 * @property contextWindow The model's usable context window in tokens. There is no reliable SDK call for
 *   this, so it is a configurable knob (docs/spec/configuration.md); a conservative default compacts a
 *   little early rather than overflowing.
 */
data class CompactionSettings(
    val enabled: Boolean = true,
    val reserveTokens: Int = DEFAULT_RESERVE_TOKENS,
    val keepRecentTokens: Int = DEFAULT_KEEP_RECENT_TOKENS,
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
) {
    companion object {
        const val DEFAULT_RESERVE_TOKENS: Int = 16_384
        const val DEFAULT_KEEP_RECENT_TOKENS: Int = 20_000
        const val DEFAULT_CONTEXT_WINDOW: Int = 128_000
    }
}
