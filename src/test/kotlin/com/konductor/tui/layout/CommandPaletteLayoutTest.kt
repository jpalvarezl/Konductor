package com.konductor.tui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPaletteLayoutTest {
    @Test
    fun centersAndBoundsOverlay() {
        val terminal = Rectangle(0, 0, 120, 40)
        val layout = assertNotNull(layoutCommandPalette(terminal, requestedItemRows = 30))

        assertEquals(72, layout.frame.width)
        assertEquals(13, layout.frame.height)
        assertTrue(layout.frame.left >= terminal.left)
        assertTrue(layout.frame.top >= terminal.top)
        assertTrue(layout.frame.rightExclusive <= terminal.rightExclusive)
        assertTrue(layout.frame.bottomExclusive <= terminal.bottomExclusive)
        assertTrue(layout.itemBounds.rightExclusive <= layout.frame.rightExclusive)
        assertTrue(layout.itemBounds.bottomExclusive <= layout.frame.bottomExclusive)
    }

    @Test
    fun fitsSmallTerminal() {
        val terminal = Rectangle(0, 0, 2, 1)
        val layout = assertNotNull(layoutCommandPalette(terminal, requestedItemRows = 9))

        assertEquals(terminal, layout.frame)
        assertEquals(0, layout.titleRow)
        assertNull(layout.queryRow)
        assertTrue(layout.itemBounds.isEmpty)
        assertNull(layout.footerRow)
    }

    @Test
    fun rejectsEmptyTerminal() {
        assertNull(layoutCommandPalette(Rectangle(0, 0, 0, 4), requestedItemRows = 1))
    }
}
