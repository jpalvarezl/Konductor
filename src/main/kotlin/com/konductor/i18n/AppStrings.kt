package com.konductor.i18n

import com.konductor.core.MessageRole
import java.text.MessageFormat
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.ResourceBundle

/**
 * User-facing application copy backed by a JVM [ResourceBundle].
 *
 * Callers use semantic methods rather than bundle keys so localization stays a presentation concern and
 * formatting can move to a richer implementation later without changing the TUI or conversation layers.
 */
class AppStrings private constructor(
    val locale: Locale,
    private val bundle: ResourceBundle,
) {
    val welcomeMessage: String get() = text("tui.welcome")
    val emptyStateTitle: String get() = text("tui.empty.title")
    val emptyStateSubtitle: String get() = text("tui.empty.subtitle")
    val inputEmptyHint: String get() = text("tui.input.hint.empty")
    val statusWorking: String get() = text("tui.status.working")
    val statusHint: String get() = text("tui.status.hint")
    val persistedAgentsPromptOnly: String get() = text("conversation.agent.promptOnly")
    val compactNothing: String get() = text("conversation.compact.nothing")
    val compactedRecentTurns: String get() = text("conversation.compact.completed")
    val compactedForContext: String get() = text("conversation.compact.auto")
    val nameUsage: String get() = text("conversation.name.usage")
    val noTokensYet: String get() = text("conversation.session.noTokens")
    val noSavedSessions: String get() = text("conversation.resume.none")
    val unnamedSession: String get() = text("conversation.session.unnamed")
    val inMemorySession: String get() = text("conversation.session.inMemory")
    val agentUseUsage: String get() = text("agent.use.usage")
    val agentCreateUsage: String get() = text("agent.create.usage")
    val noPersistedAgents: String get() = text("agent.list.none")
    val ephemeralAgent: String get() = text("agent.ephemeral")
    val unknownError: String get() = text("error.unknown")
    val cliToolSelectionConflict: String get() = text("cli.error.tools.conflict")
    val cliNoSessionConflict: String get() = text("cli.error.session.noSessionConflict")
    val cliContinueConflict: String get() = text("cli.error.session.continueConflict")
    val cliAcpSessionFlags: String get() = text("cli.error.session.acpOnly")
    val cliToolsPromptOnly: String get() = text("cli.error.tools.promptOnly")
    val cliUsageHint: String get() = text("cli.error.usageHint")
    val configurationHint: String get() = text("configuration.error.hint")
    val toolPathPlaceholder: String get() = text("tool.placeholder.path")
    val toolPatternPlaceholder: String get() = text("tool.placeholder.pattern")
    val toolCommandPlaceholder: String get() = text("tool.placeholder.command")
    val toolDone: String get() = text("tool.result.done")
    val toolFailed: String get() = text("tool.result.failed")
    val toolTruncated: String get() = text("tool.result.truncated")
    val statusUnavailable: String get() = text("tui.status.unavailable")

    fun roleLabel(role: MessageRole): String = text(
        when (role) {
            MessageRole.User -> "role.user"
            MessageRole.Assistant -> "role.assistant"
            MessageRole.System -> "role.system"
        },
    )

    fun resumedSession(id: String, entryCount: Int): String =
        text("tui.session.resumed", id, entryCount.toString())

    fun compactedTranscript(summary: String): String =
        text("tui.session.compacted", summary)

    fun turnCancelled(): String = text("tui.turn.cancelled")

    fun inputScrolledHint(line: Int, totalLines: Int, chars: Int): String =
        text("tui.input.hint.scrolled", line.toString(), totalLines.toString(), chars.toString())

    fun inputCharsHint(chars: Int): String =
        text("tui.input.hint.chars", chars.toString())

    fun statusAgent(name: String): String = text("tui.status.agent", name)

    fun statusScrolled(lines: Int): String = text("tui.status.scrolled", lines.toString())

    fun statusUsageEmpty(window: String): String = text("tui.status.usage.empty", window)

    fun statusUsage(
        totalTokens: Int,
        inputTokens: Int,
        outputTokens: Int,
        window: String,
        percent: String,
    ): String = text(
        "tui.status.usage",
        totalTokens.toString(),
        inputTokens.toString(),
        outputTokens.toString(),
        window,
        percent,
    )

    fun compactThousands(value: Int): String = text("tui.status.tokens.thousand", value.toString())

    fun compactMillions(value: Int): String = text("tui.status.tokens.million", value.toString())

    fun markdownCodeLabel(language: String): String =
        if (language.isEmpty()) text("tui.markdown.code") else text("tui.markdown.codeLanguage", language)

    fun compactFailed(reason: String): String = text("conversation.compact.failed", reason)

    fun newSession(id: String): String = text("conversation.new.completed", id)

    fun renamedSession(name: String): String = text("conversation.name.completed", name)

    fun tokenCount(tokens: Int): String = text("conversation.session.tokens", tokens.toString())

    fun sessionSummary(
        name: String,
        id: String,
        entries: Int,
        tokens: String,
        location: String,
    ): String = text("conversation.session.summary", name, id, entries.toString(), tokens, location)

    fun activeModel(model: String): String = text("conversation.model.active", model)

    fun modelFixedByAgent(agent: String): String = text("conversation.model.agentFixed", agent)

    fun modelSwitched(previous: String, current: String): String =
        text("conversation.model.switched", previous, current)

    fun modelSwitchFailed(reason: String): String = text("conversation.model.failed", reason)

    fun savedSessionLine(index: Int, id: String, name: String, entries: Int, updatedAt: String): String =
        text("conversation.resume.item", index.toString(), id, name, entries.toString(), updatedAt)

    fun savedSessionsHeader(items: String): String = text("conversation.resume.header", items)

    fun noSuchSession(input: String): String = text("conversation.resume.notFound", input)

    fun resumeFailed(reason: String): String = text("conversation.resume.failed", reason)

    fun agentUnknownSubcommand(args: String): String = text("agent.unknownSubcommand", args)

    fun agentFailed(reason: String): String = text("agent.failed", reason)

    fun agentUnavailable(name: String): String = text("agent.unavailable", name)

    fun activeAgent(name: String): String = text("agent.active", name)

    fun persistedAgentItem(marker: String, name: String): String = text("agent.list.item", marker, name)

    fun persistedAgents(items: String): String = text("agent.list", items)

    fun switchedAgent(name: String): String = text("agent.switched", name)

    fun createdAgent(name: String, version: String): String = text("agent.created", name, version)

    fun cliHelp(availableTools: String): String = text("cli.help", availableTools)

    fun cliUnknownOption(option: String): String = text("cli.error.unknownOption", option)

    fun cliUnexpectedArgument(argument: String): String = text("cli.error.unexpectedArgument", argument)

    fun cliInformationalConflict(flag: String): String = text("cli.error.informationalConflict", flag)

    fun cliMissingValue(flag: String): String = text("cli.error.missingValue", flag)

    fun cliUnknownAgentKind(value: String): String = text("cli.error.agentKind", value)

    fun cliInvalidResumeId(value: String): String = text("cli.error.resumeId", value)

    fun cliToolListRequired(flag: String): String = text("cli.error.tools.listRequired", flag)

    fun cliUnknownTools(flag: String, unknown: String, available: String): String =
        text("cli.error.tools.unknown", flag, unknown, available)

    fun cliError(message: String): String = text("cli.error.prefix", message)

    fun localeError(message: String): String = text("locale.error.prefix", message)

    fun configurationError(message: String): String = text("configuration.error.prefix", message)

    fun fatalError(message: String): String = text("fatal.error.prefix", message)

    fun retryingProvider(reason: String?, retryAttempt: Int, maxRetries: Int, delayMs: Long): String =
        text(
            "provider.retrying",
            reason ?: unknownError,
            retryAttempt.toString(),
            maxRetries.toString(),
            delayMs.toString(),
        )

    fun toolRead(name: String, path: String, range: String): String = text("tool.call.read", name, path, range)

    fun toolWithArgument(name: String, argument: String): String = text("tool.call.argument", name, argument)

    fun toolWithLocation(name: String, argument: String, location: String): String =
        text("tool.call.location", name, argument, location)

    fun toolInPath(path: String): String = text("tool.call.inPath", path)

    fun toolFallback(name: String, arguments: String): String = text("tool.call.fallback", name, arguments)

    fun toolCallFailed(summary: String, detail: String): String = text("tool.result.callFailed", summary, detail)

    fun toolCount(summary: String, count: Int, unit: String): String =
        text("tool.result.count", summary, count.toString(), unit)

    fun toolDetail(summary: String, detail: String): String = text("tool.result.detail", summary, detail)

    fun toolFallbackResult(summary: String, detail: String): String =
        text("tool.result.fallback", summary, detail)

    fun toolWrite(name: String, path: String, detail: String): String =
        text("tool.result.write", name, path, detail)

    fun toolEdit(name: String, path: String): String = text("tool.result.edit", name, path)

    fun toolUnknown(name: String, marker: String, detail: String): String =
        text("tool.result.unknown", name, marker, detail)

    fun toolLinesUnit(): String = text("tool.unit.lines")

    fun toolEntriesUnit(): String = text("tool.unit.entries")

    fun toolMatchesUnit(): String = text("tool.unit.matches")

    private fun text(key: String, vararg arguments: Any): String {
        val pattern = bundle.getString(key)
        return if (arguments.isEmpty()) pattern else MessageFormat(pattern, locale).format(arguments)
    }

    companion object {
        const val ENV_LOCALE: String = "KONDUCTOR_LOCALE"
        private const val BUNDLE_NAME: String = "com.konductor.i18n.messages"
        private val bundleControl = object : ResourceBundle.Control() {
            override fun getFallbackLocale(baseName: String, locale: Locale): Locale? = null
        }

        private val english: AppStrings by lazy { forLocale(Locale.ENGLISH) }

        fun english(): AppStrings = english

        fun load(
            env: (String) -> String? = System::getenv,
            defaultLocale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
        ): AppStrings {
            val configured = env(ENV_LOCALE)?.trim()?.ifBlank { null }
            val locale = configured?.let(::parseLocale) ?: defaultLocale
            return forLocale(locale)
        }

        fun forLocale(locale: Locale): AppStrings =
            AppStrings(locale, ResourceBundle.getBundle(BUNDLE_NAME, locale, bundleControl))

        private fun parseLocale(raw: String): Locale {
            try {
                return Locale.Builder().setLanguageTag(raw).build()
            } catch (error: IllformedLocaleException) {
                throw LocalizationException(
                    "Invalid $ENV_LOCALE '$raw'; expected a BCP-47 language tag such as en, es, or fr-CA.",
                    error,
                )
            }
        }
    }
}

class LocalizationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
