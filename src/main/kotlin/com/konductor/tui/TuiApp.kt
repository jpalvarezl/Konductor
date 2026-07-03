package com.konductor.tui

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.konductor.conversation.ConversationController
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.tui.component.PromptInputView
import com.konductor.tui.component.StatusBar
import com.konductor.tui.component.TranscriptView
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import kotlin.math.max

class TuiApp(
    private val theme: Theme = Theme(),
) {
    private val state = AppState(
        initialMessages = listOf(
            ChatMessage(
                MessageRole.System,
                "Welcome to Konductor. This scaffold gives you a scrollable transcript and a bottom-pinned " +
                    "message composer. Replace the echo controller with real application behavior as the project grows.",
            ),
        ),
    )

    private val conversationController = ConversationController(state)
    private val transcriptView = TranscriptView(theme)
    private val statusBar = StatusBar(theme)
    private val promptInputView = PromptInputView(theme)

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

        val inputHeight = when {
            height >= 5 -> 3
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
        promptInputView.render(canvas, inputBounds, state)

        screen.cursorPosition = promptInputView.cursorPosition(inputBounds, state) ?: TerminalPosition(0, height - 1)
        screen.refresh()
    }

    private fun handleKey(screen: Screen, key: KeyStroke): Boolean = when (key.keyType) {
        KeyType.EOF -> false
        KeyType.Escape -> false
        KeyType.Enter -> submitInput()
        KeyType.Backspace -> true.also { state.input.backspace() }
        KeyType.Delete -> true.also { state.input.delete() }
        KeyType.ArrowLeft -> true.also { state.input.moveLeft() }
        KeyType.ArrowRight -> true.also { state.input.moveRight() }
        KeyType.Home -> true.also { state.input.moveHome() }
        KeyType.End -> true.also { state.input.moveEnd() }
        KeyType.ArrowUp -> true.also { scrollTranscript(1) }
        KeyType.ArrowDown -> true.also { scrollTranscript(-1) }
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

    private fun submitInput(): Boolean {
        val text = state.input.text
        state.input.clear()
        return conversationController.submit(text)
    }

    private fun scrollTranscript(lines: Int) {
        state.transcriptScrollback = max(0, state.transcriptScrollback + lines)
    }

    private fun pageSize(screen: Screen): Int = max(1, screen.terminalSize.rows - 5)
}
