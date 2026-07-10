package com.konductor

import com.konductor.provider.AgentKind
import com.konductor.tool.BuiltinTools
import com.konductor.i18n.AppStrings
import kotlin.uuid.Uuid

internal enum class CliAction {
    Run,
    Help,
    Version,
}

internal enum class CliMode {
    Tui,
    Acp,
}

internal sealed interface ToolSelection {
    data class Only(val names: Set<String>) : ToolSelection
    data class Exclude(val names: Set<String>) : ToolSelection
    data object None : ToolSelection
}

internal data class CliOptions(
    val action: CliAction = CliAction.Run,
    val mode: CliMode = CliMode.Tui,
    val agentKind: AgentKind? = null,
    val model: String? = null,
    val noSession: Boolean = false,
    val continueLatest: Boolean = false,
    val resumeId: String? = null,
    val name: String? = null,
    val toolSelection: ToolSelection? = null,
) {
    fun resolveToolAllow(configured: Set<String>?, allTools: Set<String> = BuiltinTools.names()): Set<String>? =
        when (val selection = toolSelection) {
            null -> configured
            is ToolSelection.Only -> selection.names
            is ToolSelection.Exclude -> (configured ?: allTools) - selection.names
            ToolSelection.None -> emptySet()
        }
}

internal class CliException(message: String) : RuntimeException(message)

internal fun parseCliArgs(
    args: Array<String>,
    strings: AppStrings = AppStrings.english(),
): CliOptions {
    var action = CliAction.Run
    var mode = CliMode.Tui
    var agentKind: AgentKind? = null
    var model: String? = null
    var noSession = false
    var continueLatest = false
    var resumeId: String? = null
    var name: String? = null
    var toolSelection: ToolSelection? = null
    var index = 0

    fun selectTools(selection: ToolSelection) {
        if (toolSelection != null) {
            throw CliException(strings.cliToolSelectionConflict)
        }
        toolSelection = selection
    }

    while (index < args.size) {
        when (val arg = args[index]) {
            "--help", "-h" -> {
                action = selectAction(action, CliAction.Help, arg, strings)
                index += 1
            }
            "--version", "-V" -> {
                action = selectAction(action, CliAction.Version, arg, strings)
                index += 1
            }
            "acp", "--acp" -> {
                mode = CliMode.Acp
                index += 1
            }
            "--agent-kind" -> {
                agentKind = parseAgentKindArgument(args.valueAfter(arg, index, strings), strings)
                index += 2
            }
            "--model" -> {
                model = args.valueAfter(arg, index, strings)
                index += 2
            }
            "--no-session" -> {
                noSession = true
                index += 1
            }
            "--continue", "-c" -> {
                continueLatest = true
                index += 1
            }
            "--resume", "-r" -> {
                resumeId = parseSessionIdArgument(args.valueAfter(arg, index, strings), strings)
                index += 2
            }
            "--name" -> {
                name = args.valueAfter(arg, index, strings)
                index += 2
            }
            "--tools" -> {
                selectTools(ToolSelection.Only(parseToolNames(args.valueAfter(arg, index, strings), arg, strings)))
                index += 2
            }
            "--exclude-tools" -> {
                selectTools(ToolSelection.Exclude(parseToolNames(args.valueAfter(arg, index, strings), arg, strings)))
                index += 2
            }
            "--no-tools" -> {
                selectTools(ToolSelection.None)
                index += 1
            }
            else -> throw CliException(
                if (arg.startsWith("-")) strings.cliUnknownOption(arg) else strings.cliUnexpectedArgument(arg),
            )
        }
    }

    validateSessionFlags(mode, noSession, continueLatest, resumeId, name, strings)
    return CliOptions(action, mode, agentKind, model, noSession, continueLatest, resumeId, name, toolSelection)
}

private fun selectAction(current: CliAction, requested: CliAction, flag: String, strings: AppStrings): CliAction {
    if (current != CliAction.Run && current != requested) {
        throw CliException(strings.cliInformationalConflict(flag))
    }
    return requested
}

private fun Array<String>.valueAfter(flag: String, index: Int, strings: AppStrings): String {
    val value = getOrNull(index + 1)
    if (value == null || value.startsWith("-")) throw CliException(strings.cliMissingValue(flag))
    return value
}

private fun parseAgentKindArgument(value: String, strings: AppStrings): AgentKind =
    AgentKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw CliException(strings.cliUnknownAgentKind(value))

private fun parseSessionIdArgument(value: String, strings: AppStrings): String =
    runCatching { Uuid.parse(value.trim()) }
        .fold(
            onSuccess = { value },
            onFailure = { throw CliException(strings.cliInvalidResumeId(value)) },
        )

private fun parseToolNames(raw: String, flag: String, strings: AppStrings): Set<String> {
    val parts = raw.split(",").map(String::trim)
    if (parts.any(String::isEmpty)) throw CliException(strings.cliToolListRequired(flag))
    val names = parts.toSet()
    val available = BuiltinTools.names()
    val unknown = names - available
    if (unknown.isNotEmpty()) {
        throw CliException(
            strings.cliUnknownTools(
                flag,
                unknown.sorted().joinToString(),
                available.joinToString(),
            ),
        )
    }
    return names
}

private fun validateSessionFlags(
    mode: CliMode,
    noSession: Boolean,
    continueLatest: Boolean,
    resumeId: String?,
    name: String?,
    strings: AppStrings,
) {
    if (noSession && (continueLatest || resumeId != null)) {
        throw CliException(strings.cliNoSessionConflict)
    }
    if (continueLatest && resumeId != null) {
        throw CliException(strings.cliContinueConflict)
    }
    if (mode == CliMode.Acp && (noSession || continueLatest || resumeId != null || name != null)) {
        throw CliException(strings.cliAcpSessionFlags)
    }
}

internal object KonductorCli {
    val help: String get() = help(AppStrings.english())

    fun help(strings: AppStrings): String =
        strings.cliHelp(BuiltinTools.names().joinToString())

    val version: String
        get() = KonductorCli::class.java.`package`.implementationVersion ?: "0.1.0-SNAPSHOT"
}
