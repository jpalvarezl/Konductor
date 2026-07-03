package com.konductor.tui.component

import com.googlecode.lanterna.TextColor
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import com.konductor.tui.text.wrapText
import kotlin.math.max

class TranscriptView(
    private val theme: Theme,
) : TuiComponent {
    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.transcriptBackground)

        val renderedLines = state.messages.flatMapIndexed { index, message ->
            buildLines(message, bounds.width) +
                if (index == state.messages.lastIndex) emptyList() else listOf(RenderedLine("", MessageRole.System))
        }

        if (renderedLines.isEmpty()) {
            renderEmptyState(canvas, bounds)
            return
        }

        val maxScrollback = max(0, renderedLines.size - bounds.height)
        state.transcriptScrollback = state.transcriptScrollback.coerceIn(0, maxScrollback)

        val startIndex = (renderedLines.size - bounds.height - state.transcriptScrollback).coerceAtLeast(0)
        val visibleLines = renderedLines
            .drop(startIndex)
            .take(bounds.height)

        // Chat-style anchoring: keep the newest visible content pinned to the bottom of the transcript pane. When the
        // transcript grows beyond the pane height, older lines naturally scroll off the top.
        val firstRow = bounds.bottomExclusive - visibleLines.size

        visibleLines.forEachIndexed { row, line ->
            canvas.write(
                x = bounds.left,
                y = firstRow + row,
                text = line.text,
                foreground = colorFor(line.role),
                background = theme.transcriptBackground,
                maxWidth = bounds.width,
            )
        }
    }

    private fun renderEmptyState(canvas: TerminalCanvas, bounds: Rectangle) {
        val title = "Konductor"
        val subtitle = "Type a message below. Use /quit, Esc, or Ctrl+C to exit."
        val centerRow = bounds.top + bounds.height / 2

        canvas.write(
            x = bounds.left + ((bounds.width - title.length) / 2).coerceAtLeast(0),
            y = centerRow.coerceAtLeast(bounds.top),
            text = title,
            foreground = theme.systemText,
            background = theme.transcriptBackground,
            maxWidth = bounds.width,
        )

        if (centerRow + 1 < bounds.bottomExclusive) {
            canvas.write(
                x = bounds.left + ((bounds.width - subtitle.length) / 2).coerceAtLeast(0),
                y = centerRow + 1,
                text = subtitle,
                foreground = theme.mutedText,
                background = theme.transcriptBackground,
                maxWidth = bounds.width,
            )
        }
    }

    private fun buildLines(message: ChatMessage, width: Int): List<RenderedLine> {
        if (width <= 0) return emptyList()

        val prefix = "${message.role.label} › "
        val continuationPrefix = " ".repeat(prefix.length)
        val contentWidth = (width - prefix.length).coerceAtLeast(1)

        return wrapText(message.content, contentWidth).mapIndexed { index, line ->
            RenderedLine(
                text = if (index == 0) prefix + line else continuationPrefix + line,
                role = message.role,
            )
        }
    }

    private fun colorFor(role: MessageRole): TextColor = when (role) {
        MessageRole.User -> theme.userText
        MessageRole.Assistant -> theme.assistantText
        MessageRole.System -> theme.systemText
    }

    private data class RenderedLine(
        val text: String,
        val role: MessageRole,
    )
}
