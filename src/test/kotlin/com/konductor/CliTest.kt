package com.konductor

import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentKind
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliTest {
    @Test
    fun `help and version are informational actions`() {
        assertEquals(CliAction.Help, parseCliArgs(arrayOf("--help")).action)
        assertEquals(CliAction.Help, parseCliArgs(arrayOf("-h")).action)
        assertEquals(CliAction.Version, parseCliArgs(arrayOf("--version")).action)
        assertEquals(CliAction.Version, parseCliArgs(arrayOf("-V")).action)
        assertTrue(KonductorCli.help.contains("--no-tools"))
        assertTrue(KonductorCli.version.isNotBlank())
    }

    @Test
    fun `acp positional and flag forms remain compatible`() {
        assertEquals(CliMode.Acp, parseCliArgs(arrayOf("acp")).mode)
        assertEquals(CliMode.Acp, parseCliArgs(arrayOf("--acp")).mode)
        assertEquals(
            CliMode.Acp,
            parseCliArgs(arrayOf("--model", "gpt-5", "acp", "--no-tools")).mode,
        )
    }

    @Test
    fun `provider overrides are parsed`() {
        val options = parseCliArgs(arrayOf("--agent-kind", "HOSTED", "--model", "deployment"))

        assertEquals(AgentKind.Hosted, options.agentKind)
        assertEquals("deployment", options.model)
    }

    @Test
    fun `unknown options and positional arguments fail`() {
        assertCliError("Unknown option") { parseCliArgs(arrayOf("--bogus")) }
        assertCliError("Unexpected positional argument") { parseCliArgs(arrayOf("bogus")) }
    }

    @Test
    fun `parser errors use the selected catalog without localizing option names`() {
        val strings = AppStrings.forLocale(Locale.FRENCH)
        val error = assertFailsWith<CliException> { parseCliArgs(arrayOf("--bogus"), strings) }

        assertEquals("Option inconnue '--bogus'.", error.message)
    }

    @Test
    fun `value options reject missing values and following flags`() {
        assertCliError("Missing value after --model") { parseCliArgs(arrayOf("--model")) }
        assertCliError("Missing value after --resume") { parseCliArgs(arrayOf("--resume", "--no-session")) }
        assertCliError("Missing value after --tools") { parseCliArgs(arrayOf("--tools", "--no-tools")) }
    }

    @Test
    fun `session options reject incompatible combinations`() {
        assertCliError("--no-session") { parseCliArgs(arrayOf("--no-session", "--continue")) }
        assertCliError("--no-session") {
            parseCliArgs(arrayOf("--no-session", "--resume", "123e4567-e89b-12d3-a456-426614174000"))
        }
        assertCliError("--continue") {
            parseCliArgs(arrayOf("--continue", "--resume", "123e4567-e89b-12d3-a456-426614174000"))
        }
        assertCliError("apply only to TUI sessions") { parseCliArgs(arrayOf("acp", "--name", "ignored")) }
        assertCliError("Invalid --resume") { parseCliArgs(arrayOf("--resume", "not-a-uuid")) }
    }

    @Test
    fun `tools selects an exact allow list`() {
        val options = parseCliArgs(arrayOf("--tools", "read, grep"))

        assertIs<ToolSelection.Only>(options.toolSelection)
        assertEquals(setOf("read", "grep"), options.resolveToolAllow(setOf("ls")))
    }

    @Test
    fun `exclude tools subtracts from settings or all builtins`() {
        val options = parseCliArgs(arrayOf("--exclude-tools", "bash,write,edit"))

        assertEquals(setOf("read", "ls"), options.resolveToolAllow(setOf("read", "ls", "bash")))
        assertEquals(setOf("read", "ls", "find", "grep"), options.resolveToolAllow(null))
    }

    @Test
    fun `no tools resolves to an empty allow list`() {
        assertEquals(emptySet(), parseCliArgs(arrayOf("--no-tools")).resolveToolAllow(null))
    }

    @Test
    fun `tool selectors reject conflicts malformed lists and unknown tools`() {
        assertCliError("mutually exclusive") { parseCliArgs(arrayOf("--tools", "read", "--no-tools")) }
        assertCliError("comma-separated") { parseCliArgs(arrayOf("--tools", "read,,grep")) }
        assertCliError("Unknown tool") { parseCliArgs(arrayOf("--exclude-tools", "delete-everything")) }
    }

    private fun assertCliError(message: String, block: () -> Unit) {
        val error = assertFailsWith<CliException>(block = block)
        assertTrue(error.message.orEmpty().contains(message), "Expected '${error.message}' to contain '$message'")
    }
}
