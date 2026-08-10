package com.konductor.tui

import com.googlecode.lanterna.input.KeyStroke
import com.konductor.conversation.ConversationController
import com.konductor.conversation.LocalCommandContext
import com.konductor.conversation.LocalCommandPhase
import com.konductor.conversation.StateApplier
import com.konductor.core.MessageRole
import com.konductor.core.models.Session
import com.konductor.i18n.AppStrings
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiAppKeyTest {
    @Test
    fun slashOpensOnlyForEmptyComposer() {
        assertTrue(shouldOpenCommandPalette('/', ctrlDown = false, composerEmpty = true))
        assertFalse(shouldOpenCommandPalette('/', ctrlDown = false, composerEmpty = false))
    }

    @Test
    fun ctrlKOpensWithComposerContent() {
        assertTrue(shouldOpenCommandPalette('k', ctrlDown = true, composerEmpty = false))
        assertTrue(shouldOpenCommandPalette('K', ctrlDown = true, composerEmpty = true))
        assertFalse(shouldOpenCommandPalette('k', ctrlDown = false, composerEmpty = true))
    }

    @Test
    fun shortcutsStayInertDuringWork() {
        assertFalse(shouldOpenCommandPalette('/', false, true, inputAvailable = false))
        assertFalse(shouldOpenCommandPalette('k', true, true, inputAvailable = false))
    }

    @Test
    fun gracefulShutdownPreventsAStillPreparingCommandFromCommitting() = runBlocking {
        val submission = ConversationController.Submission.LocalCommand()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            started.complete(Unit)
            awaitCancellation()
        }
        submission.attach(job)
        started.await()

        awaitActiveSubmissionShutdown(submission, cancellationTimeoutMs = 1_000)

        assertEquals(LocalCommandPhase.Cancelling, submission.phase)
        assertFalse(submission.beginCommit())
        assertTrue(job.isCancelled)
    }

    @Test
    fun gracefulShutdownWaitsForAnAlreadyCommittingCommand() = runBlocking {
        val submission = ConversationController.Submission.LocalCommand()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val job = launch {
            LocalCommandContext(
                submission,
                StateApplier { it() },
                unexpectedFailure = { throw it },
            ).commit {
                commitStarted.complete(Unit)
                releaseCommit.await()
            }
        }
        submission.attach(job)
        commitStarted.await()
        val shutdownEntered = CompletableDeferred<Unit>()
        val shutdown = async {
            shutdownEntered.complete(Unit)
            awaitActiveSubmissionShutdown(submission, cancellationTimeoutMs = 1_000)
        }
        shutdownEntered.await()
        yield()

        assertFalse(shutdown.isCompleted)
        assertFalse(submission.requestCancel())
        releaseCommit.complete(Unit)
        shutdown.await()

        assertEquals(LocalCommandPhase.Completed, submission.phase)
        assertTrue(job.isCompleted)
    }

    @Test
    fun gracefulShutdownRechecksWhenCommitWinsAfterItsPhaseSnapshot() = runBlocking {
        val submission = ConversationController.Submission.LocalCommand()
        val releasePreparation = CompletableDeferred<Unit>()
        val commitStarted = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            releasePreparation.await()
            LocalCommandContext(
                submission,
                StateApplier { it() },
                unexpectedFailure = { throw it },
            ).commit {
                commitStarted.complete(Unit)
                releaseCommit.await()
            }
        }
        submission.attach(job)
        val phaseRead = CountDownLatch(1)
        val allowCancelRequest = CountDownLatch(1)
        val shutdown = async(Dispatchers.Default) {
            awaitActiveSubmissionShutdown(
                submission,
                cancellationTimeoutMs = 1,
                afterPhaseRead = {
                    phaseRead.countDown()
                    allowCancelRequest.await()
                },
            )
        }
        assertTrue(phaseRead.await(5, TimeUnit.SECONDS))
        releasePreparation.complete(Unit)
        commitStarted.await()
        allowCancelRequest.countDown()
        yield()

        assertEquals(LocalCommandPhase.Committing, submission.phase)
        assertFalse(shutdown.isCompleted)
        releaseCommit.complete(Unit)
        shutdown.await()
        assertEquals(LocalCommandPhase.Completed, submission.phase)
    }

    @Test
    fun startupMessagesArePresentationOnlySystemMessages() {
        val session = Session(Uuid.random(), null, Path.of("."), "model", Clock.System.now())

        val messages = initialTuiMessages(
            session,
            AppStrings.english(),
            startupSystemMessages = listOf("Automatically selected deployment-a."),
        )

        assertEquals(
            listOf(AppStrings.english().welcomeMessage, "Automatically selected deployment-a."),
            messages.map { it.content },
        )
        assertTrue(messages.all { it.role == MessageRole.System })
        assertTrue(session.entries.isEmpty())
    }

    @Test
    fun consumesModifiedEnterSuffix() {
        val keys = ArrayDeque("13;2u".map { KeyStroke(it, false, false) })
        val deferred = mutableListOf<KeyStroke>()

        val keycode = consumeCsiuSuffix({ keys.removeFirstOrNull() }, deferred::add)

        assertEquals(CSI_U_ENTER_KEYCODE, keycode)
        assertTrue(keys.isEmpty())
        assertTrue(deferred.isEmpty())
    }
}
