package com.konductor.conversation

import com.konductor.agent.PromptAgentBindingResult
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.i18n.AppStrings
import com.konductor.provider.inference.PromptAgentClient
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

/**
 * Handles the `/agent` TUI command family. Lifecycle creation remains a remote operation, while adoption is delegated
 * to `AgentLoop` so metadata persistence, provider publication, and live-session mutation share one operation boundary.
 * Work runs off the event loop and publishes copy/status only through [LocalCommandContext].
 */
class PromptAgentCommand(
    private val state: AppState,
    private val contextProvider: () -> AgentContext,
    private val activeAgentProvider: () -> String?,
    private val adoptAgent: suspend (
        String?,
        suspend (suspend () -> PromptAgentBindingResult) -> Unit,
    ) -> Unit,
    private val lifecycle: PromptAgentClient,
    private val cwd: Path = Path.of("").toAbsolutePath(),
    private val strings: AppStrings = AppStrings.english(),
) : TuiCommand {
    /** Source-compatible adapter for focused command tests that do not own an AgentLoop operation gate. */
    constructor(
        state: AppState,
        contextProvider: () -> AgentContext,
        activeAgentProvider: () -> String?,
        adoptAgent: (String?) -> PromptAgentBindingResult,
        lifecycle: PromptAgentClient,
        cwd: Path = Path.of("").toAbsolutePath(),
        strings: AppStrings = AppStrings.english(),
    ) : this(
        state,
        contextProvider,
        activeAgentProvider,
        adoptAgent = { name, commit -> commit { adoptAgent(name) } },
        lifecycle,
        cwd,
        strings,
    )

    override val descriptor: CommandDescriptor = BuiltInCommandDescriptors.agent

    override fun execute(invocation: CommandInvocation): CommandAction = CommandAction.Background { command ->
        // The TUI is the user-input edge: pass trimmed names to the domain, which rejects rather than rewrites them.
        val args = invocation.rawArguments.trim()
        val (subcommand, argument) = args.split(Regex("\\s+"), limit = 2).let {
            it.first().lowercase() to it.getOrElse(1) { "" }.trim()
        }
        try {
            when {
                args.isEmpty() -> publish(command, strings.activeAgent(activeAgentProvider() ?: strings.ephemeralAgent))
                subcommand == "list" -> list(command)
                subcommand == "use" -> use(argument, command)
                subcommand == "create" -> create(argument.ifBlank { defaultAgentName() }, command)
                else -> publish(command, strings.agentUnknownSubcommand(args))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            publish(command, strings.agentFailed(errorReason(error)), failed = true)
        }
    }

    private suspend fun list(command: LocalCommandContext) {
        val names = lifecycle.listAgents()
        if (names.isEmpty()) {
            publish(command, strings.noPersistedAgents)
            return
        }
        val active = activeAgentProvider()
        val items = names.joinToString("\n") {
            strings.persistedAgentItem(if (it == active) "* " else "  ", it)
        }
        publish(command, strings.persistedAgents(items))
    }

    private suspend fun use(name: String, command: LocalCommandContext) {
        if (name.isBlank()) {
            publish(command, strings.agentUseUsage)
            return
        }
        try {
            adoptAgent(name) { commitAdoption ->
                command.commit commitBlock@{
                    val result = try {
                        commitAdoption()
                    } catch (error: Exception) {
                        fail { addSystem(strings.agentAdoptionFailed(name, errorReason(error))) }
                        return@commitBlock
                    }
                    apply { publishCommitted(result, strings.switchedAgent(requireNotNull(result.agentName))) }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            publish(command, strings.agentAdoptionFailed(name, errorReason(error)), failed = true)
        }
    }

    private suspend fun create(name: String, command: LocalCommandContext) {
        if (name.isBlank()) {
            publish(command, strings.agentCreateUsage)
            return
        }
        val ref = try {
            // Bake the stable base + configured append only; the dynamic preamble rides each invocation.
            val currentContext = contextProvider()
            lifecycle.createAgentVersion(
                name,
                currentContext.modelName,
                currentContext.baseSystemPrompt,
                currentContext.tools,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            publish(command, strings.agentCreateAmbiguous(name, errorReason(error)), failed = true)
            return
        }

        try {
            adoptAgent(ref.name) { commitAdoption ->
                command.commit commitBlock@{
                    val result = try {
                        commitAdoption()
                    } catch (error: Exception) {
                        fail { addSystem(strings.createdAgentNotAdopted(ref.name, ref.version, errorReason(error))) }
                        return@commitBlock
                    }
                    apply { publishCommitted(result, strings.createdAgent(ref.name, ref.version)) }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            publish(
                command,
                strings.createdAgentNotAdopted(ref.name, ref.version, errorReason(error)),
                failed = true,
            )
        }
    }

    private fun publishCommitted(result: PromptAgentBindingResult, copy: String) {
        if (result.changed) state.activeAgentName = result.agentName
        addSystem(copy)
    }

    private suspend fun publish(command: LocalCommandContext, text: String, failed: Boolean = false) {
        command.commit {
            if (failed) fail { addSystem(text) } else apply { addSystem(text) }
        }
    }

    /** A cwd-derived default so `/agent create` (no name) still yields a stable, service-legal agent name. */
    private fun defaultAgentName(): String {
        val slug = (cwd.fileName?.toString() ?: "")
            .lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .trim('-')
            .ifBlank { "workspace" }
        return "konductor-$slug"
    }

    private fun addSystem(text: String) = state.addMessage(ChatMessage(MessageRole.System, text))

    private fun errorReason(error: Throwable): String =
        error.message ?: error::class.simpleName ?: strings.unknownError
}
