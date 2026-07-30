package com.konductor.conversation

import com.konductor.i18n.AppStrings
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CommandRegistryTest {
    @Test
    fun parsesRawRemainder() {
        val raw = "  /Compact \tKeep THIS  "
        val command = MockTuiCommand(descriptor("/compact"))

        CommandRegistry(listOf(command)).dispatch(raw)

        assertEquals("/Compact", command.lastInvocation?.name)
        assertEquals(" \tKeep THIS  ", command.lastInvocation?.rawArguments)
        assertEquals(raw, command.lastInvocation?.rawInput)
        assertNull(CommandInvocation.parse("hello /compact"))
    }

    @Test
    fun preservesDescriptorOrder() {
        val first = MockTuiCommand(descriptor("/first"))
        val second = MockTuiCommand(descriptor("/second"))

        val registry = CommandRegistry(listOf(first, second))

        assertEquals(listOf("/first", "/second"), registry.descriptors.map(CommandDescriptor::name))
    }

    @Test
    fun findsNamesAndAliasesIgnoringCase() {
        val quit = MockTuiCommand(descriptor("/quit", setOf("/exit")))
        val registry = CommandRegistry(listOf(quit))

        assertEquals(quit, registry.find("/QUIT"))
        assertEquals(quit, registry.find("/Exit"))
        assertIs<CommandAction.Immediate>(registry.dispatch("/eXiT"))
    }

    @Test
    fun rejectsCaseInsensitiveCollisions() {
        val first = MockTuiCommand(descriptor("/first", setOf("/shared")))
        val second = MockTuiCommand(descriptor("/SHARED"))

        assertFailsWith<IllegalArgumentException> {
            CommandRegistry(listOf(first, second))
        }
    }

    @Test
    fun evaluatesOptionalAvailability() {
        val enabled = MockTuiCommand(descriptor("/enabled"))
        val disabled = MockTuiCommand(
            descriptor("/disabled"),
            CommandAvailabilityProvider { CommandAvailability.Disabled("not available") },
        )
        val entries = CommandRegistry(listOf(enabled, disabled)).entries()

        assertIs<CommandAvailability.Enabled>(entries[0].availability)
        assertEquals("not available", assertIs<CommandAvailability.Disabled>(entries[1].availability).reason)
    }

    @Test
    fun localizesDescriptions() {
        val description = BuiltInCommandDescriptors.quit.describe(AppStrings.forLocale(Locale.FRENCH))

        assertEquals("Quitter Konductor.", description)
    }

    private fun descriptor(name: String, aliases: Set<String> = emptySet()) = CommandDescriptor(
        name = name,
        aliases = aliases,
        usage = name,
        describe = { "Description" },
    )
}

private class MockTuiCommand(
    override val descriptor: CommandDescriptor,
    override val availabilityProvider: CommandAvailabilityProvider? = null,
) : TuiCommand {
    var lastInvocation: CommandInvocation? = null
        private set

    override fun execute(invocation: CommandInvocation): CommandAction {
        lastInvocation = invocation
        return CommandAction.Immediate {}
    }
}
