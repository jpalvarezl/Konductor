package com.konductor.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.konductor.agent.AgentLoop
import com.konductor.conversation.BuiltInCommandDescriptors
import com.konductor.conversation.CommandDescriptor
import com.konductor.conversation.CommandOptionProvider
import com.konductor.conversation.ConversationController
import com.konductor.conversation.FoundryDiscoveryCommand
import com.konductor.conversation.FoundryModelOptionProvider
import com.konductor.conversation.PromptAgentCommand
import com.konductor.conversation.PromptAgentOptionProvider
import com.konductor.conversation.RecentSessionOptionProvider
import com.konductor.conversation.sessionEntriesToMessages
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.Session
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.i18n.AppStrings
import com.konductor.provider.ProviderManagement
import com.konductor.provider.ProviderRuntime
import com.konductor.session.SessionSummary
import com.konductor.tui.component.CommandPaletteView
import com.konductor.tui.component.PromptInputView
import com.konductor.tui.component.StatusBar
import com.konductor.tui.component.TranscriptView
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.palette.CommandPaletteController
import com.konductor.tui.palette.CommandPaletteCoordinator
import com.konductor.tui.palette.PaletteKey
import com.konductor.tui.palette.PaletteOpenOrigin
import com.konductor.tui.palette.PaletteOptionSource
import com.konductor.tui.style.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal fun shouldOpenCommandPalette(
    character: Char,
    ctrlDown: Boolean,
    composerEmpty: Boolean,
    inputAvailable: Boolean = true,
): Boolean = inputAvailable &&
    ((ctrlDown && character.equals('k', ignoreCase = true)) || (!ctrlDown && character == '/' && composerEmpty))

/** Input routes retained while an exact active submission owns the TUI work slot. */
internal enum class ActiveSubmissionKeyRoute {
    Cancel,
    ScrollLineUp,
    ScrollLineDown,
    ScrollPageUp,
    ScrollPageDown,
    Quit,
    Inert,
}

/** Keep active-work key routing explicit and independently testable without constructing a terminal. */
internal fun routeActiveSubmissionKey(key: KeyStroke): ActiveSubmissionKeyRoute = when (key.keyType) {
    KeyType.Escape -> ActiveSubmissionKeyRoute.Cancel
    KeyType.ArrowUp -> ActiveSubmissionKeyRoute.ScrollLineUp
    KeyType.ArrowDown -> ActiveSubmissionKeyRoute.ScrollLineDown
    KeyType.PageUp -> ActiveSubmissionKeyRoute.ScrollPageUp
    KeyType.PageDown -> ActiveSubmissionKeyRoute.ScrollPageDown
    KeyType.Character -> if (
        (key.character == 'c' || key.character == 'C') && key.isCtrlDown
    ) {
        ActiveSubmissionKeyRoute.Quit
    } else {
        ActiveSubmissionKeyRoute.Inert
    }
    else -> ActiveSubmissionKeyRoute.Inert
}

/** Atomic exact-identity slot; a stale terminal callback cannot clear a newer submission. */
internal class ActiveSubmissionSlot {
    private val active = AtomicReference<ConversationController.Submission.Active?>(null)

    val current: ConversationController.Submission.Active? get() = active.get()

    @Synchronized
    fun install(submission: ConversationController.Submission.Active) {
        check(active.compareAndSet(null, submission)) { "An active submission already owns the TUI work slot" }
    }

    @Synchronized
    fun clear(submission: ConversationController.Submission.Active): Boolean =
        active.compareAndSet(submission, null)

    /** Run terminal presentation only for the matching owner, then clear that exact identity last. */
    @Synchronized
    fun complete(submission: ConversationController.Submission.Active, terminalMutation: () -> Unit): Boolean {
        if (active.get() !== submission) return false
        try {
            terminalMutation()
        } finally {
            check(active.compareAndSet(submission, null)) { "Active submission changed during terminal reporting" }
        }
        return true
    }

    fun requestCancel(): Boolean = active.get()?.requestCancel() == true
}

/** Deterministic observation seam for the shutdown branch immediately before its join/wait. */
internal enum class ActiveSubmissionShutdownBranch {
    AgentTurnCancellation,
    LocalCommandCancellation,
    LocalCommandCommit,
}

