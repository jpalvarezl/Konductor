package com.konductor.tui.component

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import com.konductor.core.AppState
import com.konductor.core.CommandPaletteItem
import com.konductor.core.CommandPaletteMode
import com.konductor.core.CommandPaletteState
import com.konductor.tui.TerminalCanvas
import com.konductor.tui.layout.Rectangle
import com.konductor.tui.style.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandPaletteViewTest {
    @Test
    fun rendersWithinSmallBounds() {
        renderAt(1, 1)
        renderAt(8, 4)
    }

    @Test
    fun placesQueryCursorInsideOverlay() {
        val state = paletteState()
        val view = CommandPaletteView(Theme())
        val bounds = Rectangle(0, 0, 40, 10)

        val cursor = assertNotNull(view.cursorPosition(bounds, state))

        assertTrue(cursor.column in bounds.left until bounds.rightExclusive)
        assertTrue(cursor.row in bounds.top until bounds.bottomExclusive)
    }

    @Test
    fun rendersLongQuerySuffix() {
        val terminal = DefaultVirtualTerminal(TerminalSize(10, 5))
        val screen = TerminalScreen(terminal)
        val state = paletteState().also { it.commandPalette?.query?.insert("abcdefghijklmnopqrst") }
        try {
            screen.startScreen()
            CommandPaletteView(Theme()).render(
                TerminalCanvas(screen),
                Rectangle(0, 0, 10, 5),
                state,
            )
            screen.refresh()

            val suffix = (2..8).map { terminal.getCharacter(it, 1).character }.joinToString("")
            assertEquals("nopqrst", suffix)
        } finally {
            screen.stopScreen()
        }
    }

    private fun renderAt(width: Int, height: Int) {
        val terminal = DefaultVirtualTerminal(TerminalSize(width, height))
        val screen = TerminalScreen(terminal)
        try {
            screen.startScreen()
            CommandPaletteView(Theme()).render(
                TerminalCanvas(screen),
                Rectangle(0, 0, width, height),
                paletteState(),
            )
            screen.refresh()
        } finally {
            screen.stopScreen()
        }
    }

    private fun paletteState(): AppState = AppState().also { state ->
        state.commandPalette = CommandPaletteState(
            mode = CommandPaletteMode.Commands,
            title = "Commands",
            allItems = listOf(
                CommandPaletteItem(
                    id = "/model",
                    label = "/model",
                    usage = "/model [list | <deployment>]",
                    description = "Switch model",
                    insertionText = "/model ",
                ),
            ),
        )
    }
}
