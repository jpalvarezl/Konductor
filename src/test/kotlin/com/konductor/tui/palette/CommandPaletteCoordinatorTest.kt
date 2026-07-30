package com.konductor.tui.palette

import com.konductor.conversation.CommandAction
import com.konductor.conversation.CommandDescriptor
import com.konductor.conversation.CommandInvocation
import com.konductor.conversation.CommandOption
import com.konductor.conversation.CommandOptionProvider
import com.konductor.conversation.CommandRegistry
import com.konductor.conversation.TuiCommand
import com.konductor.core.AppState
import com.konductor.core.CommandPaletteStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPaletteCoordinatorTest {
    @Test
    fun `typed absolute path sequence is restored after escape`() = runBlocking {
        val fixture = fixture(this)

        assertTrue(fixture.coordinator.tryOpen('/', ctrlDown = false))
        "etc/hosts".forEach { fixture.coordinator.handle(PaletteKey.Character(it)) }
        fixture.coordinator.handle(PaletteKey.Escape)

        assertEquals("/etc/hosts", fixture.state.input.text)
        assertNull(fixture.state.commandPalette)
    }

    @Test
    fun `pasted oversized absolute path key burst preserves every character`() = runBlocking {
        val fixture = fixture(this)
        val expected = "/" + "segment/".repeat(FuzzyMatcher.MAX_QUERY_CODE_POINTS)
        val pasted = ArrayDeque(expected.toList())

        assertTrue(fixture.coordinator.tryOpen(pasted.removeFirst(), ctrlDown = false))
        while (pasted.isNotEmpty()) fixture.coordinator.handle(PaletteKey.Character(pasted.removeFirst()))
        fixture.coordinator.handle(PaletteKey.Escape)

        assertEquals(expected, fixture.state.input.text)
    }

    @Test
    fun `ctrl k replacement preserves a slash origin cancellation draft`() = runBlocking {
        val fixture = fixture(this)
        assertTrue(fixture.coordinator.tryOpen('/', ctrlDown = false))
        "etc/hosts".forEach { fixture.coordinator.handle(PaletteKey.Character(it)) }

        assertTrue(fixture.coordinator.tryOpen('k', ctrlDown = true))
        fixture.coordinator.handle(PaletteKey.Escape)

        assertEquals("/etc/hosts", fixture.state.input.text)
    }

    @Test
    fun `production coordinator launches and completes option loading`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<List<CommandOption>>()
        val fixture = fixture(this, CommandOptionProvider {
            started.complete(Unit)
            result.await()
        })

        fixture.coordinator.open(PaletteOpenOrigin.Shortcut)
        fixture.coordinator.handle(PaletteKey.Enter)
        started.await()
        val load = assertNotNull(fixture.coordinator.activeLoad)

        result.complete(listOf(CommandOption("alpha")))
        load.join()

        assertEquals(listOf("alpha"), fixture.state.commandPalette?.items?.map { it.id })
        assertIs<CommandPaletteStatus.Ready>(fixture.state.commandPalette?.status)
        Unit
    }

    @Test
    fun `escape cancels loading and restores the slash draft`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val fixture = fixture(this, CommandOptionProvider {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        })

        assertTrue(fixture.coordinator.tryOpen('/', ctrlDown = false))
        "model".forEach { fixture.coordinator.handle(PaletteKey.Character(it)) }
        fixture.coordinator.handle(PaletteKey.Enter)
        started.await()
        val load = assertNotNull(fixture.coordinator.activeLoad)

        fixture.coordinator.handle(PaletteKey.Escape)
        cancelled.await()
        load.join()

        assertEquals("/model", fixture.state.input.text)
        assertNull(fixture.state.commandPalette)
        assertNull(fixture.coordinator.activeLoad)
    }

    @Test
    fun `ctrl k cancels and replaces generation while stale non cooperative completion is ignored`() = runBlocking {
        val provider = ReplacingProvider()
        val fixture = fixture(this, provider)

        fixture.coordinator.open(PaletteOpenOrigin.Shortcut)
        fixture.coordinator.handle(PaletteKey.Enter)
        provider.firstStarted.await()
        val firstJob = assertNotNull(fixture.coordinator.activeLoad)
        val firstGeneration = assertNotNull(fixture.state.commandPalette).requestId

        assertTrue(fixture.coordinator.tryOpen('k', ctrlDown = true))
        provider.firstCancelled.await()
        fixture.coordinator.handle(PaletteKey.Enter)
        provider.secondStarted.await()
        val secondJob = assertNotNull(fixture.coordinator.activeLoad)
        val secondGeneration = assertNotNull(fixture.state.commandPalette).requestId

        assertNotEquals(firstGeneration, secondGeneration)
        firstJob.join()
        assertIs<CommandPaletteStatus.Loading>(fixture.state.commandPalette?.status)

        provider.secondResult.complete(listOf(CommandOption("current")))
        secondJob.join()
        assertEquals(listOf("current"), fixture.state.commandPalette?.items?.map { it.id })
    }

    @Test
    fun `reopening an option catalog loads a fresh snapshot`() = runBlocking {
        val calls = AtomicInteger()
        val fixture = fixture(this, CommandOptionProvider {
            listOf(CommandOption("snapshot-${calls.incrementAndGet()}"))
        })

        fixture.coordinator.open(PaletteOpenOrigin.Shortcut)
        fixture.coordinator.handle(PaletteKey.Enter)
        assertNotNull(fixture.coordinator.activeLoad).join()
        assertEquals(listOf("snapshot-1"), fixture.state.commandPalette?.items?.map { it.id })

        fixture.coordinator.handle(PaletteKey.Escape)
        fixture.coordinator.open(PaletteOpenOrigin.Shortcut)
        fixture.coordinator.handle(PaletteKey.Enter)
        assertNotNull(fixture.coordinator.activeLoad).join()

        assertEquals(2, calls.get())
        assertEquals(listOf("snapshot-2"), fixture.state.commandPalette?.items?.map { it.id })
    }

    @Test
    fun `enter is inert when layout exposes no item rows`() = runBlocking {
        val fixture = fixture(this, provider = null, insertionPrefix = "/model ")
        fixture.coordinator.open(PaletteOpenOrigin.Shortcut)

        fixture.coordinator.handle(PaletteKey.Enter, selectionVisible = false)

        assertNotNull(fixture.state.commandPalette)
        assertEquals("", fixture.state.input.text)

        fixture.coordinator.handle(PaletteKey.Enter, selectionVisible = true)
        assertNull(fixture.state.commandPalette)
        assertEquals("/model ", fixture.state.input.text)
    }

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        provider: CommandOptionProvider? = CommandOptionProvider { emptyList() },
        insertionPrefix: String = "/model ",
    ): Fixture {
        val state = AppState()
        val command = CoordinatorCommand(insertionPrefix)
        val source = provider?.let {
            mapOf(
                "/model" to PaletteOptionSource(
                    it,
                    insertionPrefix = "/model ",
                    title = "Models",
                    loadingMessage = "Loading",
                    emptyMessage = "Empty",
                    errorMessage = "Error",
                ),
            )
        }.orEmpty()
        val controller = CommandPaletteController(CommandRegistry(listOf(command)), optionSources = source)
        return Fixture(state, CommandPaletteCoordinator(state, controller, scope))
    }

    private data class Fixture(
        val state: AppState,
        val coordinator: CommandPaletteCoordinator,
    )
}

private class CoordinatorCommand(insertionPrefix: String) : TuiCommand {
    override val descriptor = CommandDescriptor(
        name = "/model",
        usage = "/model [deployment]",
        insertionPrefix = insertionPrefix,
        describe = { "Switch model" },
    )

    override fun execute(invocation: CommandInvocation): CommandAction = error("Palette must not dispatch")
}

/**
 * First call deliberately swallows cancellation and returns stale data; the second remains independently controlled.
 */
private class ReplacingProvider : CommandOptionProvider {
    val firstStarted = CompletableDeferred<Unit>()
    val firstCancelled = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()
    val secondResult = CompletableDeferred<List<CommandOption>>()
    private val calls = AtomicInteger()

    override suspend fun loadOptions(): List<CommandOption> = when (calls.incrementAndGet()) {
        1 -> {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: kotlinx.coroutines.CancellationException) {
                firstCancelled.complete(Unit)
                listOf(CommandOption("stale"))
            }
        }
        2 -> {
            secondStarted.complete(Unit)
            secondResult.await()
        }
        else -> error("Unexpected load")
    }
}
