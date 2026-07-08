package com.konductor.tui.text

import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.TextColor.ANSI

/**
 * Minimal Markdown-ish highlighter for chat transcripts.
 *
 * This is intentionally lightweight (no full Markdown rendering/layout): it just emits styled segments that can be
 * wrapped and painted by the TUI.
 */
object MarkdownHighlighter {
    data class Segment(
        val text: String,
        val foreground: TextColor? = null,
    )

    /**
     * Highlights a subset of Markdown:
     * - Inline code using backticks: `code`
     * - Code fences using triple backticks: ```...```
     * - Bold using **bold**
     * - Italic using *italic*
     *
     * Any delimiter characters are kept in the output (this is highlighting, not parsing away markup).
     */
    fun highlight(
        text: String,
        base: TextColor,
        code: TextColor = ANSI.YELLOW,
        emphasis: TextColor = ANSI.CYAN,
    ): List<Segment> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<Segment>()
        val sb = StringBuilder()

        fun flush(color: TextColor?) {
            if (sb.isEmpty()) return
            segments += Segment(sb.toString(), color)
            sb.setLength(0)
        }

        var i = 0
        var inFence = false
        var inInlineCode = false
        var bold = false
        var italic = false

        fun currentColor(): TextColor = when {
            inFence || inInlineCode -> code
            bold || italic -> emphasis
            else -> base
        }

        while (i < text.length) {
            val c = text[i]

            // Code fence ``` toggles fence mode (takes precedence)
            if (!inInlineCode && i + 2 < text.length && text.startsWith("```", i)) {
                val before = currentColor()
                flush(before)
                inFence = !inFence
                // keep the delimiter highlighted as code
                sb.append("```")
                flush(code)
                i += 3
                continue
            }

            // Inline code ` toggles inline code (disabled inside fences)
            if (!inFence && c == '`') {
                val before = currentColor()
                flush(before)
                inInlineCode = !inInlineCode
                sb.append('`')
                flush(code)
                i += 1
                continue
            }

            // Emphasis toggles (disabled inside any code)
            if (!inFence && !inInlineCode) {
                // Bold **
                if (i + 1 < text.length && text.startsWith("**", i)) {
                    val before = currentColor()
                    flush(before)
                    bold = !bold
                    sb.append("**")
                    flush(emphasis)
                    i += 2
                    continue
                }

                // Italic * (avoid treating ** as two italics; handled above)
                if (c == '*') {
                    val before = currentColor()
                    flush(before)
                    italic = !italic
                    sb.append('*')
                    flush(emphasis)
                    i += 1
                    continue
                }
            }

            val col = currentColor()
            // Coalesce runs of same color by flushing only when color changes.
            // We do this by flushing when the next char would have a different color, but simplest is to append and
            // flush opportunistically: keep a pending run in sb with an implicit color.
            // Here we just append; run boundaries are handled by the delimiter cases above.
            sb.append(c)

            i += 1
        }

        flush(currentColor())

        // Normalize: remove nulls by applying base if needed
        return segments.map { seg ->
            if (seg.foreground == null) seg.copy(foreground = base) else seg
        }
    }
}
