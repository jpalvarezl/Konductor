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
 * Work runs off the event loop and publishes copy/status only through [StateApplier].
 */
class PromptAgentCommand(
    private val state: AppState,
    private val contextProvider: () -> AgentContext,
    private val activeAgentProvider: () -> String?,
    private val adoptAgent: (String?) -> PromptAgentBindingResult,
    private val lifecycle: PromptAgentClient,
    private val cwd: Path = Path.of("").toAbsolutePath(),
    private val strings: AppStrings = AppStrings.english(),
) : TuiCommand {
    override val descriptor: CommandDescriptor = BuiltInCommandDescriptors.agent

    override fun execute(invocation: CommandInvocation): CommandAction = CommandAction.Background { applier ->
        // The TUI is the user-input edge: pass trimmed names to the domain, which rejects rather than rewrites them.
        val args = invocation.rawArguments.trim()
        val (subcommand, argument) = args.split(Regex("\\s+"), limit = 2).let {
            it.first().lowercase() to it.getOrElse(1) { "" }.trim()
        }
        try {
            when {
                args.isEmpty() -> showActive(applier)
                subcommand == "list" -> list(applier)
                subcommand == "use" -> use(argument, applier)
                subcommand == "create" -> create(argument.ifBlank { defaultAgentName() }, applier)
                else -> system(applier, strings.agentUnknownSubcommand(args))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            system(applier, strings.agentFailed(errorReason(e)))
        }
    }

    private fun showActive(applier: StateApplier) =
        system(applier, strings.activeAgent(activeAgentProvider() ?: strings.ephemeralAgent))

    private suspend fun list(applier: StateApplier) {
        val names = lifecycle.listAgents()
        if (names.isEmpty()) {
            system(applier, strings.noPersistedAgents)
            return
        }
        val active = activeAgentProvider()
        val items = names.joinToString("\n") {
            strings.persistedAgentItem(if (it == active) "* " else "  ", it)
        }
        system(applier, strings.persistedAgents(items))
    }

    private fun use(name: String, applier: StateApplier) {
        if (name.isBlank()) {
            system(applier, strings.agentUseUsage)
            return
        }
        val result = try {
            adoptAgent(name)
        } catch (error: Exception) {
            system(applier, strings.agentAdoptionFailed(name, errorReason(error)))
            return
        }
        publishCommitted(applier, result, strings.switchedAgent(requireNotNull(result.agentName)))
    }

    private suspend fun create(name: String, applier: StateApplier) {
        if (name.isBlank()) {
            system(applier, strings.agentCreateUsage)
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
            system(applier, strings.agentCreateAmbiguous(name, errorReason(error)))
            return
        }

        val result = try {
            adoptAgent(ref.name)
        } catch (error: Exception) {
            system(applier, strings.createdAgentNotAdopted(ref.name, ref.version, errorReason(error)))
            return
        }
        publishCommitted(applier, result, strings.createdAgent(ref.name, ref.version))
    }

    private fun publishCommitted(applier: StateApplier, result: PromptAgentBindingResult, copy: String) = applier {
        if (result.changed) state.activeAgentName = result.agentName
        state.addMessage(ChatMessage(MessageRole.System, copy))
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

    private fun system(applier: StateApplier, text: String) = applier {
        state.addMessage(ChatMessage(MessageRole.System, text))
    }

    private fun errorReason(error: Throwable): String =
        error.message ?: error::class.simpleName ?: strings.unknownError
}
