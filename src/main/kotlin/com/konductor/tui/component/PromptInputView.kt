package com.konductor.tui.component

import com.googlecode.lanterna.TerminalPosition
import com.konductor.core.AppState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import com.konductor.tui.text.layoutInputText
import kotlin.math.max
import kotlin.math.min

class PromptInputView(
    private val theme: Theme,
) : TuiComponent {
    private val prompt = "› "
    private val maxComposerLines = 5

    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.inputBackground)

        if (hasDivider(bounds)) {
            canvas.write(
                x = bounds.left,
                y = bounds.top,
                text = "─".repeat(bounds.width),
                foreground = theme.divider,
                background = theme.inputBackground,
                maxWidth = bounds.width,
            )
        }

        val area = composerArea(bounds) ?: return
        val layout = inputLayout(bounds, state, area.visibleHeight)
        val firstRow = area.bottomExclusive - layout.visibleLines.size

        layout.visibleLines.forEachIndexed { index, line ->
            val row = firstRow + index
            canvas.write(
                x = bounds.left,
                y = row,
                text = if (layout.firstVisibleLine + index == 0) prompt else " ".repeat(prompt.length),
                foreground = theme.prompt,
                background = theme.inputBackground,
                maxWidth = prompt.length,
            )
            canvas.write(
                x = bounds.left + prompt.length,
                y = row,
                text = line,
                foreground = theme.normalText,
                background = theme.inputBackground,
                maxWidth = contentWidth(bounds),
            )
        }

        if (area.hintRow != null) {
            val hint = if (state.input.text.isEmpty()) {
                "Enter sends. Alt+Enter inserts a newline."
            } else if (layout.totalLines > layout.visibleHeight) {
                "Line ${layout.cursorLine + 1}/${layout.totalLines} • Alt+Enter newline • Chars: ${state.input.text.length}"
            } else {
                "Alt+Enter newline • Chars: ${state.input.text.length}"
            }
            canvas.write(
                x = bounds.left,
                y = area.hintRow,
                text = hint,
                foreground = theme.mutedText,
                background = theme.inputBackground,
                maxWidth = bounds.width,
            )
        }
    }

    fun cursorPosition(bounds: Rectangle, state: AppState): TerminalPosition? {
        if (bounds.isEmpty) return null

        val area = composerArea(bounds) ?: return null
        val layout = inputLayout(bounds, state, area.visibleHeight)
        val visibleLine = (layout.cursorLine - layout.firstVisibleLine).coerceIn(0, layout.visibleHeight - 1)
        val firstRow = area.bottomExclusive - layout.visibleLines.size
        return TerminalPosition(
            bounds.left + prompt.length + layout.cursorColumn.coerceIn(0, contentWidth(bounds)),
            firstRow + visibleLine,
        )
    }

    fun preferredHeight(width: Int, state: AppState): Int {
        if (width <= 0) return 0
        val lines = layoutInputText(
            text = state.input.text,
            cursor = state.input.cursor,
            width = max(1, width - prompt.length),
            maxVisibleLines = maxComposerLines,
        ).visibleHeight
        return 1 + lines + 1 // divider + composer + hint
    }

    private fun inputLayout(bounds: Rectangle, state: AppState, visibleHeight: Int) =
        layoutInputText(
            text = state.input.text,
            cursor = state.input.cursor,
            width = contentWidth(bounds),
            maxVisibleLines = visibleHeight,
        )

    private fun composerArea(bounds: Rectangle): ComposerArea? {
        val contentTop = bounds.top + if (hasDivider(bounds)) 1 else 0
        if (contentTop >= bounds.bottomExclusive) return null

        val contentHeight = bounds.bottomExclusive - contentTop
        val hintRow = if (contentHeight >= 2) bounds.bottomExclusive - 1 else null
        val bottom = hintRow ?: bounds.bottomExclusive
        val height = (bottom - contentTop).coerceAtLeast(0)
        if (height == 0) return null

        return ComposerArea(
            bottomExclusive = bottom,
            visibleHeight = min(maxComposerLines, height),
            hintRow = hintRow,
        )
    }

    private fun hasDivider(bounds: Rectangle): Boolean = bounds.height > 1

    private fun contentWidth(bounds: Rectangle): Int = max(1, bounds.width - prompt.length)

    private data class ComposerArea(
        val bottomExclusive: Int,
        val visibleHeight: Int,
        val hintRow: Int?,
    )
}
