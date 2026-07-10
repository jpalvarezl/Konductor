package com.konductor.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppStringsTest {
    @Test
    fun `loads a localized bundle and falls back to the English base`() {
        val strings = AppStrings.forLocale(Locale.FRENCH)

        assertEquals("Konducteur", strings.emptyStateTitle)
        assertEquals("Tour annulé.", strings.turnCancelled())
        assertEquals("you", strings.roleLabel(com.konductor.core.MessageRole.User))
    }

    @Test
    fun `formats messages with the selected locale`() {
        val strings = AppStrings.forLocale(Locale.ENGLISH)

        assertEquals(
            "Resumed session abc12345 (12 entries).",
            strings.resumedSession("abc12345", 12),
        )
    }

    @Test
    fun `resolves configured BCP 47 locale tags`() {
        val strings = AppStrings.load(env = { if (it == AppStrings.ENV_LOCALE) "fr-CA" else null })

        assertEquals("fr", strings.locale.language)
        assertEquals("CA", strings.locale.country)
    }

    @Test
    fun `accepts valid private use locale tags`() {
        val strings = AppStrings.load(env = { if (it == AppStrings.ENV_LOCALE) "x-private" else null })

        assertEquals("x-private", strings.locale.toLanguageTag())
    }

    @Test
    fun `an unsupported requested locale falls directly to the English root`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRENCH)

            assertEquals("Konductor", AppStrings.forLocale(Locale.GERMAN).emptyStateTitle)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `rejects an invalid configured locale`() {
        assertFailsWith<LocalizationException> {
            AppStrings.load(env = { if (it == AppStrings.ENV_LOCALE) "en--US" else null })
        }
    }

    @Test
    fun `the English bundle covers every catalog accessor`() {
        val strings = AppStrings.english()
        val values = listOf(
            strings.welcomeMessage,
            strings.emptyStateTitle,
            strings.emptyStateSubtitle,
            strings.inputEmptyHint,
            strings.statusWorking,
            strings.statusHint,
            strings.persistedAgentsPromptOnly,
            strings.compactNothing,
            strings.compactedRecentTurns,
            strings.compactedForContext,
            strings.nameUsage,
            strings.noTokensYet,
            strings.noSavedSessions,
            strings.unnamedSession,
            strings.inMemorySession,
            strings.agentUseUsage,
            strings.agentCreateUsage,
            strings.noPersistedAgents,
            strings.ephemeralAgent,
            strings.unknownError,
            strings.cliToolSelectionConflict,
            strings.cliNoSessionConflict,
            strings.cliContinueConflict,
            strings.cliAcpSessionFlags,
            strings.cliToolsPromptOnly,
            strings.cliUsageHint,
            strings.configurationHint,
            strings.toolPathPlaceholder,
            strings.toolPatternPlaceholder,
            strings.toolCommandPlaceholder,
            strings.toolDone,
            strings.toolFailed,
            strings.toolTruncated,
            strings.statusUnavailable,
            strings.roleLabel(com.konductor.core.MessageRole.User),
            strings.roleLabel(com.konductor.core.MessageRole.Assistant),
            strings.roleLabel(com.konductor.core.MessageRole.System),
            strings.resumedSession("abc12345", 2),
            strings.compactedTranscript("summary"),
            strings.turnCancelled(),
            strings.inputScrolledHint(1, 2, 3),
            strings.inputCharsHint(3),
            strings.statusAgent("agent"),
            strings.statusScrolled(2),
            strings.statusUsageEmpty("128K"),
            strings.statusUsage(10, 7, 3, "128K", "<1%"),
            strings.compactThousands(128),
            strings.compactMillions(1),
            strings.markdownCodeLabel(""),
            strings.markdownCodeLabel("kotlin"),
            strings.compactFailed("reason"),
            strings.newSession("abc12345"),
            strings.renamedSession("name"),
            strings.tokenCount(10),
            strings.sessionSummary("name", "abc12345", 2, "10 tokens", "file"),
            strings.activeModel("model"),
            strings.modelFixedByAgent("agent"),
            strings.modelSwitched("old", "new"),
            strings.modelSwitchFailed("reason"),
            strings.savedSessionLine(1, "abc12345", "name", 2, "now"),
            strings.savedSessionsHeader("item"),
            strings.noSuchSession("missing"),
            strings.resumeFailed("reason"),
            strings.agentUnknownSubcommand("unknown"),
            strings.agentFailed("reason"),
            strings.agentUnavailable("agent"),
            strings.activeAgent("agent"),
            strings.persistedAgentItem("* ", "agent"),
            strings.persistedAgents("  * agent"),
            strings.switchedAgent("agent"),
            strings.createdAgent("agent", "1"),
            strings.cliHelp("read"),
            strings.cliUnknownOption("--bad"),
            strings.cliUnexpectedArgument("bad"),
            strings.cliInformationalConflict("--help"),
            strings.cliMissingValue("--model"),
            strings.cliUnknownAgentKind("bad"),
            strings.cliInvalidResumeId("bad"),
            strings.cliToolListRequired("--tools"),
            strings.cliUnknownTools("--tools", "bad", "read"),
            strings.cliError("bad"),
            strings.localeError("bad"),
            strings.configurationError("bad"),
            strings.fatalError("bad"),
            strings.retryingProvider("HTTP 429", 1, 3, 250),
            strings.toolRead("read", "file", ":1-2"),
            strings.toolWithArgument("ls", "."),
            strings.toolWithLocation("grep", "\"x\"", " in src"),
            strings.toolInPath("src"),
            strings.toolFallback("custom", "{}"),
            strings.toolCallFailed("read file", "reason"),
            strings.toolCount("read file", 2, strings.toolLinesUnit()),
            strings.toolDetail("bash cmd", "done"),
            strings.toolFallbackResult("custom {}", "done"),
            strings.toolWrite("write", "file", "10 bytes"),
            strings.toolEdit("edit", "file"),
            strings.toolUnknown("custom", "done", "detail"),
            strings.toolLinesUnit(),
            strings.toolEntriesUnit(),
            strings.toolMatchesUnit(),
        )

        assertTrue(values.all(String::isNotBlank))
    }
}
