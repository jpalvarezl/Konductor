package com.konductor.provider.inference

/**
 * One application event from a streaming Foundry Responses call. The Prompt loop relays [TextDelta]s to the UI
 * as they arrive and uses the terminal [Completed] — carrying the aggregated [FoundryResponsesResult] (full text,
 * tool calls, usage) — to finish the turn. Future streaming detail (e.g. function-call argument deltas for
 * the M2 tool loop) becomes additional variants here.
 */
sealed interface FoundryResponsesEvent {
    /** An incremental piece of assistant text. */
    data class TextDelta(val text: String) : FoundryResponsesEvent

    /** Structured transient-retry status; frontends own the localized presentation. */
    data class Retrying(
        val reason: String?,
        val retryAttempt: Int,
        val maxRetries: Int,
        val delayMs: Long,
    ) : FoundryResponsesEvent

    /** The terminal event: the fully aggregated application result for this Foundry Responses call. */
    data class Completed(val result: FoundryResponsesResult) : FoundryResponsesEvent
}
