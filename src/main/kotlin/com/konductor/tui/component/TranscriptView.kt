package com.konductor.tui.component

import com.googlecode.lanterna.TextColor
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.i18n.AppStrings
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import com.konductor.tui.text.MarkdownRenderer
import com.konductor.tui.text.MarkdownStyle
import com.konductor.tui.text.StyledTextSegment
import com.konductor.tui.text.wrapText
import kotlin.math.max

class TranscriptView(
    private val theme: Theme,
    private val strings: AppStrings = AppStrings.english(),
) : TuiComponent {
    override fun render(canvas: TerminalCanvas, bounds: Rectangle, state: AppState) {
        if (bounds.isEmpty) return

        canvas.fill(bounds, theme.transcriptBackground)

        val renderedLines = state.messages.flatMapIndexed { index, message ->
            buildLines(message, bounds.width) +
                if (index == state.messages.lastIndex) {
                    emptyList()
                } else {
                    listOf(RenderedLine(emptyList(), MessageRole.System))
                }
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
            var x = bounds.left
            var remaining = bounds.width
            line.segments.forEach { segment ->
                if (remaining <= 0) return@forEach
                canvas.write(
                    x = x,
                    y = firstRow + row,
                    text = segment.text,
                    foreground = colorFor(line.role, segment.style),
                    background = theme.transcriptBackground,
                    maxWidth = remaining,
                    modifiers = segment.modifiers,
                )
                val written = segment.text.length.coerceAtMost(remaining)
                x += written
                remaining -= written
            }
        }
    }

    private fun renderEmptyState(canvas: TerminalCanvas, bounds: Rectangle) {
        val title = strings.emptyStateTitle
        val subtitle = strings.emptyStateSubtitle
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

        val prefix = "${strings.roleLabel(message.role)} › "
        val continuationPrefix = " ".repeat(prefix.length)
        val contentWidth = (width - prefix.length).coerceAtLeast(1)

        val contentLines = if (message.role == MessageRole.Assistant) {
            MarkdownRenderer.render(message.content, contentWidth, strings)
        } else {
            wrapText(message.content, contentWidth).map { line -> lineOf(line) }
        }

        return contentLines.mapIndexed { index, line ->
            val linePrefix = if (index == 0) prefix else continuationPrefix
            RenderedLine(
                segments = listOf(StyledTextSegment(linePrefix)) + line.segments,
                role = message.role,
            )
        }
    }

    private fun lineOf(text: String) = com.konductor.tui.text.StyledTextLine(listOf(StyledTextSegment(text)))

    private fun colorFor(role: MessageRole, style: MarkdownStyle): TextColor = when {
        role == MessageRole.Assistant && style == MarkdownStyle.Heading -> theme.prompt
        role == MessageRole.Assistant && style == MarkdownStyle.InlineCode -> TextColor.ANSI.CYAN_BRIGHT
        role == MessageRole.Assistant && style == MarkdownStyle.CodeBlock -> TextColor.ANSI.GREEN_BRIGHT
        role == MessageRole.Assistant && style == MarkdownStyle.ListMarker -> theme.mutedText
        role == MessageRole.Assistant && style == MarkdownStyle.Emphasis -> TextColor.ANSI.WHITE_BRIGHT
        role == MessageRole.Assistant && style == MarkdownStyle.Strong -> TextColor.ANSI.WHITE_BRIGHT
        role == MessageRole.User -> theme.userText
        role == MessageRole.Assistant -> theme.assistantText
        else -> theme.systemText
    }

    private data class RenderedLine(
        val segments: List<StyledTextSegment>,
        val role: MessageRole,
    )
}
