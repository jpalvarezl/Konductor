package com.konductor.tui

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.screen.Screen
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.text.StyledSpan

class TerminalCanvas(
    screen: Screen,
) {
    private val graphics: TextGraphics = screen.newTextGraphics()

    fun fill(bounds: Rectangle, background: TextColor) {
        if (bounds.isEmpty) return

        graphics.backgroundColor = background
        graphics.foregroundColor = TextColor.ANSI.DEFAULT

        val blank = " ".repeat(bounds.width)
        for (row in bounds.top until bounds.bottomExclusive) {
            graphics.putString(bounds.left, row, blank)
        }
    }

    fun write(
        x: Int,
        y: Int,
        text: String,
        foreground: TextColor,
        background: TextColor = TextColor.ANSI.DEFAULT,
        maxWidth: Int = text.length,
    ) {
        if (text.isEmpty() || maxWidth <= 0) return

        graphics.foregroundColor = foreground
        graphics.backgroundColor = background
        graphics.putString(x, y, text.take(maxWidth))
    }

    fun writeSpans(
        x: Int,
        y: Int,
        spans: List<StyledSpan>,
        background: TextColor = TextColor.ANSI.DEFAULT,
        maxWidth: Int,
    ) {
        if (maxWidth <= 0 || spans.isEmpty()) return

        var col = 0
        for (span in spans) {
            if (col >= maxWidth) break
            if (span.text.isEmpty()) continue

            val remaining = maxWidth - col
            val toWrite = span.text.take(remaining)
            if (toWrite.isEmpty()) continue

            graphics.foregroundColor = span.foreground
            graphics.backgroundColor = background

            // Use Lanterna's native SGR styling.
            if (span.modifiers.isEmpty()) {
                graphics.putString(x + col, y, toWrite)
            } else {
                val mods: Array<SGR> = span.modifiers.toTypedArray()
                val head = mods[0]
                val tail = if (mods.size > 1) mods.copyOfRange(1, mods.size) else emptyArray()
                graphics.putString(x + col, y, toWrite, head, *tail)
            }

            col += toWrite.length
        }
    }
}
