package com.konductor.provider.inference

/**
 * The PromptAgent binding control surface. Preparing may allocate a replacement Responses adapter, but it leaves the
 * committed provider route unchanged until the returned one-shot handle is committed by `AgentLoop`.
 */
interface PromptAgentBinder {
    /** The persisted agent the next turn will reference, or `null` for the ephemeral (default) path. */
    val activeAgent: String?

    /**
     * Normalize [agentName] (`trim`, with blank mapped to `null`) and prepare that exact name without publication.
     * The handle's [PreparedPromptAgentBinding.agentName] is the only value callers may persist.
     */
    fun prepareBinding(agentName: String?): PreparedPromptAgentBinding
}

/**
 * A one-shot PromptAgent binding candidate. A valid first [commit] is non-failing; [close] aborts an uncommitted
 * candidate with best-effort cleanup. Committing, aborting, or reusing a completed handle is rejected.
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
            try {
                commitAction()
                state = State.Committed
            } catch (failure: Throwable) {
                state = State.Aborted
                runCatching(abortAction)
                throw failure
            }
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
        Committed("committed"),
        Aborted("aborted"),
    }
}
