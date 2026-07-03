package com.konductor.tui

import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.screen.Screen
import com.konductor.tui.layout.Rectangle

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
}
