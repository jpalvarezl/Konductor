package com.konductor.tui.palette

import com.konductor.conversation.CommandAction
import com.konductor.conversation.CommandAvailability
import com.konductor.conversation.CommandAvailabilityProvider
import com.konductor.conversation.CommandDescriptor
import com.konductor.conversation.CommandInvocation
import com.konductor.conversation.CommandOption
import com.konductor.conversation.CommandOptionProvider
import com.konductor.conversation.CommandRegistry
import com.konductor.conversation.TuiCommand
import com.konductor.core.AppState
import com.konductor.core.CommandPaletteMode
import com.konductor.core.CommandPaletteStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPaletteControllerTest {
    @Test
    fun filtersAndMovesSelection() {
        val state = AppState()
        val controller = controller("/compact", "/connections", "/model")
        controller.open(state)

        controller.handle(state, PaletteKey.Character('c'))
        controller.handle(state, PaletteKey.Character('o'))
        assertEquals(listOf("/compact", "/connections"), state.commandPalette?.items?.map { it.id })

        controller.handle(state, PaletteKey.ArrowDown)
        assertEquals("/connections", state.commandPalette?.selectedItem?.id)
    }

    @Test
    fun insertsUsagePrefixWithoutDispatch() {
        val state = AppState().also { it.input.insert("draft") }
        val registry = CommandRegistry(listOf(MockTuiCommand("/name", insertionPrefix = "/name ")))
        val controller = CommandPaletteController(registry)
        controller.open(state)

        val action = controller.handle(state, PaletteKey.Enter)

        assertIs<PaletteAction.Closed>(action)
        assertEquals("/name ", state.input.text)
        assertNull(state.commandPalette)
    }

    @Test
    fun escapeClosesWithoutChangingComposer() {
        val state = AppState().also { it.input.insert("draft") }
        val controller = controller("/model")
        controller.open(state)

        assertIs<PaletteAction.Closed>(controller.handle(state, PaletteKey.Escape))
        assertEquals("draft", state.input.text)
        assertNull(state.commandPalette)
    }

    @Test
    fun disabledSelectionStaysOpen() {
        val state = AppState()
        val command = MockTuiCommand(
            "/model",
            availabilityProvider = CommandAvailabilityProvider {
                CommandAvailability.Disabled("fixed model")
            },
        )
        val controller = CommandPaletteController(CommandRegistry(listOf(command)))
        controller.open(state)

        val item = assertNotNull(state.commandPalette?.selectedItem)
        assertTrue(!item.enabled)
        assertEquals("fixed model", item.disabledReason)
        assertIs<PaletteAction.None>(controller.handle(state, PaletteKey.Enter))
        assertNotNull(state.commandPalette)
    }

    @Test
    fun loadsAndInsertsModelOption() {
        val state = AppState()
        val provider = MockOptionProvider()
        val command = MockTuiCommand("/model", insertionPrefix = "/model ")
        val controller = CommandPaletteController(
            CommandRegistry(listOf(command)),
            optionProviders = mapOf("/model" to provider),
        )
        controller.open(state)

        val load = assertIs<PaletteAction.LoadOptions>(controller.handle(state, PaletteKey.Enter))
        assertEquals(CommandPaletteMode.Options, state.commandPalette?.mode)
        assertIs<CommandPaletteStatus.Loading>(state.commandPalette?.status)
        assertTrue(state.messages.isEmpty())

        controller.completeOptions(
            state,
            load.requestId,
            Result.success(listOf(CommandOption("alpha"), CommandOption("beta"))),
        )
        controller.handle(state, PaletteKey.ArrowDown)
        controller.handle(state, PaletteKey.Enter)

        assertEquals("/model beta", state.input.text)
        assertNull(state.commandPalette)
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun showsEmptyAndErrorStates() {
        val state = AppState()
        val provider = MockOptionProvider()
        val controller = CommandPaletteController(
            CommandRegistry(listOf(MockTuiCommand("/model", insertionPrefix = "/model "))),
            optionProviders = mapOf("/model" to provider),
        )

        controller.open(state)
        val emptyLoad = assertIs<PaletteAction.LoadOptions>(controller.handle(state, PaletteKey.Enter))
        controller.completeOptions(state, emptyLoad.requestId, Result.success(emptyList()))
        assertIs<CommandPaletteStatus.Empty>(state.commandPalette?.status)

        controller.open(state)
        val failedLoad = assertIs<PaletteAction.LoadOptions>(controller.handle(state, PaletteKey.Enter))
        controller.completeOptions(state, failedLoad.requestId, Result.failure(IllegalStateException("secret")))
        val error = assertIs<CommandPaletteStatus.Error>(state.commandPalette?.status)
        assertTrue(!error.message.contains("secret"))
        assertTrue(state.messages.isEmpty())
    }

    @Test
    fun ignoresStaleOptionResults() {
        val state = AppState()
        val controller = CommandPaletteController(
            CommandRegistry(listOf(MockTuiCommand("/model"))),
            optionProviders = mapOf("/model" to MockOptionProvider()),
        )
        controller.open(state)
        val load = assertIs<PaletteAction.LoadOptions>(controller.handle(state, PaletteKey.Enter))
        controller.handle(state, PaletteKey.Escape)
        controller.open(state)
        val replacement = assertIs<PaletteAction.LoadOptions>(controller.handle(state, PaletteKey.Enter))

        controller.completeOptions(state, load.requestId, Result.success(listOf(CommandOption("late"))))
        assertIs<CommandPaletteStatus.Loading>(state.commandPalette?.status)

        controller.completeOptions(state, replacement.requestId, Result.success(listOf(CommandOption("current"))))
        assertEquals(listOf("current"), state.commandPalette?.items?.map { it.id })
    }

    private fun controller(vararg commands: String): CommandPaletteController = CommandPaletteController(
        CommandRegistry(commands.map(::MockTuiCommand)),
    )
}

private class MockTuiCommand(
    name: String,
    insertionPrefix: String = name,
    override val availabilityProvider: CommandAvailabilityProvider? = null,
) : TuiCommand {
    override val descriptor = CommandDescriptor(
        name = name,
        usage = "$name [value]",
        insertionPrefix = insertionPrefix,
        describe = { "Description for $name" },
    )

    override fun execute(invocation: CommandInvocation): CommandAction = error("Palette must not dispatch commands")
}

private class MockOptionProvider : CommandOptionProvider {
    override suspend fun loadOptions(): List<CommandOption> = emptyList()
}
