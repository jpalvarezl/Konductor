package com.konductor.conversation

import com.konductor.i18n.AppStrings
import java.util.Locale

/** A leading slash-command token and its otherwise-unparsed source text. */
data class CommandInvocation(
    val name: String,
    val rawArguments: String,
    val rawInput: String,
) {
    companion object {
        /** Parse only a leading slash token; argument whitespace, casing, and content remain untouched. */
        fun parse(rawInput: String): CommandInvocation? {
            val commandStart = rawInput.indexOfFirst { !it.isWhitespace() }
            if (commandStart < 0 || rawInput[commandStart] != '/') return null

            val commandEnd = (commandStart until rawInput.length)
                .firstOrNull { rawInput[it].isWhitespace() }
                ?: rawInput.length
            return CommandInvocation(
                name = rawInput.substring(commandStart, commandEnd),
                rawArguments = rawInput.substring(commandEnd),
                rawInput = rawInput,
            )
        }
    }
}

/** Stable command identity and syntax plus presentation-localized descriptive copy. */
data class CommandDescriptor(
    val name: String,
    val aliases: Set<String> = emptySet(),
    val usage: String,
    val describe: (AppStrings) -> String,
)

/** Work requested by a command; only [ConversationController] executes or launches it. */
sealed interface CommandAction {
    data class Immediate(val apply: () -> Unit) : CommandAction
    data class Background(val run: suspend (StateApplier) -> Unit) : CommandAction
    data object Quit : CommandAction
    data object NotHandled : CommandAction
}

/** A canonical TUI command that describes itself and returns work without launching it. */
interface TuiCommand {
    val descriptor: CommandDescriptor
    fun execute(invocation: CommandInvocation): CommandAction
}

/**
 * Ordered command catalog and case-insensitive lookup. Canonical names and aliases share one collision domain so
 * dispatch can never depend on registration order.
 */
class CommandRegistry(commands: List<TuiCommand>) {
    private val orderedCommands: List<TuiCommand> = commands.toList()
    private val commandsByName: Map<String, TuiCommand> = buildMap {
        orderedCommands.forEach { command ->
            sequenceOf(command.descriptor.name)
                .plus(command.descriptor.aliases.asSequence())
                .forEach { registeredName ->
                    val key = registeredName.lowercase(Locale.ROOT)
                    require(put(key, command) == null) {
                        "Duplicate TUI command name or alias: $registeredName"
                    }
                }
        }
    }

    /** Descriptors in deterministic display order. */
    val descriptors: List<CommandDescriptor> = orderedCommands.map(TuiCommand::descriptor)

    fun find(name: String): TuiCommand? = commandsByName[name.lowercase(Locale.ROOT)]

    /** Parse and dispatch [rawInput], returning [CommandAction.NotHandled] for normal or unknown input. */
    fun dispatch(rawInput: String): CommandAction {
        val invocation = CommandInvocation.parse(rawInput) ?: return CommandAction.NotHandled
        return find(invocation.name)?.execute(invocation) ?: CommandAction.NotHandled
    }
}

internal class FunctionalTuiCommand(
    override val descriptor: CommandDescriptor,
    private val action: (CommandInvocation) -> CommandAction,
) : TuiCommand {
    override fun execute(invocation: CommandInvocation): CommandAction = action(invocation)
}

/** The sole source of built-in top-level command names, aliases, usage syntax, and descriptions. */
internal object BuiltInCommandDescriptors {
    val quit = CommandDescriptor("/quit", setOf("/exit"), "/quit", AppStrings::commandQuitDescription)
    val new = CommandDescriptor("/new", usage = "/new", describe = AppStrings::commandNewDescription)
    val name = CommandDescriptor("/name", usage = "/name <label>", describe = AppStrings::commandNameDescription)
    val session = CommandDescriptor("/session", usage = "/session", describe = AppStrings::commandSessionDescription)
    val resume = CommandDescriptor(
        "/resume",
        usage = "/resume [number|id]",
        describe = AppStrings::commandResumeDescription,
    )
    val compact = CommandDescriptor(
        "/compact",
        usage = "/compact [instructions]",
        describe = AppStrings::commandCompactDescription,
    )
    val model = CommandDescriptor(
        "/model",
        usage = "/model [list | <deployment>]",
        describe = AppStrings::commandModelDescription,
    )
    val connections = CommandDescriptor(
        "/connections",
        usage = "/connections",
        describe = AppStrings::commandConnectionsDescription,
    )
    val agent = CommandDescriptor(
        "/agent",
        usage = "/agent [list | use <name> | create [name]]",
        describe = AppStrings::commandAgentDescription,
    )
}
