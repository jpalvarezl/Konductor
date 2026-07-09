package com.konductor.tui.text

import kotlin.test.Test
import kotlin.test.assertEquals

class InputTextLayoutTest {
    @Test
    fun `hard wraps input and preserves explicit blank lines`() {
        val layout = layoutInputText("abcd\n\nef", cursor = 8, width = 3, maxVisibleLines = 5)

        assertEquals(listOf("abc", "d", "", "ef"), layout.lines)
        assertEquals(3, layout.cursorLine)
        assertEquals(2, layout.cursorColumn)
    }

    @Test
    fun `scrolls vertically to keep the cursor visible`() {
        val layout = layoutInputText("1\n2\n3\n4\n5\n6", cursor = 11, width = 10, maxVisibleLines = 5)

        assertEquals(6, layout.totalLines)
        assertEquals(1, layout.firstVisibleLine)
        assertEquals(listOf("2", "3", "4", "5", "6"), layout.visibleLines)
    }

    @Test
    fun `keeps the caret on the last real line when text ends at an exact wrap boundary`() {
        // "abc" exactly fills a width-3 line; the caret at the end must stay on that line (col 3), not jump
        // to a non-existent wrapped row (which previously mis-rendered at column 0).
        val layout = layoutInputText("abc", cursor = 3, width = 3, maxVisibleLines = 5)

        assertEquals(listOf("abc"), layout.lines)
        assertEquals(0, layout.cursorLine)
        assertEquals(3, layout.cursorColumn)
    }

    @Test
    fun `caret at a mid-text wrap boundary moves to the start of the next line`() {
        // Cursor between 'c' and 'd' in "abcdef" (width 3): the next line already exists, so the caret sits
        // at its start rather than clamping back.
        val layout = layoutInputText("abcdef", cursor = 3, width = 3, maxVisibleLines = 5)

        assertEquals(listOf("abc", "def"), layout.lines)
        assertEquals(1, layout.cursorLine)
        assertEquals(0, layout.cursorColumn)
    }
}
