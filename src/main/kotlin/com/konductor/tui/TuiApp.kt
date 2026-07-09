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
import kotlin.math.max

class TuiApp(
    private val agentLoop: AgentLoop,
    private val agentBinder: PromptAgentBinder? = null,
    private val promptAgentLifecycle: PromptAgentClient? = null,
    private val theme: Theme = Theme(),
) {
    private val state = AppState(
        initialMessages = initialMessages(),
        modelName = agentLoop.modelName,
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
                agentLoop.context,
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
            render(screen)
            val key = pendingKey?.also { pendingKey = null } ?: screen.readInput() ?: continue
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

        screen.cursorPosition = promptInputView.cursorPosition(inputBounds, state) ?: TerminalPosition(0, height - 1)
        screen.refresh()
    }

    private fun handleKey(screen: Screen, key: KeyStroke): Boolean = when (key.keyType) {
        KeyType.EOF -> false
        KeyType.Escape -> false
        // Shift+Enter is terminal-dependent: Lanterna has a flag, but common terminals report it exactly like
        // Enter. Alt+Enter is a reliable newline chord while plain Enter remains submit.
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

    private fun handleCharacter(screen: Screen, key: KeyStroke): Boolean {
        val character = key.character ?: return true

        if ((character == 'c' || character == 'C') && key.isCtrlDown) {
            return false
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
        // Pass a redraw hook so the "working…" state (and, later, streamed output) paints while the
        // synchronous turn runs — ConversationController stays free of any Lanterna dependency.
        return conversationController.submit(text) { render(screen) }
    }

    private fun scrollTranscript(lines: Int) {
        state.transcriptScrollback = max(0, state.transcriptScrollback + lines)
    }

    private fun pageSize(screen: Screen): Int = max(1, screen.terminalSize.rows - 5)
}
