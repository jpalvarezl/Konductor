package com.konductor.tui.component

import com.googlecode.lanterna.TextColor
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import com.konductor.tui.text.MarkdownHighlighter
import com.konductor.tui.text.StyledLine
import com.konductor.tui.text.StyledSpan
import com.konductor.tui.text.wrapStyledText
import kotlin.math.max

class TranscriptView(
    private val theme: Theme,
) : TuiComponent {
    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.transcriptBackground)

        val renderedLines = state.messages.flatMapIndexed { index, message ->
            buildLines(message, bounds.width) +
                if (index == state.messages.lastIndex) emptyList() else listOf(RenderedLine(listOf(StyledSpan("", theme.systemText)), MessageRole.System))
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

        // Chat-style anchoring: keep the newest visible content pinned to the bottom of the transcript pane.
        val firstRow = bounds.bottomExclusive - visibleLines.size

        visibleLines.forEachIndexed { row, line ->
            canvas.writeSpans(
                x = bounds.left,
                y = firstRow + row,
                spans = line.spans,
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

        val baseColor = colorFor(message.role)

        val highlighted = MarkdownHighlighter.highlight(
            text = message.content,
            base = baseColor,
            code = codeColorFor(message.role),
            emphasis = emphasisColorFor(message.role),
        ).map { StyledSpan(it.text, it.foreground ?: baseColor) }

        val wrapped: List<StyledLine> = wrapStyledText(highlighted, contentWidth)

        return wrapped.mapIndexed { index, line ->
            val prefixText = if (index == 0) prefix else continuationPrefix
            val spans = buildList {
                add(StyledSpan(prefixText, baseColor))
                addAll(line.spans)
            }
            RenderedLine(spans = spans, role = message.role)
        }
    }

    private fun colorFor(role: MessageRole): TextColor = when (role) {
        MessageRole.User -> theme.userText
        MessageRole.Assistant -> theme.assistantText
        MessageRole.System -> theme.systemText
    }

    private fun codeColorFor(role: MessageRole): TextColor = when (role) {
        MessageRole.User -> theme.userText
        MessageRole.Assistant -> TextColor.ANSI.YELLOW
        MessageRole.System -> theme.systemText
    }

    private fun emphasisColorFor(role: MessageRole): TextColor = when (role) {
        MessageRole.User -> theme.userText
        MessageRole.Assistant -> TextColor.ANSI.CYAN
        MessageRole.System -> theme.systemText
    }

    private data class RenderedLine(
        val spans: List<StyledSpan>,
        val role: MessageRole,
    )
}
