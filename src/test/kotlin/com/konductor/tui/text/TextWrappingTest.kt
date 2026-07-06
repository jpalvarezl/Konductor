package com.konductor.tui.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextWrappingTest {
    @Test
    fun `returns no lines for non-positive widths`() {
        assertEquals(emptyList(), wrapText("hello", 0))
        assertEquals(emptyList(), wrapText("hello", -1))
    }

    @Test
    fun `wraps text at word boundaries`() {
        assertEquals(
            listOf("hello", "world", "from", "kotlin"),
            wrapText("hello world from kotlin", width = 6),
        )
    }

    @Test
    fun `splits long words when no word boundary is available`() {
        assertEquals(
            listOf("abc", "def", "ghi"),
            wrapText("abcdefghi", width = 3),
        )
    }

    @Test
    fun `preserves explicit blank lines`() {
        assertEquals(
            listOf("first", "", "second"),
            wrapText("first\n\nsecond", width = 20),
        )
    }
}
