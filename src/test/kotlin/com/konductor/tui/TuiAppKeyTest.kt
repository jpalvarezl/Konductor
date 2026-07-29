package com.konductor.tui

import com.googlecode.lanterna.input.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiAppKeyTest {
    @Test
    fun slashOpensOnlyForEmptyComposer() {
        assertTrue(shouldOpenCommandPalette('/', ctrlDown = false, composerEmpty = true))
        assertFalse(shouldOpenCommandPalette('/', ctrlDown = false, composerEmpty = false))
    }

    @Test
    fun ctrlKOpensWithComposerContent() {
        assertTrue(shouldOpenCommandPalette('k', ctrlDown = true, composerEmpty = false))
        assertTrue(shouldOpenCommandPalette('K', ctrlDown = true, composerEmpty = true))
        assertFalse(shouldOpenCommandPalette('k', ctrlDown = false, composerEmpty = true))
    }

    @Test
    fun shortcutsStayInertDuringWork() {
        assertFalse(shouldOpenCommandPalette('/', false, true, inputAvailable = false))
        assertFalse(shouldOpenCommandPalette('k', true, true, inputAvailable = false))
    }

    @Test
    fun consumesModifiedEnterSuffix() {
        val keys = ArrayDeque("13;2u".map { KeyStroke(it, false, false) })
        val deferred = mutableListOf<KeyStroke>()

        val keycode = consumeCsiuSuffix({ keys.removeFirstOrNull() }, deferred::add)

        assertEquals(CSI_U_ENTER_KEYCODE, keycode)
        assertTrue(keys.isEmpty())
        assertTrue(deferred.isEmpty())
    }
}
