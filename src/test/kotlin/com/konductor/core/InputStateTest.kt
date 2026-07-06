package com.konductor.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InputStateTest {
    @Test
    fun `inserts text at the cursor`() {
        val input = InputState()
        input.type("helo")
        input.moveLeft()
        input.insert('l')

        assertEquals("hello", input.text)
        assertEquals(4, input.cursor)
    }

    @Test
    fun `cursor movement is clamped to input bounds`() {
        val input = InputState()
        input.type("hi")

        repeat(5) { input.moveLeft() }
        assertEquals(0, input.cursor)

        repeat(5) { input.moveRight() }
        assertEquals(2, input.cursor)
    }

    @Test
    fun `backspace and delete remove characters around the cursor`() {
        val input = InputState()
        input.type("abcd")
        input.moveLeft()
        input.moveLeft()

        input.backspace()
        assertEquals("acd", input.text)
        assertEquals(1, input.cursor)

        input.delete()
        assertEquals("ad", input.text)
        assertEquals(1, input.cursor)
    }

    @Test
    fun `clear resets text and cursor`() {
        val input = InputState()
        input.type("hello")

        input.clear()

        assertEquals("", input.text)
        assertEquals(0, input.cursor)
    }

    private fun InputState.type(text: String) {
        text.forEach(::insert)
    }
}
