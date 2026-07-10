package com.konductor.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.konductor.agent.AgentLoop
import com.konductor.conversation.ConversationController
import com.konductor.conversation.PromptAgentCommand
import com.konductor.conversation.sessionEntriesToMessages
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.tui.component.PromptInputView
import com.konductor.tui.component.StatusBar
import com.konductor.tui.component.TranscriptView
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.max

class TuiApp(
    private val agentLoop: AgentLoop,
    private val agentBinder: PromptAgentBinder? = null,
    private val promptAgentLifecycle: PromptAgentClient? = null,
    private val contextWindowTokens: Int = 128_000,
    private val theme: Theme = Theme(),
) {
    private val state = AppState(
        initialMessages = initialMessages(),
        modelName = agentLoop.modelName,
        contextWindowTokens = contextWindowTokens,
        activeAgentName = agentBinder?.activeAgent,
    )

    init {
        // Restore the status-bar token count from the most recent assistant entry when resuming a session.
        state.lastUsage = agentLoop.session.entries.asReversed()
            .filterIsInstance<AssistantEntry>().firstOrNull { it.usage != null }?.usage
    }

    /** Seed the transcript: a resumed session's entries, or the first-run welcome for a fresh session. */
    private fun initialMessages(): List<ChatMessage> {
        val entries = agentLoop.session.entries
        if (entries.isEmpty()) {
            return listOf(
                ChatMessage(
                    MessageRole.System,
                    "Welcome to Konductor. Type a message and press Enter to send it to the model. " +
                        "Use /quit, Esc, or Ctrl+C to exit.",
                ),
            )
        }
        return sessionEntriesToMessages(entries) + ChatMessage(
            MessageRole.System,
            "Resumed session ${agentLoop.session.id.toString().take(8)} (${entries.size} entries).",
        )
    }

    // /agent is available only on the Prompt provider (binder for live switching + lifecycle for create/list).
    // The recorder persists the bound agent onto the active session's header via the (agent-agnostic) loop.
    private val agentCommand: PromptAgentCommand? =
        if (agentBinder != null && promptAgentLifecycle != null) {
            PromptAgentCommand(
                state,
                { agentLoop.context },
                agentBinder,
                promptAgentLifecycle,
                recordAgent = { name ->
                    agentLoop.session.promptAgentName = name
                    agentLoop.persistSessionHeader()
                },
            )
        } else {
            null
        }

    private val conversationController = ConversationController(state, agentLoop, agentCommand)

    init {
        // Sync the persisted-agent binding to the initial session: a fresh session adopts the currently-bound
        // (config) agent; a resumed/continued session restores its saved agent — validated, since agents are
        // volatile — falling back to ephemeral if it is gone.
        agentCommand?.let { command ->
            val saved = agentLoop.session.promptAgentName
            if (saved != null || agentLoop.session.entries.isNotEmpty()) command.onResumedSession(saved)
            else command.onFreshSession()
        }
    }

    private val transcriptView = TranscriptView(theme)
    private val statusBar = StatusBar(theme)
    private val promptInputView = PromptInputView(theme)

    // One-key lookahead used by the paste heuristic: a newline that turns out to be part of a paste stashes the
    // key it peeked here so the event loop processes it on the next iteration (instead of recursing per line).
    private var pendingKey: KeyStroke? = null

    // A turn runs on this background scope so the Lanterna event loop stays free to poll input (Esc) and repaint
    // streamed output. `stateLock` serializes the turn's AppState mutations against the render loop; `activeTurn`
    // is the in-flight turn's Job (cancelled by Esc).
    private val turnScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateLock = Any()
    @Volatile
    private var activeTurn: Job? = null

    // Set whenever state changes (keypress, resize, or a background turn update via `fold`). The event loop only
    // re-renders when it's true, so an idle prompt doesn't re-wrap the whole transcript every tick (~40 Hz).
    @Volatile
    private var dirty = true

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
            screen.stopScreen()
        }
    }

    private fun eventLoop(screen: Screen) {
        var running = true

        while (running) {
            // A pending terminal resize must repaint even when otherwise idle; doResizeIfNecessary() is a cheap
            // AtomicReference check that only returns non-null when the size actually changed.
            if (screen.doResizeIfNecessary() != null) dirty = true

            // Render only when something changed. Both the reset and every state mutation (render below, the turn's
            // `fold`, cancelActiveTurn) happen under `stateLock`, so no update between a mutation and its render is lost.
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
        turnScope.cancel()
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

        screen.cursorPosition = promptInputView.cursorPosition(inputBounds, state) ?: TerminalPosition(0, height - 1)
        screen.refresh()
    }

    private fun handleKey(screen: Screen, key: KeyStroke): Boolean {
        // While a turn is running, most input is inert: Esc cancels it, scrolling still works, Ctrl+C still quits.
        if (activeTurn?.isActive == true) {
            return when (key.keyType) {
                KeyType.Escape -> true.also { cancelActiveTurn() }
                KeyType.ArrowUp -> true.also { scrollTranscript(1) }
                KeyType.ArrowDown -> true.also { scrollTranscript(-1) }
                KeyType.PageUp -> true.also { scrollTranscript(pageSize(screen)) }
                KeyType.PageDown -> true.also { scrollTranscript(-pageSize(screen)) }
                KeyType.Character -> !((key.character == 'c' || key.character == 'C') && key.isCtrlDown)
                else -> true
            }
        }
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

    /** Cancel the in-flight turn (Esc) and note it in the transcript. */
    private fun cancelActiveTurn() {
        activeTurn?.cancel()
        synchronized(stateLock) {
            state.addMessage(ChatMessage(MessageRole.System, "⏹ Turn cancelled."))
            dirty = true
        }
    }

    private fun handleCharacter(screen: Screen, key: KeyStroke): Boolean {
        val character = key.character ?: return true

        if ((character == 'c' || character == 'C') && key.isCtrlDown) {
            return false
        }

        // Windows Terminal / Kitty encode modified keys as CSI-u (ESC[<code>;<mods>u); Lanterna decodes the
        // leading ESC[ as Alt+[ and leaks the params as plain chars. Intercept that Alt+[ and consume the rest of
        // the sequence so a modified Enter (e.g. Shift+Enter = "13;2u") inserts a newline instead of leaking.
        if (character == '[' && key.isAltDown) {
            return readCsiUModifiedKey(screen)
        }

        if (character == '\n' || character == '\r') {
            return handleEnter(screen, key.isAltDown)
        }

        if (!character.isISOControl()) {
            state.input.insert(character)
        }

        return true
    }

    /**
     * Consume a CSI-u sequence whose leading `ESC[` Lanterna already delivered as `Alt+[`. Drains the buffered
     * parameter characters (digits / `;`) up to the terminating `u`, then — for a modified Enter (keycode
     * [CSI_U_ENTER_KEYCODE], e.g. Shift+Enter `13;2u`) — inserts a newline. The whole sequence arrives as one
     * burst, so [Screen.pollInput] returns each following char immediately. A char that can't belong to a CSI-u
     * param stops the drain and is deferred; an unrecognized sequence is swallowed so raw `13;2u` never leaks.
     */
    private fun readCsiUModifiedKey(screen: Screen): Boolean {
        val params = StringBuilder()
        while (params.length < CSI_U_MAX_PARAM_LENGTH) {
            val next = screen.pollInput() ?: break
            val ch = next.character
            if (next.keyType != KeyType.Character || ch == null) {
                pendingKey = next // not part of the sequence; handle it next tick
                break
            }
            when {
                ch == 'u' || ch == '~' -> { params.append(ch); break } // CSI terminator
                ch.isDigit() || ch == ';' -> params.append(ch)
                else -> { pendingKey = next; break } // a genuine Alt+[ prefix, not a CSI-u sequence
            }
        }
        if (parseCsiuKeycode(params.toString()) == CSI_U_ENTER_KEYCODE) {
            state.input.insertNewline()
        }
        return true
    }

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
        // Launch the turn on the background scope; it folds AppState under stateLock while the event loop keeps
        // polling input + repainting. The returned Job is cancelable via Esc.
        return when (val submission = conversationController.submitAsync(text, turnScope) { block ->
            synchronized(stateLock) {
                block()
                dirty = true
            }
        }) {
            ConversationController.Submission.Quit -> false
            ConversationController.Submission.Handled -> true
            is ConversationController.Submission.Turn -> true.also { activeTurn = submission.job }
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

        // Upper bound on CSI-u parameter chars to drain (e.g. "13;2u") so a stray Alt+[ can't spin the loop.
        const val CSI_U_MAX_PARAM_LENGTH = 12
    }
}
