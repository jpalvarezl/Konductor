package com.konductor.tui.component

import com.googlecode.lanterna.TerminalPosition
import com.konductor.core.AppState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import kotlin.math.max

class PromptInputView(
    private val theme: Theme,
) : TuiComponent {
    private val prompt = "› "

    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.inputBackground)

        canvas.write(
            x = bounds.left,
            y = bounds.top,
            text = "─".repeat(bounds.width),
            foreground = theme.divider,
            background = theme.inputBackground,
            maxWidth = bounds.width,
        )

        val inputRow = inputRow(bounds)
        if (inputRow >= bounds.bottomExclusive) return

        canvas.write(
            x = bounds.left,
            y = inputRow,
            text = prompt,
            foreground = theme.prompt,
            background = theme.inputBackground,
            maxWidth = bounds.width,
        )

        val viewport = inputViewport(bounds, state)
        canvas.write(
            x = bounds.left + prompt.length,
            y = inputRow,
            text = viewport.visibleText,
            foreground = theme.normalText,
            background = theme.inputBackground,
            maxWidth = viewport.visibleWidth,
        )

        if (bounds.height >= 3) {
            val hint = if (state.input.text.isEmpty()) {
                "Start typing. This is a single-line composer scaffold."
            } else {
                "Chars: ${state.input.text.length}"
            }
            canvas.write(
                x = bounds.left,
                y = bounds.bottomExclusive - 1,
                text = hint,
                foreground = theme.mutedText,
                background = theme.inputBackground,
                maxWidth = bounds.width,
            )
        }
    }

    fun cursorPosition(bounds: Rectangle, state: AppState): TerminalPosition? {
        if (bounds.isEmpty) return null

        val inputRow = inputRow(bounds)
        if (inputRow >= bounds.bottomExclusive) return null

        val viewport = inputViewport(bounds, state)
        val cursorOffset = (state.input.cursor - viewport.startIndex).coerceIn(0, viewport.visibleWidth)
        return TerminalPosition(bounds.left + prompt.length + cursorOffset, inputRow)
    }

    private fun inputRow(bounds: Rectangle): Int = bounds.top + if (bounds.height == 1) 0 else 1

    private fun inputViewport(bounds: Rectangle, state: AppState): InputViewport {
        val visibleWidth = max(1, bounds.width - prompt.length - 1)
        val startIndex = (state.input.cursor - visibleWidth).coerceAtLeast(0)
        val visibleText = state.input.text.drop(startIndex).take(visibleWidth)

        return InputViewport(
            startIndex = startIndex,
            visibleWidth = visibleWidth,
            visibleText = visibleText,
        )
    }

    private data class InputViewport(
        val startIndex: Int,
        val visibleWidth: Int,
        val visibleText: String,
    )
}
