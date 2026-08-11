package com.konductor.conversation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Atomic lifecycle of one submitted local background command. */
internal enum class LocalCommandPhase {
    Running,
    Cancelling,
    Cancelled,
    Committing,
    Completed,
    Failed,
}

/**
 * Controller-owned command boundary. Preparation runs before [commit] and must not mutate durable, live, or
 * presentation state. The winning commit, including its result publication, runs completely in [NonCancellable].
 */
class LocalCommandContext internal constructor(
    private val submission: ConversationController.Submission.LocalCommand,
    private val applier: StateApplier,
    private val unexpectedFailure: (Throwable) -> Unit,
    internal val beforeBeginCommit: () -> Unit = {},
) {
    /**
     * Linearize this command against cancellation, then run its complete durable/live/presentation commit.
     * A command-specific failure can be reported with [CommitScope.fail]; an uncaught failure receives the generic
     * local-command failure report. If cancellation already won, this throws without invoking [block].
     */
    suspend fun commit(block: suspend CommitScope.() -> Unit) {
        currentCoroutineContext().ensureActive()
        beforeBeginCommit()
        if (!submission.beginCommit()) throw CancellationException("Local command was cancelled before commit")

        withContext(NonCancellable) {
            val commitScope = CommitScope(applier)
            try {
                commitScope.block()
            } catch (error: Throwable) {
                commitScope.fail { unexpectedFailure(error) }
            } finally {
                submission.finishCommit(commitScope.failed)
            }
        }
    }

    /** Linearize an unexpected preparation failure against cancellation and publish its natural failure report. */
    suspend fun failPreparation(mutation: () -> Unit) {
        currentCoroutineContext().ensureActive()
        if (!submission.failPreparation()) throw CancellationException("Local command cancellation won")
        applier(mutation)
    }

    class CommitScope internal constructor(private val applier: StateApplier) {
        internal var failed: Boolean = false
            private set

        /** Apply matching live/presentation state through the frontend's render-lock boundary. */
        fun apply(mutation: () -> Unit) {
            applier(mutation)
        }

        /** Mark natural command failure and publish its command-specific report under the presentation boundary. */
        fun fail(mutation: () -> Unit) {
            failed = true
            apply(mutation)
        }
    }
}