/** Gracefully prevent a pre-commit command, but allow an already admitted non-cancellable commit to finish. */
internal suspend fun awaitActiveSubmissionShutdown(
    active: ConversationController.Submission.Active,
    cancellationTimeoutMs: Long,
    afterPhaseRead: () -> Unit = {},
    onBranchEntered: (ActiveSubmissionShutdownBranch) -> Unit = {},
) {
    when (active) {
        is ConversationController.Submission.AgentTurn -> {
            active.requestCancel()
            onBranchEntered(ActiveSubmissionShutdownBranch.AgentTurnCancellation)
            withTimeoutOrNull(cancellationTimeoutMs) { active.job.join() }
        }
        is ConversationController.Submission.LocalCommand -> when (active.phase) {
            com.konductor.conversation.LocalCommandPhase.Committing -> {
                onBranchEntered(ActiveSubmissionShutdownBranch.LocalCommandCommit)
                active.job.join()
            }
            else -> {
                afterPhaseRead()
                val cancellationWon = active.requestCancel()
                // Commit may have won after the phase read but before requestCancel. Re-read after the failed CAS so
                // shutdown never applies the generic cancellation timeout to an admitted NonCancellable commit.
                if (!cancellationWon && active.phase == com.konductor.conversation.LocalCommandPhase.Committing) {
                    onBranchEntered(ActiveSubmissionShutdownBranch.LocalCommandCommit)
                    active.job.join()
                } else {
                    onBranchEntered(ActiveSubmissionShutdownBranch.LocalCommandCancellation)
                    withTimeoutOrNull(cancellationTimeoutMs) { active.job.join() }
                }
            }
        }
    }
}

/** Always restore the screen after frontend work shutdown, even if that shutdown itself fails. */
internal fun shutdownTuiAndStopScreen(shutdown: () -> Unit, stopScreen: () -> Unit) {
    try {
        shutdown()
    } finally {
        stopScreen()
    }
}

/** Consume the suffix after Lanterna has exposed the leading CSI-u `Alt+[` key. */
internal fun consumeCsiuSuffix(
    pollInput: () -> KeyStroke?,
    defer: (KeyStroke) -> Unit,
): Int? {
    val params = StringBuilder()
    while (params.length < CSI_U_MAX_PARAM_LENGTH) {
        val next = pollInput() ?: break
        val ch = next.character
        if (next.keyType != KeyType.Character || ch == null) {
            defer(next)
            break
        }
        when {
            ch == 'u' || ch == '~' -> { params.append(ch); break }
            ch.isDigit() || ch == ';' -> params.append(ch)
            else -> { defer(next); break }
        }
    }
    return parseCsiuKeycode(params.toString())
}

private const val CSI_U_MAX_PARAM_LENGTH = 12

/** Build presentation-only initial TUI messages without adding entries to the persisted conversation. */
internal fun initialTuiMessages(
    session: Session,
    strings: AppStrings,
    startupSystemMessages: List<String> = emptyList(),
): List<ChatMessage> {
    val sessionMessages = if (session.entries.isEmpty()) {
        listOf(ChatMessage(MessageRole.System, strings.welcomeMessage))
    } else {
        sessionEntriesToMessages(session.entries, strings) + ChatMessage(
            MessageRole.System,
            strings.resumedSession(session.id.toString().take(8), session.entries.size),
        )
    }
    return sessionMessages + startupSystemMessages.map { ChatMessage(MessageRole.System, it) }
}

/** Application composition for dynamic palette catalogs, kept Lanterna-free for focused production-wiring tests. */
internal fun composePaletteOptionSources(
    deployments: FoundryDeploymentCatalog,
    listSessions: () -> List<SessionSummary>,
    providerManagement: ProviderManagement,
    strings: AppStrings = AppStrings.english(),
): Map<String, PaletteOptionSource> = buildMap {
    putOptionSource(
        BuiltInCommandDescriptors.model,
        FoundryModelOptionProvider(deployments),
        title = strings.paletteModelTitle,
        loadingMessage = strings.paletteModelLoading,
        emptyMessage = strings.paletteModelEmpty,
        errorMessage = strings.paletteModelError,
    )
    putOptionSource(
        BuiltInCommandDescriptors.resume,
        RecentSessionOptionProvider(listSessions, strings),
        title = strings.paletteResumeTitle,
        loadingMessage = strings.paletteResumeLoading,
        emptyMessage = strings.paletteResumeEmpty,
        errorMessage = strings.paletteResumeError,
    )
    (providerManagement as? ProviderManagement.PromptAgents)?.let { management ->
        putOptionSource(
            BuiltInCommandDescriptors.agent,
            PromptAgentOptionProvider(management, strings),
            insertionPrefix = "/agent use ",
            title = strings.paletteAgentTitle,
            loadingMessage = strings.paletteAgentLoading,
            emptyMessage = strings.paletteAgentEmpty,
            errorMessage = strings.paletteAgentError,
        )
    }
}

