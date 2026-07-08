package com.konductor.tui.component

import com.googlecode.lanterna.TerminalPosition
import com.konductor.core.AppState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import com.konductor.tui.text.wrapText

class PromptInputView(
    private val theme: Theme,
) : TuiComponent {
    private val prompt = "› "

    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.inputBackground)

        // Top divider if we have room.
        val hasDivider = bounds.height > 1
        if (hasDivider) {
            canvas.write(
                x = bounds.left,
                y = bounds.top,
                text = "─".repeat(bounds.width),
                foreground = theme.divider,
                background = theme.inputBackground,
                maxWidth = bounds.width,
            )
        }

        val contentTop = if (hasDivider) bounds.top + 1 else bounds.top
        val contentHeight = (bounds.bottomExclusive - contentTop).coerceAtLeast(0)
        if (contentHeight <= 0) return

        val hintRow = if (contentHeight >= 2) bounds.bottomExclusive - 1 else null
        val composerBottom = hintRow ?: bounds.bottomExclusive
        val composerHeight = (composerBottom - contentTop).coerceAtLeast(0)
        if (composerHeight <= 0) return

        val contentWidth = (bounds.width - prompt.length).coerceAtLeast(1)
        val rawLines = wrapText(state.input.text, contentWidth)

        // Keep the last N wrapped lines visible (chat-composer behavior).
        val visibleLines = rawLines.takeLast(composerHeight)
        val firstRow = composerBottom - visibleLines.size

        visibleLines.forEachIndexed { i, line ->
            val row = firstRow + i

            // Prompt marker only on the first visible line.
            if (i == 0) {
                canvas.write(
                    x = bounds.left,
                    y = row,
                    text = prompt,
                    foreground = theme.prompt,
                    background = theme.inputBackground,
                    maxWidth = bounds.width,
                )
            }

            canvas.write(
                x = bounds.left + prompt.length,
                y = row,
                text = line,
                foreground = theme.normalText,
                background = theme.inputBackground,
                maxWidth = contentWidth,
            )
        }

        if (hintRow != null) {
            val hint = if (state.input.text.isEmpty()) {
                "Start typing. Enter to send."
            } else {
                "Chars: ${state.input.text.length}"
            }
            canvas.write(
                x = bounds.left,
                y = hintRow,
                text = hint,
                foreground = theme.mutedText,
                background = theme.inputBackground,
                maxWidth = bounds.width,
            )
        }
    }

    fun cursorPosition(bounds: Rectangle, state: AppState): TerminalPosition? {
        if (bounds.isEmpty) return null

        val hasDivider = bounds.height > 1
        val contentTop = if (hasDivider) bounds.top + 1 else bounds.top
        val contentHeight = (bounds.bottomExclusive - contentTop).coerceAtLeast(0)
        if (contentHeight <= 0) return null

        val hintRow = if (contentHeight >= 2) bounds.bottomExclusive - 1 else null
        val composerBottom = hintRow ?: bounds.bottomExclusive
        val composerHeight = (composerBottom - contentTop).coerceAtLeast(0)
        if (composerHeight <= 0) return null

        val contentWidth = (bounds.width - prompt.length).coerceAtLeast(1)
        val lines = wrapText(state.input.text, contentWidth)

        // Cursor is always within the visible viewport (we render last N lines). If cursor is earlier than the
        // viewport start, clamp it to the start.
        val cursor = state.input.cursor.coerceIn(0, state.input.text.length)
        val (cursorLine, cursorCol) = indexToWrappedPosition(state.input.text, cursor, contentWidth)

        val totalLines = lines.size.coerceAtLeast(1)
        val firstVisibleLineIndex = (totalLines - composerHeight).coerceAtLeast(0)
        val visibleLineIndex = (cursorLine - firstVisibleLineIndex).coerceIn(0, composerHeight - 1)

        val row = (composerBottom - (totalLines - firstVisibleLineIndex)) + visibleLineIndex
        val col = prompt.length + cursorCol

        return TerminalPosition(bounds.left + col, row)
    }

    /**
     * Maps a cursor index in [text] to (line, column) in wrapped coordinates.
     *
     * This assumes wrapping at [width] and treats '\n' as a forced line break.
     */
    private fun indexToWrappedPosition(text: String, cursorIndex: Int, width: Int): Pair<Int, Int> {
        if (width <= 0) return 0 to 0

        var line = 0
        var col = 0
        var i = 0

        while (i < cursorIndex && i < text.length) {
            val ch = text[i]
            if (ch == '\n') {
                line += 1
                col = 0
            } else {
                col += 1
                if (col >= width) {
                    line += 1
                    col = 0
                }
            }
            i += 1
        }

        return line to col
    }
}
