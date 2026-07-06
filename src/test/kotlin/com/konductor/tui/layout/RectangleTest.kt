package com.konductor.tui.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RectangleTest {
    @Test
    fun `computes exclusive right and bottom edges`() {
        val rectangle = Rectangle(left = 2, top = 3, width = 10, height = 5)

        assertEquals(12, rectangle.rightExclusive)
        assertEquals(8, rectangle.bottomExclusive)
    }

    @Test
    fun `is empty when width or height are not positive`() {
        assertTrue(Rectangle(0, 0, 0, 1).isEmpty)
        assertTrue(Rectangle(0, 0, 1, 0).isEmpty)
        assertTrue(Rectangle(0, 0, -1, 1).isEmpty)
        assertFalse(Rectangle(0, 0, 1, 1).isEmpty)
    }
}
