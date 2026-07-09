package com.konductor.provider.inference

/**
 * One event from a streaming inference call (vendor-neutral). The Prompt loop relays [TextDelta]s to the UI
 * as they arrive and uses the terminal [Completed] — carrying the aggregated [InferenceResponse] (full text,
 * tool calls, usage) — to finish the turn. Future streaming detail (e.g. function-call argument deltas for
 * the M2 tool loop) becomes additional variants here.
 */
sealed interface InferenceChunk {
    /** An incremental piece of assistant text. */
    data class TextDelta(val text: String) : InferenceChunk

    /** Brief provider-neutral status (for example, a transient retry notice) surfaced to the UI. */
    data class Status(val message: String) : InferenceChunk

    /** The terminal event: the fully aggregated response for this model call. */
    data class Completed(val response: InferenceResponse) : InferenceChunk
}
