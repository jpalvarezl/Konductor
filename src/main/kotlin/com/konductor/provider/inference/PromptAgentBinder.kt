package com.konductor.provider.inference

/**
 * The PromptAgent binding control surface. Preparing may allocate a replacement Responses adapter, but it leaves the
 * committed provider route unchanged until the returned one-shot handle is committed by `AgentLoop`.
 */
interface PromptAgentBinder {
    /** The exact persisted agent name the next turn will reference, or `null` for the ephemeral (default) path. */
    val activeAgent: String?

    /**
     * Validate and prepare [agentName] without publication. `null` explicitly requests the ephemeral adapter; a
     * non-null value must be non-blank and already trimmed. Invalid input is rejected rather than silently changed.
     * The handle exposes the same exact name, which is the only value callers may persist.
     */
    fun prepareBinding(agentName: String?): PreparedPromptAgentBinding
}

/**
 * A PromptAgent-scoped, one-shot binding candidate. The type stays scoped because its prepare/publish/cleanup contract
 * is specific to one provider route rather than a generic application transaction. Production commit actions must be
 * non-failing; defensive callback failure aborts the candidate best-effort and is rethrown. [close] aborts an
 * uncommitted candidate with best-effort cleanup. Committing, aborting, or reusing a completed handle is rejected.
 */
class PreparedPromptAgentBinding internal constructor(
    val agentName: String?,
    private val commitAction: () -> Unit,
    private val abortAction: () -> Unit = {},
) : AutoCloseable {
    private var state: State = State.Prepared

    fun commit() {
        synchronized(this) {
            check(state == State.Prepared) { "PromptAgent binding has already been ${state.description}." }
            state = State.Committing
        }
        try {
            commitAction()
            synchronized(this) { state = State.Committed }
        } catch (failure: Throwable) {
            synchronized(this) { state = State.Aborted }
            runCatching(abortAction)
            throw failure
        }
    }

    override fun close() {
        synchronized(this) {
            check(state == State.Prepared) { "PromptAgent binding has already been ${state.description}." }
            state = State.Aborted
        }
        runCatching(abortAction)
    }

    private enum class State(val description: String) {
        Prepared("prepared"),
        Committing("committing"),
        Committed("committed"),
        Aborted("aborted"),
    }
}
