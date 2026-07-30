package com.konductor.tui

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.TerminalFactory
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import com.konductor.config.WorkspaceTrustChoice
import com.konductor.config.WorkspaceTrustCoordinator
import com.konductor.config.WorkspaceTrustOutcome
import com.konductor.config.WorkspaceTrustStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceTrustPromptTest {
    @Test
    fun `normal prompt exposes four localized choices and defaults session-only untrusted`(@TempDir root: Path) {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val config = Files.createDirectory(root.resolve("config"))
        val required = assertIs<WorkspaceTrustOutcome.ChoiceRequired>(
            WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace)).resolve(gatedProjectSourcePresent = true),
        )

        val model = WorkspaceTrustPrompt().model(required)

        assertEquals(4, model.options.size)
        assertEquals(3, model.defaultIndex)
        assertEquals(
            WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.DoNotTrustForSession),
            model.options[model.defaultIndex].result,
        )
        assertEquals(
            WorkspaceTrustChoice.entries,
            model.options.map { (it.result as WorkspaceTrustPromptResult.Choice).choice },
        )
    }

    @Test
    fun `repair prompt offers only continue and quit and defaults quit`(@TempDir root: Path) {
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val config = Files.createDirectory(root.resolve("config"))
        Files.writeString(config.resolve(WorkspaceTrustStore.STORE_FILE_NAME), "{ malformed")
        val error = assertIs<WorkspaceTrustOutcome.Error>(
            WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace)).resolve(gatedProjectSourcePresent = false),
        )

        val model = WorkspaceTrustPrompt().model(error)

        assertEquals(2, model.options.size)
        assertEquals(WorkspaceTrustPromptResult.ContinueUntrusted, model.options[0].result)
        assertEquals(WorkspaceTrustPromptResult.Quit, model.options[1].result)
        assertEquals(1, model.defaultIndex)
        assertEquals(WorkspaceTrustPromptResult.Quit, model.cancelResult)
    }

    @Test
    fun `tiny terminal clips long repair diagnostic and keeps choices in bounds`(@TempDir root: Path) {
        val error = repairOutcome(root, "very long repair diagnostic ".repeat(40))
        val prompt = WorkspaceTrustPrompt()
        val model = prompt.model(error)
        val terminal = DefaultVirtualTerminal(TerminalSize(9, 3))
        val screen = TerminalScreen(terminal)

        screen.startScreen()
        try {
            prompt.render(screen, model, model.defaultIndex)

            assertEquals('>', terminal.getCharacter(0, 2).character)
            assertTrue(terminal.getCharacter(0, 2).isReversed)
            assertEquals(3, terminal.terminalSize.rows)
        } finally {
            screen.stopScreen()
            terminal.close()
        }
    }

    @Test
    fun `one-cell terminal scrolls options to selection without drawing outside canvas`() {
        val model = WorkspaceTrustPromptModel(
            title = "Trust",
            body = "body ".repeat(20),
            options = (0..3).map { WorkspaceTrustPromptOption("option $it", WorkspaceTrustPromptResult.Quit) },
            defaultIndex = 3,
            cancelResult = WorkspaceTrustPromptResult.Quit,
        )
        val prompt = WorkspaceTrustPrompt()
        val terminal = DefaultVirtualTerminal(TerminalSize(1, 1))
        val screen = TerminalScreen(terminal)

        screen.startScreen()
        try {
            prompt.render(screen, model, selected = 3)

            assertEquals('>', terminal.getCharacter(0, 0).character)
            assertTrue(terminal.getCharacter(0, 0).isReversed)
        } finally {
            screen.stopScreen()
            terminal.close()
        }
    }

    @Test
    fun `queued navigation and enter select the previous choice`(@TempDir root: Path) {
        val outcome = choiceOutcome(root)
        val terminal = TrackingVirtualTerminal(TerminalSize(24, 6)).apply {
            addInput(KeyStroke(KeyType.ArrowUp))
            addInput(KeyStroke(KeyType.Enter))
        }

        val result = WorkspaceTrustPrompt(terminalFactory = TerminalFactory { terminal }).prompt(outcome)

        assertEquals(
            WorkspaceTrustPromptResult.Choice(WorkspaceTrustChoice.DoNotTrust),
            result,
        )
        assertTrue(terminal.exitedPrivateMode)
        assertTrue(terminal.closed)
    }

    @Test
    fun `escape returns conservative repair result and restores terminal`(@TempDir root: Path) {
        val terminal = TrackingVirtualTerminal(TerminalSize(12, 3)).apply {
            addInput(KeyStroke(KeyType.Escape))
        }

        val result = WorkspaceTrustPrompt(terminalFactory = TerminalFactory { terminal })
            .prompt(repairOutcome(root, "repair me"))

        assertEquals(WorkspaceTrustPromptResult.Quit, result)
        assertTrue(terminal.exitedPrivateMode)
        assertTrue(terminal.closed)
    }

    private fun choiceOutcome(root: Path): WorkspaceTrustOutcome.ChoiceRequired {
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val config = Files.createDirectories(root.resolve("config"))
        return assertIs(
            WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace))
                .resolve(gatedProjectSourcePresent = true),
        )
    }

    private fun repairOutcome(root: Path, diagnostic: String): WorkspaceTrustOutcome.Error {
        val workspace = Files.createDirectories(root.resolve("workspace"))
        val config = Files.createDirectories(root.resolve("config"))
        Files.writeString(config.resolve(WorkspaceTrustStore.STORE_FILE_NAME), "{ malformed $diagnostic")
        return assertIs(
            WorkspaceTrustCoordinator(WorkspaceTrustStore(config, workspace))
                .resolve(gatedProjectSourcePresent = false),
        )
    }
}

private class TrackingVirtualTerminal(size: TerminalSize) : DefaultVirtualTerminal(size) {
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
