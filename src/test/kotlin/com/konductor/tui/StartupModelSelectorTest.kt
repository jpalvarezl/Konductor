package com.konductor.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.TerminalFactory
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import com.konductor.conversation.CommandOption
import com.konductor.tui.palette.FuzzyMatcher
import com.konductor.tui.palette.PaletteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StartupModelSelectorTest {
    @Test
    fun `retains only the matcher candidate bound while tracking the complete catalog`() {
        val options = (0 until FuzzyMatcher.MAX_INSPECTED_CANDIDATES).map {
            CommandOption("model-$it")
        } + CommandOption("omitted-value", label = "needle-only")
        val controller = StartupModelSelectorController(options)

        assertEquals(FuzzyMatcher.MAX_INSPECTED_CANDIDATES, controller.state.retainedOptions.size)
        assertEquals(options.size, controller.state.totalOptionCount)
        assertTrue(controller.state.isTruncated)
        assertEquals(FuzzyMatcher.MAX_RETURNED_RESULTS, controller.state.filteredOptions.size)

        "needle".forEach { controller.handle(PaletteKey.Character(it)) }

        assertTrue(controller.state.filteredOptions.isEmpty())
        assertNull(controller.handle(PaletteKey.Enter))
    }

    @Test
    fun `filtering and navigation preserve deterministic source order and return exact values`() {
        val controller = StartupModelSelectorController(
            listOf(
                CommandOption(value = "deployment/a", label = "Alpha", detail = "shared model"),
                CommandOption(value = "deployment/b", label = "Beta", detail = "shared model"),
                CommandOption(value = "deployment/c", label = "Gamma", detail = "shared model"),
            ),
        )

        "shared".forEach { controller.handle(PaletteKey.Character(it)) }
        repeat(4) { controller.handle(PaletteKey.ArrowDown) }

        assertEquals(
            listOf("deployment/a", "deployment/b", "deployment/c"),
            controller.state.filteredOptions.map { it.value },
        )
        assertEquals(2, controller.state.selectedIndex)
        assertEquals(
            StartupModelSelectorResult.Selected("deployment/c"),
            controller.handle(PaletteKey.Enter),
        )
        assertEquals(StartupModelSelectorResult.Cancelled, controller.handle(PaletteKey.Escape))
    }

    @Test
    fun `runner filters and selects the exact option value then restores the terminal`() {
        val terminal = TrackingModelSelectorTerminal(TerminalSize(80, 8)).apply {
            addInput(KeyStroke('b', false, false))
            addInput(KeyStroke(KeyType.Enter))
        }
        val selector = StartupModelSelector(terminalFactory = TerminalFactory { terminal })

        val result = selector.select(
            listOf(
                CommandOption(value = "deployment-a", label = "Alpha"),
                CommandOption(value = "exact deployment b", label = "Beta"),
            ),
        )

        assertEquals(StartupModelSelectorResult.Selected("exact deployment b"), result)
        assertTrue(terminal.exitedPrivateMode)
        assertTrue(terminal.closed)
    }

    @Test
    fun `escape cancels and restores the terminal`() {
        val terminal = TrackingModelSelectorTerminal(TerminalSize(20, 4)).apply {
            addInput(KeyStroke(KeyType.Escape))
        }

        val result = StartupModelSelector(terminalFactory = TerminalFactory { terminal })
            .select(listOf(CommandOption("a"), CommandOption("b")))

        assertIs<StartupModelSelectorResult.Cancelled>(result)
        assertTrue(terminal.exitedPrivateMode)
        assertTrue(terminal.closed)
    }

    @Test
    fun `truncation notice remains rendered after filtering`() {
        val options = (0..FuzzyMatcher.MAX_INSPECTED_CANDIDATES).map { CommandOption("model-$it") }
        val controller = StartupModelSelectorController(options)
        controller.handle(PaletteKey.Character('1'))
        val selector = StartupModelSelector()
        val terminal = DefaultVirtualTerminal(TerminalSize(140, 7))
        val screen = TerminalScreen(terminal)

        screen.startScreen()
        try {
            selector.render(screen, controller.state)
            val rendered = (0 until terminal.terminalSize.rows).joinToString("\n") { row ->
                (0 until terminal.terminalSize.columns)
                    .map { column -> terminal.getCharacter(column, row).character }
                    .joinToString("")
            }
            assertTrue(rendered.contains("Showing the first 2000 of 2001 deployments"))
        } finally {
            screen.stopScreen()
            terminal.close()
        }
    }
}

private class TrackingModelSelectorTerminal(size: TerminalSize) : DefaultVirtualTerminal(size) {
    var exitedPrivateMode = false
    var closed = false

    override fun exitPrivateMode() {
        exitedPrivateMode = true
        super.exitPrivateMode()
    }

    override fun close() {
        closed = true
        super.close()
    }
}
