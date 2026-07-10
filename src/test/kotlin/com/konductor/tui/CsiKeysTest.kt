package com.konductor.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsiKeysTest {
    @Test
    fun `parses a modified Enter CSI-u sequence to its keycode`() {
        assertEquals(CSI_U_ENTER_KEYCODE, parseCsiuKeycode("13;2u")) // Shift+Enter
        assertEquals(13, parseCsiuKeycode("13;5u")) // Ctrl+Enter (fixterms modifier 5)
        assertEquals(9, parseCsiuKeycode("9;2u")) // Shift+Tab, for illustration
    }

    @Test
    fun `returns null when the params are not a well-formed CSI-u sequence`() {
        assertNull(parseCsiuKeycode("")) // stray Alt+[ with nothing after it
        assertNull(parseCsiuKeycode("13;2~")) // wrong terminator
        assertNull(parseCsiuKeycode("13;2")) // no terminator
        assertNull(parseCsiuKeycode("abc"))
        assertNull(parseCsiuKeycode("[13;2u")) // the leading bracket is consumed before parsing
    }
}
