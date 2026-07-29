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
    fun `one two and three row terminals expose results only when visible`() {
        val oneRow = assertNotNull(layoutCommandPalette(Rectangle(0, 0, 8, 1), requestedItemRows = 9))
        val twoRows = assertNotNull(layoutCommandPalette(Rectangle(0, 0, 8, 2), requestedItemRows = 9))
        val threeRows = assertNotNull(layoutCommandPalette(Rectangle(0, 0, 8, 3), requestedItemRows = 9))

        assertEquals(0, oneRow.itemBounds.height)
        assertNull(oneRow.queryRow)
        assertEquals(0, twoRows.itemBounds.height)
        assertEquals(1, twoRows.queryRow)
        assertEquals(1, threeRows.itemBounds.height)
        assertNull(threeRows.footerRow)
    }

    @Test
    fun rejectsEmptyTerminal() {
        assertNull(layoutCommandPalette(Rectangle(0, 0, 0, 4), requestedItemRows = 1))
    }
}
