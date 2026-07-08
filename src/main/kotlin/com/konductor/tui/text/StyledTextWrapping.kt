package com.konductor.tui.text

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor

data class StyledSpan(
    val text: String,
    val foreground: TextColor,
    val modifiers: Set<SGR> = emptySet(),
)

data class StyledLine(
    val spans: List<StyledSpan>,
)

/**
 * Wraps styled spans into lines, preserving style per span.
 *
 * Wrapping is character-based and does not attempt to be smart about wide glyphs.
 */
fun wrapStyledText(spans: List<StyledSpan>, maxWidth: Int): List<StyledLine> {
    if (maxWidth <= 0) return emptyList()
    if (spans.isEmpty()) return emptyList()

    val lines = mutableListOf<MutableList<StyledSpan>>()
    var currentLine = mutableListOf<StyledSpan>()
    var currentWidth = 0

    fun pushLine() {
        lines += currentLine
        currentLine = mutableListOf()
        currentWidth = 0
    }

    fun appendSpan(text: String, color: TextColor, modifiers: Set<SGR>) {
        if (text.isEmpty()) return
        // Coalesce adjacent spans of same style
        val last = currentLine.lastOrNull()
        if (last != null && last.foreground == color && last.modifiers == modifiers) {
            currentLine[currentLine.lastIndex] = last.copy(text = last.text + text)
        } else {
            currentLine += StyledSpan(text, color, modifiers)
        }
    }

    for (span in spans) {
        var idx = 0
        val s = span.text
        while (idx < s.length) {
            val ch = s[idx]
            if (ch == '\n') {
                pushLine()
                idx++
                continue
            }

            if (currentWidth == maxWidth) {
                pushLine()
            }

            appendSpan(ch.toString(), span.foreground, span.modifiers)
            currentWidth += 1
            idx++
        }
    }

    if (currentLine.isNotEmpty() || lines.isEmpty()) {
        lines += currentLine
    }

    return lines.map { StyledLine(it.toList()) }
}
