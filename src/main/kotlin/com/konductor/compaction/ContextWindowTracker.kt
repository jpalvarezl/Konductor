package com.konductor.compaction

import com.konductor.core.models.Usage

/**
 * Tracks the latest authoritative context size and decides when to compact before a turn
 * (docs/spec/architecture.md#compaction-integration).
 *
 * It keeps the most recent `Usage.totalTokens` reported by the model (from `AgentEvent.UsageReported`) — the
 * authoritative number, which includes any server-side overhead the client transcript can't see (e.g. a bound
 * PromptAgent's baked instructions, docs/spec/compaction.md#persisted-agents). The trigger uses that number,
 * not local estimates, so it stays accurate.
 *
 * Stateful and single-threaded: owned by one [com.konductor.agent.AgentLoop]; the turn loop is sequential.
 */
class ContextWindowTracker(private val settings: CompactionSettings) {
    /** Latest reported total context size, in tokens; 0 until the first turn reports usage. */
    var contextTokens: Int = 0
        private set

    /** Fold a fresh usage report in. Later turns see the updated size when deciding whether to compact. */
    fun update(usage: Usage) {
        contextTokens = usage.totalTokens
    }

    /**
     * Forget the last reported size (drop to 0). Called right after a compaction so the *next* turn does not
     * re-trigger off the now-stale pre-compaction total — the model re-establishes the real size on its next
     * `UsageReported`.
     */
    fun reset() {
        contextTokens = 0
    }

    /** The point past which a turn risks overflowing the reply headroom: `contextWindow - reserveTokens`. */
    val threshold: Int get() = settings.contextWindow - settings.reserveTokens

    /**
     * True when auto-compaction is enabled and the last reported size exceeds the reply-headroom threshold.
     * Manual `/compact` bypasses this (it always compacts).
     */
    fun shouldCompact(): Boolean = settings.enabled && contextTokens > threshold
}