private fun MutableMap<String, PaletteOptionSource>.putOptionSource(
    descriptor: CommandDescriptor,
    provider: CommandOptionProvider,
    insertionPrefix: String = descriptor.insertionPrefix,
    title: String,
    loadingMessage: String,
    emptyMessage: String,
    errorMessage: String,
) {
    put(
        descriptor.name,
        PaletteOptionSource(
            provider = provider,
            insertionPrefix = insertionPrefix,
            title = title,
            loadingMessage = loadingMessage,
            emptyMessage = emptyMessage,
            errorMessage = errorMessage,
        ),
    )
}

class TuiApp(
    private val agentLoop: AgentLoop,
    private val providerRuntime: ProviderRuntime,
    private val deployments: FoundryDeploymentCatalog,
    private val connections: FoundryConnectionCatalog,
    private val contextWindowTokens: Int = 128_000,
    private val strings: AppStrings = AppStrings.english(),
    private val theme: Theme = Theme(),
    private val startupSystemMessages: List<String> = emptyList(),
) {
    private val providerManagement = providerRuntime.management
    private val state = AppState(
        initialMessages = initialTuiMessages(agentLoop.session, strings, startupSystemMessages),
        modelName = agentLoop.modelName,
        contextWindowTokens = contextWindowTokens,
        activeAgentName = agentLoop.session.promptAgentName,
    )

    init {
        // Restore the status-bar token count from the most recent assistant entry when resuming a session.
        state.lastUsage = agentLoop.session.entries.asReversed()
            .filterIsInstance<AssistantEntry>().firstOrNull { it.usage != null }?.usage
    }

    // /agent is available only when the runtime carries the explicit PromptAgent management surface. AgentLoop owns
    // the only production adoption path; this command receives only committed outcomes for presentation.
    private val agentCommand: PromptAgentCommand? =
        (providerManagement as? ProviderManagement.PromptAgents)?.let { management ->
            PromptAgentCommand(
                state,
                { agentLoop.context },
                { agentLoop.activePromptAgentName },
                { name, commit -> agentLoop.bindPromptAgentWithCommit(name, commit) },
                management.lifecycle,
                strings = strings,
            )
        }

    private val discoveryCommand = FoundryDiscoveryCommand(state, agentLoop, deployments, connections, strings)
    private val conversationController = ConversationController(
        state = state,
        agentLoop = agentLoop,
        discoveryCommand = discoveryCommand,
        agentCommand = agentCommand,
        strings = strings,
    )

    private val transcriptView = TranscriptView(theme, strings)
    private val statusBar = StatusBar(theme, strings)
    private val promptInputView = PromptInputView(theme, strings)
    private val commandPaletteView = CommandPaletteView(theme, strings)
    private val commandPaletteController = CommandPaletteController(
        conversationController.commandRegistry,
        strings,
        composePaletteOptionSources(deployments, agentLoop::listSessions, providerManagement, strings),
    )

    // One-key lookahead used by the paste heuristic: a newline that turns out to be part of a paste stashes the
    // key it peeked here so the event loop processes it on the next iteration (instead of recursing per line).
    private var pendingKey: KeyStroke? = null

    // A model turn or local command runs on this scope so the event loop can poll Esc and repaint. The exact kinded
    // identity remains installed through unwind, terminal reporting, working-state clear, and only then is removed.
    private val turnScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateLock = Any()
    private val activeSubmission = ActiveSubmissionSlot()

    // Set whenever state changes (keypress, resize, or a background turn update via `fold`). The event loop only
    // re-renders when it's true, so an idle prompt doesn't re-wrap the whole transcript every tick (~40 Hz).
    @Volatile
    private var dirty = true

    private val commandPaletteCoordinator by lazy {
        CommandPaletteCoordinator(
            state,
            commandPaletteController,
            turnScope,
            stateLock,
            onStateChanged = { dirty = true },
        )
    }

    fun run() {
        val terminal = DefaultTerminalFactory()
            .setTerminalEmulatorTitle("Konductor")
            .createTerminal()
        val screen = TerminalScreen(terminal)

        try {
            screen.startScreen()
            screen.clear()
            eventLoop(screen)
        } finally {
            // This runs for normal exit and render/poll/key failures. Main closes provider resources only after run
            // returns or throws, so pre-commit work is denied and admitted commits finish before provider close.
            shutdownTuiAndStopScreen(::shutdownFrontend, screen::stopScreen)
        }
    }

    /** Prevent late command commits and finish/cancel all frontend work before the process closes provider resources. */
    private fun shutdownFrontend() {
        try {
            commandPaletteCoordinator.close()
        } finally {
            runBlocking {
                try {
                    val active = activeSubmission.current
                    if (active != null) awaitActiveSubmissionShutdown(active, TURN_SCOPE_SHUTDOWN_TIMEOUT_MS)
                } finally {
                    // Cancel any non-submission child only after the active owner has prevented a late commit or
                    // completed it.
                    withTimeoutOrNull(TURN_SCOPE_SHUTDOWN_TIMEOUT_MS) {
                        turnScope.coroutineContext[Job]?.cancelAndJoin()
                    }
                }
            }
        }
    }

    private fun eventLoop(screen: Screen) {
        var running = true

        while (running) {
            // A pending terminal resize must repaint even when otherwise idle; doResizeIfNecessary() is a cheap
            // AtomicReference check that only returns non-null when the size actually changed.
            if (screen.doResizeIfNecessary() != null) dirty = true

            // Render only when something changed. Both the reset and every accepted state mutation happen under
            // `stateLock`, so no update between a mutation and its render is lost.
            if (dirty) {
                synchronized(stateLock) {
                    dirty = false
                    render(screen)
                }
            }

            // Drain a paste-lookahead key first (see handleEnter); otherwise non-blocking poll + tick so streamed
            // turn output and the working indicator keep repainting, and Esc can cancel an in-flight turn. Gating
            // render on `dirty` means an idle screen just sleeps between polls.
            val key = pendingKey?.also { pendingKey = null } ?: screen.pollInput()
            if (key == null) {
                Thread.sleep(TICK_MS)
                continue
            }
            dirty = true
            running = handleKey(screen, key)
        }
    }

    private fun render(screen: Screen) {
        screen.doResizeIfNecessary()
        screen.clear()

        val size = screen.terminalSize
        val width = size.columns.coerceAtLeast(1)
        val height = size.rows.coerceAtLeast(1)
        val canvas = TerminalCanvas(screen)

        val statusHeight = if (height >= 4) 1 else 0
        val inputHeight = when {
            height >= 2 -> promptInputView.preferredHeight(width, state)
                .coerceIn(1, (height - statusHeight).coerceAtLeast(1))
            else -> 0
        }
        val transcriptHeight = (height - statusHeight - inputHeight).coerceAtLeast(0)

        val transcriptBounds = Rectangle(0, 0, width, transcriptHeight)
        val statusBounds = Rectangle(0, transcriptBounds.bottomExclusive, width, statusHeight)
        val inputBounds = Rectangle(0, statusBounds.bottomExclusive, width, height - transcriptHeight - statusHeight)

        transcriptView.render(canvas, transcriptBounds, state)
        statusBar.render(canvas, statusBounds, state)
        promptInputView.render(canvas, inputBounds, state)

        val screenBounds = Rectangle(0, 0, width, height)
        commandPaletteView.render(canvas, screenBounds, state)
        screen.cursorPosition = commandPaletteView.cursorPosition(screenBounds, state)
            ?: promptInputView.cursorPosition(inputBounds, state)
            ?: TerminalPosition(0, height - 1)
        screen.refresh()
    }

    private fun handleKey(screen: Screen, key: KeyStroke): Boolean {
        // While work is running, most input is inert: Esc cancels it, scrolling still works, Ctrl+C still quits.
        // Keep the guard through the cancelling state too: a blocking provider/catalog call may unwind slowly, and
        // accepting another submission before its job completes would violate this TUI's single-flight contract.
        if (activeSubmission.current != null) {
            return when (routeActiveSubmissionKey(key)) {
                ActiveSubmissionKeyRoute.Cancel -> true.also { activeSubmission.requestCancel() }
                ActiveSubmissionKeyRoute.ScrollLineUp -> true.also { scrollTranscript(1) }
                ActiveSubmissionKeyRoute.ScrollLineDown -> true.also { scrollTranscript(-1) }
                ActiveSubmissionKeyRoute.ScrollPageUp -> true.also { scrollTranscript(pageSize(screen)) }
                ActiveSubmissionKeyRoute.ScrollPageDown -> true.also { scrollTranscript(-pageSize(screen)) }
                ActiveSubmissionKeyRoute.Quit -> false
                ActiveSubmissionKeyRoute.Inert -> true
            }
        }
        if (state.commandPalette != null) return handlePaletteKey(screen, key)

        // Enter submits; Alt+Enter inserts a newline where the terminal delivers it (some, e.g. Windows Terminal,
        // bind Alt+Enter to fullscreen). Shift+Enter arrives as a CSI-u escape and is handled in handleCharacter.
        return when (key.keyType) {
            KeyType.EOF -> false
            KeyType.Escape -> false
            KeyType.Enter -> handleEnter(screen, key.isAltDown)
            KeyType.Backspace -> true.also { state.input.backspace() }
            KeyType.Delete -> true.also { state.input.delete() }
            KeyType.ArrowLeft -> true.also { state.input.moveLeft() }
            KeyType.ArrowRight -> true.also { state.input.moveRight() }
            KeyType.Home -> true.also { state.input.moveHome() }
            KeyType.End -> true.also { state.input.moveEnd() }
            KeyType.ArrowUp -> true.also {
                if (state.input.hasMultipleLines()) state.input.moveUp() else scrollTranscript(1)
            }
            KeyType.ArrowDown -> true.also {
                if (state.input.hasMultipleLines()) state.input.moveDown() else scrollTranscript(-1)
            }
            KeyType.PageUp -> true.also { scrollTranscript(pageSize(screen)) }
            KeyType.PageDown -> true.also { scrollTranscript(-pageSize(screen)) }
            KeyType.Character -> handleCharacter(screen, key)
            else -> true
        }
    }

    private fun handleCharacter(screen: Screen, key: KeyStroke): Boolean {
        val character = key.character ?: return true

        if ((character == 'c' || character == 'C') && key.isCtrlDown) {
            return false
        }
        if (commandPaletteCoordinator.tryOpen(character, key.isCtrlDown, activeSubmission.current == null)) return true

        // Windows Terminal / Kitty encode modified keys as CSI-u (ESC[<code>;<mods>u); Lanterna decodes the
        // leading ESC[ as Alt+[ and leaks the params as plain chars. Intercept that Alt+[ and consume the rest of
        // the sequence so a modified Enter (e.g. Shift+Enter = "13;2u") inserts a newline instead of leaking.
        if (character == '[' && key.isAltDown) {
            return readCsiUModifiedKey(screen)
        }

        if (character == '\n' || character == '\r') {
            return handleEnter(screen, key.isAltDown)
        }

        if (!character.isISOControl()) state.input.insert(character)

        return true
    }

    private fun handlePaletteKey(screen: Screen, key: KeyStroke): Boolean {
        if (key.keyType == KeyType.EOF) return false
        if (key.keyType == KeyType.Character) {
            val character = key.character
            if ((character == 'c' || character == 'C') && key.isCtrlDown) return false
            if (character == '[' && key.isAltDown) {
                if (consumeCsiUModifiedKey(screen) == CSI_U_ENTER_KEYCODE) {
                    commandPaletteCoordinator.handle(
                        PaletteKey.Enter,
                        selectionVisible = paletteHasVisibleItems(screen),
                    )
                }
                return true
            }
            if ((character == 'k' || character == 'K') && key.isCtrlDown) {
                commandPaletteCoordinator.open(PaletteOpenOrigin.Shortcut)
                return true
            }
        }

        val paletteKey = when (key.keyType) {
            KeyType.Escape -> PaletteKey.Escape
            KeyType.Enter -> PaletteKey.Enter
            KeyType.Backspace -> PaletteKey.Backspace
            KeyType.ArrowUp -> PaletteKey.ArrowUp
            KeyType.ArrowDown -> PaletteKey.ArrowDown
            KeyType.Character -> key.character?.let(PaletteKey::Character)
            else -> null
        } ?: return true

        commandPaletteCoordinator.handle(
            paletteKey,
            selectionVisible = paletteKey != PaletteKey.Enter || paletteHasVisibleItems(screen),
        )
        return true
    }

    private fun paletteHasVisibleItems(screen: Screen): Boolean {
        val size = screen.terminalSize
        val bounds = Rectangle(0, 0, size.columns.coerceAtLeast(1), size.rows.coerceAtLeast(1))
        return synchronized(stateLock) { commandPaletteView.visibleItemRows(bounds, state) > 0 }
    }

    /**
     * Consume a CSI-u sequence whose leading `ESC[` Lanterna already delivered as `Alt+[`. Drains the buffered
     * parameter characters (digits / `;`) up to the terminating `u`, then — for a modified Enter (keycode
     * [CSI_U_ENTER_KEYCODE], e.g. Shift+Enter `13;2u`) — inserts a newline. The whole sequence arrives as one
     * burst, so [Screen.pollInput] returns each following char immediately. A char that can't belong to a CSI-u
     * param stops the drain and is deferred; an unrecognized sequence is swallowed so raw `13;2u` never leaks.
     */
    private fun readCsiUModifiedKey(screen: Screen): Boolean {
        if (consumeCsiUModifiedKey(screen) == CSI_U_ENTER_KEYCODE) state.input.insertNewline()
        return true
    }

    /** Drain one Lanterna-leaked CSI-u suffix and return its numeric keycode, if complete. */
    private fun consumeCsiUModifiedKey(screen: Screen): Int? =
        consumeCsiuSuffix(screen::pollInput) { pendingKey = it }

    /**
     * Resolve a newline keystroke into either a submit or a literal line break. Alt+Enter always inserts a
     * newline. A plain Enter usually submits — but when it is part of a pasted multi-line block the terminal
     * delivers the whole paste as one burst, so more input is already buffered. We detect that with a
     * non-blocking [Screen.pollInput]: if another keystroke is immediately available, this newline is part of
     * the paste, so we insert a line break and defer the peeked key to the next event-loop iteration instead
     * of submitting mid-paste. Only a newline that arrives alone (nothing buffered behind it) submits.
     */
    private fun handleEnter(screen: Screen, altDown: Boolean): Boolean {
        if (altDown) {
            state.input.insertNewline()
            return true
        }
        val peeked = screen.pollInput()
        if (peeked != null) {
            state.input.insertNewline()
            pendingKey = peeked
            return true
        }
        return submitInput(screen)
    }

    private fun submitInput(screen: Screen): Boolean {
        val text = state.input.text
        state.input.clear()
        // The controller installs the exact identity before starting its lazy job and clears it last from the
        // terminal state application, closing fast-completion and stale-finalizer races.
        return when (
            conversationController.submitAsync(
                text,
                turnScope,
                applier = { block ->
                    synchronized(stateLock) {
                        block()
                        dirty = true
                    }
                },
                onStarted = activeSubmission::install,
                onTerminal = activeSubmission::complete,
            )
        ) {
            ConversationController.Submission.Quit -> false
            ConversationController.Submission.Handled -> true
            is ConversationController.Submission.Active -> true
        }
    }

    private fun scrollTranscript(lines: Int) {
        // Guarded by stateLock: scrolling is allowed while a turn is active, and the background turn's `fold`
        // mutates transcriptScrollback (addMessage resets it to 0) under the same lock — so this read-modify-write
        // must be serialized against it to avoid a torn/lost update.
        synchronized(stateLock) {
            state.transcriptScrollback = max(0, state.transcriptScrollback + lines)
        }
    }

    private fun pageSize(screen: Screen): Int = max(1, screen.terminalSize.rows - 5)

    private companion object {
        // Poll/tick cadence while a turn streams (~40 Hz). Rendering is gated on `dirty`, so an idle screen only
        // sleeps between polls instead of re-rendering every tick.
        const val TICK_MS = 25L
        const val TURN_SCOPE_SHUTDOWN_TIMEOUT_MS = 5_000L

    }
}
