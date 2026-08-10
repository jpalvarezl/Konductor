package com.konductor.tui

import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
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
    fun activeSubmissionRoutesOnlyCancellationScrollingAndQuit() {
        assertEquals(ActiveSubmissionKeyRoute.Cancel, routeActiveSubmissionKey(KeyStroke(KeyType.Escape)))
        assertEquals(ActiveSubmissionKeyRoute.ScrollLineUp, routeActiveSubmissionKey(KeyStroke(KeyType.ArrowUp)))
        assertEquals(ActiveSubmissionKeyRoute.ScrollLineDown, routeActiveSubmissionKey(KeyStroke(KeyType.ArrowDown)))
        assertEquals(ActiveSubmissionKeyRoute.ScrollPageUp, routeActiveSubmissionKey(KeyStroke(KeyType.PageUp)))
        assertEquals(ActiveSubmissionKeyRoute.ScrollPageDown, routeActiveSubmissionKey(KeyStroke(KeyType.PageDown)))
        assertEquals(ActiveSubmissionKeyRoute.Quit, routeActiveSubmissionKey(KeyStroke('c', true, false)))

        listOf(
            KeyStroke(KeyType.Enter),
            KeyStroke(KeyType.Backspace),
            KeyStroke('/', false, false),
            KeyStroke('k', true, false),
            KeyStroke('x', false, false),
        ).forEach { key ->
            assertEquals(ActiveSubmissionKeyRoute.Inert, routeActiveSubmissionKey(key), "route for $key")
        }
    }

    @Test
    fun activeSlotMakesRepeatedEscapeAndSupersessionInert() {
        val slot = ActiveSubmissionSlot()
        val first = ConversationController.Submission.LocalCommand().also { it.attach(Job()) }
        val attemptedReplacement = ConversationController.Submission.LocalCommand().also { it.attach(Job()) }
        slot.install(first)

        assertTrue(slot.requestCancel())
        assertFalse(slot.requestCancel())
        assertEquals(LocalCommandPhase.Cancelling, first.phase)
        assertFailsWith<IllegalStateException> { slot.install(attemptedReplacement) }
        assertSame(first, slot.current)
    }

    @Test
    fun staleTerminalIdentityCannotMutateOrClearANewerSubmission() {
        val slot = ActiveSubmissionSlot()
        val stale = ConversationController.Submission.LocalCommand().also { it.attach(Job()) }
        val current = ConversationController.Submission.LocalCommand().also { it.attach(Job()) }
        var terminalMutations = 0
        slot.install(stale)
        assertTrue(slot.clear(stale))
        slot.install(current)

        assertFalse(slot.complete(stale) { terminalMutations++ })
        assertEquals(0, terminalMutations)
        assertSame(current, slot.current)
        assertTrue(slot.complete(current) { terminalMutations++ })
        assertEquals(1, terminalMutations)
        assertEquals(null, slot.current)
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

        var branch: ActiveSubmissionShutdownBranch? = null
        awaitActiveSubmissionShutdown(
            submission,
            cancellationTimeoutMs = 1_000,
            onBranchEntered = { branch = it },
        )

        assertEquals(ActiveSubmissionShutdownBranch.LocalCommandCancellation, branch)
        assertEquals(LocalCommandPhase.Cancelling, submission.phase)
        assertFalse(submission.beginCommit())
        assertTrue(job.isCancelled)
    }

    @Test
    fun gracefulShutdownTimeoutStillDeniesLateCommitFromNonCooperativePreparation() = runBlocking {
        val submission = ConversationController.Submission.LocalCommand()
        val preparationEntered = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val lateBeginCommit = CompletableDeferred<Boolean>()
        var commitSideEffects = 0
        val job = launch(Dispatchers.IO) {
            preparationEntered.countDown()
            while (true) {
                try {
                    releasePreparation.await()
                    break
                } catch (_: InterruptedException) {
                    // Simulate blocking SDK preparation that ignores coroutine/thread cancellation.
                }
            }
            val admitted = submission.beginCommit()
            if (admitted) commitSideEffects++
            lateBeginCommit.complete(admitted)
        }
        submission.attach(job)
        assertTrue(preparationEntered.await(5, TimeUnit.SECONDS))
        val branchEntered = CompletableDeferred<ActiveSubmissionShutdownBranch>()

        awaitActiveSubmissionShutdown(
            submission,
            cancellationTimeoutMs = 1,
            onBranchEntered = { branchEntered.complete(it) },
        )

        assertEquals(ActiveSubmissionShutdownBranch.LocalCommandCancellation, branchEntered.await())
        assertEquals(LocalCommandPhase.Cancelling, submission.phase)
        assertFalse(job.isCompleted)
        releasePreparation.countDown()
        assertFalse(lateBeginCommit.await())
        assertEquals(0, commitSideEffects)
        job.join()
    }

    @Test
    fun eventLoopFailureShutsDownWorkBeforeScreenStopAndDeniesLateCommit() = runBlocking {
        val submission = ConversationController.Submission.LocalCommand()
        val preparationEntered = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val lateBeginCommit = CompletableDeferred<Boolean>()
        var durableCommits = 0
        val job = launch(Dispatchers.IO) {
            preparationEntered.countDown()
            while (true) {
                try {
                    releasePreparation.await()
                    break
                } catch (_: InterruptedException) {
                    // Simulate a catalog/SDK call that does not unwind within the bounded frontend shutdown wait.
                }
            }
            val admitted = submission.beginCommit()
            if (admitted) durableCommits++
            lateBeginCommit.complete(admitted)
        }
        submission.attach(job)
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            try {
                events += "start-screen"
                assertTrue(preparationEntered.await(5, TimeUnit.SECONDS))
                events += "event-loop-failed"
                error("render failed")
            } finally {
                shutdownTuiAndStopScreen(
                    shutdown = {
                        events += "frontend-shutdown"
                        runBlocking { awaitActiveSubmissionShutdown(submission, cancellationTimeoutMs = 1) }
                    },
                    stopScreen = { events += "stop-screen" },
                )
            }
        }
        events += "provider-close"

        assertEquals("render failed", failure.message)
        assertEquals(
            listOf("start-screen", "event-loop-failed", "frontend-shutdown", "stop-screen", "provider-close"),
            events,
        )
        assertEquals(LocalCommandPhase.Cancelling, submission.phase)
        assertFalse(job.isCompleted)
        releasePreparation.countDown()
        assertFalse(lateBeginCommit.await())
        assertEquals(0, durableCommits)
        job.join()
    }

    @Test
    fun screenStopStillRunsWhenFrontendShutdownFails() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            shutdownTuiAndStopScreen(
                shutdown = {
                    events += "frontend-shutdown"
                    error("shutdown failed")
                },
                stopScreen = { events += "stop-screen" },
            )
        }

        assertEquals("shutdown failed", failure.message)
        assertEquals(listOf("frontend-shutdown", "stop-screen"), events)
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
        val branchEntered = CompletableDeferred<ActiveSubmissionShutdownBranch>()
        val shutdown = async {
            awaitActiveSubmissionShutdown(
                submission,
                cancellationTimeoutMs = 1_000,
                onBranchEntered = { branchEntered.complete(it) },
            )
        }
        assertEquals(ActiveSubmissionShutdownBranch.LocalCommandCommit, branchEntered.await())

        assertFalse(shutdown.isCompleted)
        submission.job.cancel()
        assertFalse(submission.requestCancel())
        releaseCommit.complete(Unit)
        shutdown.await()

        assertEquals(LocalCommandPhase.Completed, submission.phase)
        assertTrue(job.isCancelled)
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
        val branchEntered = CompletableDeferred<ActiveSubmissionShutdownBranch>()
        val shutdown = async(Dispatchers.Default) {
            awaitActiveSubmissionShutdown(
                submission,
                cancellationTimeoutMs = 1,
                afterPhaseRead = {
                    phaseRead.countDown()
                    allowCancelRequest.await()
                },
                onBranchEntered = { branchEntered.complete(it) },
            )
        }
        assertTrue(phaseRead.await(5, TimeUnit.SECONDS))
        releasePreparation.complete(Unit)
        commitStarted.await()
        allowCancelRequest.countDown()
        assertEquals(ActiveSubmissionShutdownBranch.LocalCommandCommit, branchEntered.await())

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
