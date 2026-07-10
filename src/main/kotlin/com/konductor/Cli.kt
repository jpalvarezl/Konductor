package com.konductor

import com.konductor.provider.AgentKind
import com.konductor.tool.BuiltinTools
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

internal fun parseCliArgs(args: Array<String>): CliOptions {
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
            throw CliException("--tools, --exclude-tools, and --no-tools are mutually exclusive.")
        }
        toolSelection = selection
    }

    while (index < args.size) {
        when (val arg = args[index]) {
            "--help", "-h" -> {
                action = selectAction(action, CliAction.Help, arg)
                index += 1
            }
            "--version", "-V" -> {
                action = selectAction(action, CliAction.Version, arg)
                index += 1
            }
            "acp", "--acp" -> {
                mode = CliMode.Acp
                index += 1
            }
            "--agent-kind" -> {
                agentKind = parseAgentKindArgument(args.valueAfter(arg, index))
                index += 2
            }
            "--model" -> {
                model = args.valueAfter(arg, index)
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
                resumeId = parseSessionIdArgument(args.valueAfter(arg, index))
                index += 2
            }
            "--name" -> {
                name = args.valueAfter(arg, index)
                index += 2
            }
            "--tools" -> {
                selectTools(ToolSelection.Only(parseToolNames(args.valueAfter(arg, index), arg)))
                index += 2
            }
            "--exclude-tools" -> {
                selectTools(ToolSelection.Exclude(parseToolNames(args.valueAfter(arg, index), arg)))
                index += 2
            }
            "--no-tools" -> {
                selectTools(ToolSelection.None)
                index += 1
            }
            else -> throw CliException(
                if (arg.startsWith("-")) "Unknown option '$arg'." else "Unexpected positional argument '$arg'.",
            )
        }
    }

    validateSessionFlags(mode, noSession, continueLatest, resumeId, name)
    return CliOptions(action, mode, agentKind, model, noSession, continueLatest, resumeId, name, toolSelection)
}

private fun selectAction(current: CliAction, requested: CliAction, flag: String): CliAction {
    if (current != CliAction.Run && current != requested) {
        throw CliException("$flag cannot be combined with another informational option.")
    }
    return requested
}

private fun Array<String>.valueAfter(flag: String, index: Int): String {
    val value = getOrNull(index + 1)
    if (value == null || value.startsWith("-")) throw CliException("Missing value after $flag.")
    return value
}

private fun parseAgentKindArgument(value: String): AgentKind =
    AgentKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: throw CliException("Unknown --agent-kind '$value'; expected prompt or hosted.")

private fun parseSessionIdArgument(value: String): String =
    runCatching { Uuid.parse(value.trim()) }
        .fold(
            onSuccess = { value },
            onFailure = { throw CliException("Invalid --resume session id '$value' (expected a session UUID).") },
        )

private fun parseToolNames(raw: String, flag: String): Set<String> {
    val parts = raw.split(",").map(String::trim)
    if (parts.any(String::isEmpty)) throw CliException("$flag requires a comma-separated list of tool names.")
    val names = parts.toSet()
    val unknown = names - BuiltinTools.names()
    if (unknown.isNotEmpty()) {
        throw CliException(
            "Unknown tool name(s) for $flag: ${unknown.sorted().joinToString()}. " +
                "Available tools: ${BuiltinTools.names().joinToString()}.",
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
) {
    if (noSession && (continueLatest || resumeId != null)) {
        throw CliException("--no-session cannot be combined with --resume or --continue.")
    }
    if (continueLatest && resumeId != null) {
        throw CliException("--continue cannot be combined with --resume.")
    }
    if (mode == CliMode.Acp && (noSession || continueLatest || resumeId != null || name != null)) {
        throw CliException("--no-session, --continue, --resume, and --name apply only to TUI sessions, not ACP mode.")
    }
}

internal object KonductorCli {
    val help: String = """
        Usage:
          konductor [options]
          konductor acp [options]

        Frontend:
          acp, --acp                    Run headless over Agent Client Protocol.

        Provider:
          --agent-kind <prompt|hosted>  Select the provider kind.
          --model <name>                Override the Prompt model deployment.

        Tools (Prompt/client-side; mutually exclusive):
          --tools <names>               Enable only the comma-separated built-ins.
          --exclude-tools <names>       Remove names from the configured/default built-ins.
          --no-tools                    Disable all client-side tools.
          Available: ${BuiltinTools.names().joinToString()}

        TUI sessions:
          --no-session                  Use an ephemeral session.
          --continue, -c                Resume the latest session for this directory.
          --resume, -r <id>             Resume a session UUID.
          --name <name>                 Name a new or resumed session.

        Information:
          --help, -h                    Show this help without requiring Foundry configuration.
          --version, -V                 Show the Konductor version.
    """.trimIndent()

    val version: String
        get() = KonductorCli::class.java.`package`.implementationVersion ?: "0.1.0-SNAPSHOT"
}
