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
import com.konductor.conversation.SlashCommands
import com.konductor.conversation.sessionEntriesToMessages
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AssistantEntry
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.tui.component.PromptInputView
import com.konductor.tui.component.SlashCommandOverlay
import com.konductor.tui.component.StatusBar
import com.konductor.tui.component.TranscriptView
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import kotlin.math.max
import kotlin.math.min

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

    private val slashCommandOverlay = SlashCommandOverlay(
        theme = theme,
        commands = SlashCommands.topLevel,
        subcommands = SlashCommands.subcommands,
    )

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
            val key = screen.readInput() ?: continue
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

        // Give the composer a few lines so input can wrap.
        // PromptInputView caps visible lines to 5, but we allocate an extra row when possible for a hint/footer.
        val inputHeight = when {
            height >= 8 -> 6
            height >= 6 -> 4
            height >= 2 -> 1
            else -> 0
        }
        val statusHeight = if (height >= 4) 1 else 0
        val transcriptHeight = (height - statusHeight - inputHeight).coerceAtLeast(0)

        val transcriptBounds = Rectangle(0, 0, width, transcriptHeight)
        val statusBounds = Rectangle(0, transcriptBounds.bottomExclusive, width, statusHeight)
        val inputBounds = Rectangle(0, statusBounds.bottomExclusive, width, height - transcriptHeight - statusHeight)

        transcriptView.render(canvas, transcriptBounds, state)
        statusBar.render(canvas, statusBounds, state)

        // Render slash-command suggestions as an overlay above the composer content.
        // Important: this overlay must not overlap the input area; otherwise the input text/cursor
        // appears to hover over the overlay (and the cursor can look vertically misaligned).
        // So we carve out vertical space and render the composer underneath.
        val overlayMax = 4
        val overlayHeight = if (slashCommandOverlay.isActive(state)) {
            min(overlayMax, inputBounds.height).coerceAtLeast(0)
        } else {
            0
        }

        val overlayBounds = if (overlayHeight > 0) Rectangle(
            inputBounds.left,
            inputBounds.top,
            inputBounds.width,
            overlayHeight,
        ) else Rectangle(0, 0, 0, 0)

        val composerBounds = if (overlayHeight > 0) Rectangle(
            inputBounds.left,
            inputBounds.top + overlayHeight,
            inputBounds.width,
            (inputBounds.height - overlayHeight).coerceAtLeast(0),
        ) else inputBounds

        slashCommandOverlay.render(canvas, overlayBounds, state)
        promptInputView.render(canvas, composerBounds, state)

        screen.cursorPosition = promptInputView.cursorPosition(composerBounds, state) ?: TerminalPosition(0, height - 1)
        screen.refresh()
    }

    private fun handleKey(screen: Screen, key: KeyStroke): Boolean = when (key.keyType) {
        KeyType.EOF -> false
        KeyType.Escape -> false
        KeyType.Enter ->
            if (slashCommandOverlay.isActive(state) && slashCommandOverlay.acceptSelection(state)) true
            else submitInput(screen)
        KeyType.Backspace -> true.also { state.input.backspace() }
        KeyType.Delete -> true.also { state.input.delete() }
        KeyType.ArrowLeft -> true.also { state.input.moveLeft() }
        KeyType.ArrowRight -> true.also { state.input.moveRight() }
        KeyType.Home -> true.also { state.input.moveHome() }
        KeyType.End -> true.also { state.input.moveEnd() }
        KeyType.ArrowUp ->
            if (slashCommandOverlay.isActive(state)) true.also { slashCommandOverlay.moveSelection(-1, state) }
            else true.also { scrollTranscript(1) }
        KeyType.ArrowDown ->
            if (slashCommandOverlay.isActive(state)) true.also { slashCommandOverlay.moveSelection(1, state) }
            else true.also { scrollTranscript(-1) }
        KeyType.PageUp -> true.also { scrollTranscript(pageSize(screen)) }
        KeyType.PageDown -> true.also { scrollTranscript(-pageSize(screen)) }
        KeyType.Character -> handleCharacter(key)
        else -> true
    }

    private fun handleCharacter(key: KeyStroke): Boolean {
        val character = key.character ?: return true

        if ((character == 'c' || character == 'C') && key.isCtrlDown) {
            return false
        }

        if (!character.isISOControl()) {
            state.input.insert(character)
        }

        return true
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
