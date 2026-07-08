package com.konductor.tui.text

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor

/**
 * Minimal Markdown-ish highlighter for chat transcripts.
 *
 * Uses Lanterna-native styling (SGR modifiers) instead of attempting full Markdown rendering.
 *
 * Supported (highlighting only, delimiters preserved):
 * - Code fences: ```...```
 * - Inline code: `code`
 * - Bold: **bold**
 * - Italic: *italic*
 */
object MarkdownHighlighter {
    data class Segment(
        val text: String,
        val foreground: TextColor? = null,
        val modifiers: Set<SGR> = emptySet(),
    )

    fun highlight(
        text: String,
        baseForeground: TextColor,
        codeForeground: TextColor,
        emphasisForeground: TextColor,
    ): List<Segment> {
        if (text.isEmpty()) return emptyList()

        val out = mutableListOf<Segment>()
        val sb = StringBuilder()

        var currentFg: TextColor = baseForeground
        var currentMods: Set<SGR> = emptySet()

        fun setStyle(fg: TextColor, mods: Set<SGR>) {
            currentFg = fg
            currentMods = mods
        }

        fun flush() {
            if (sb.isEmpty()) return
            out += Segment(sb.toString(), currentFg, currentMods)
            sb.setLength(0)
        }

        var i = 0
        var inFence = false
        var inInlineCode = false
        var bold = false
        var italic = false

        fun recomputeStyle() {
            when {
                inFence || inInlineCode -> setStyle(codeForeground, emptySet())
                else -> {
                    val mods = buildSet {
                        if (bold) add(SGR.BOLD)
                        if (italic) add(SGR.ITALIC)
                    }
                    val fg = if (mods.isNotEmpty()) emphasisForeground else baseForeground
                    setStyle(fg, mods)
                }
            }
        }

        recomputeStyle()

        while (i < text.length) {
            // Fence toggle ``` (only when not inside inline code)
            if (!inInlineCode && i + 2 < text.length && text.startsWith("```", i)) {
                flush()
                // delimiter itself should be styled as code
                setStyle(codeForeground, emptySet())
                sb.append("```")
                flush()

                inFence = !inFence
                recomputeStyle()
                i += 3
                continue
            }

            val c = text[i]

            // Inline code toggle ` (disabled inside fences)
            if (!inFence && c == '`') {
                flush()
                setStyle(codeForeground, emptySet())
                sb.append('`')
                flush()

                inInlineCode = !inInlineCode
                recomputeStyle()
                i += 1
                continue
            }

            // Emphasis toggles (disabled inside any code)
            if (!inFence && !inInlineCode) {
                if (i + 1 < text.length && text.startsWith("**", i)) {
                    flush()
                    // delimiter highlighted as emphasis
                    setStyle(emphasisForeground, setOf(SGR.BOLD))
                    sb.append("**")
                    flush()

                    bold = !bold
                    recomputeStyle()
                    i += 2
                    continue
                }

                if (c == '*') {
                    flush()
                    setStyle(emphasisForeground, setOf(SGR.ITALIC))
                    sb.append('*')
                    flush()

                    italic = !italic
                    recomputeStyle()
                    i += 1
                    continue
                }
            }

            sb.append(c)
            i += 1
        }

        flush()
        return out
    }
}
