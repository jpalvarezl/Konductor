package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Handles the `/agent` TUI slash-command family for the opt-in persisted PromptAgent
 * (M2.5, docs/spec/tui.md#slash-commands): show the active agent, list agents, hot-swap to an existing one
 * (`use`), or mint a new version from the current [context] and switch to it (`create`). Lifecycle calls go to
 * the standalone [lifecycle] client; switching the live session goes through the [binder]. Results fold into
 * [AppState]; no Lanterna dependency, so it stays unit-testable.
 *
 * Kept in a dedicated handler so [ConversationController] needs only a one-line delegation — minimising overlap
 * with the session slash-commands (M3).
 */
class PromptAgentCommand(
    private val state: AppState,
    private val context: AgentContext,
    private val binder: PromptAgentBinder,
    private val lifecycle: PromptAgentClient,
    private val cwd: Path = Path.of("").toAbsolutePath(),
) {
    /** Handle a line already known to start with `/agent`. Network/SDK failures render as a system line. */
    fun handle(line: String) {
        // ConversationController routes any case/whitespace form of "/agent" here, so strip the (case-insensitive)
        // prefix, then match the subcommand case-insensitively while preserving the agent name's original case.
        val args = line.trim().drop(AGENT_PREFIX.length).trim()
        val (subcommand, argument) = args.split(WHITESPACE, limit = 2).let {
            it.first().lowercase() to it.getOrElse(1) { "" }.trim()
        }
        try {
            when {
                args.isEmpty() -> showActive()
                subcommand == "list" -> list()
                subcommand == "use" -> use(argument)
                subcommand == "create" -> create(argument.ifBlank { defaultAgentName() })
                else -> system("Unknown /agent subcommand '$args'. Try: /agent [list | use <name> | create [name]].")
            }
        } catch (e: Exception) {
            system("⚠ /agent failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun showActive() =
        system("Active agent: ${binder.activeAgent ?: "ephemeral (no persisted agent)"}")

    private fun list() {
        val names = runBlocking { lifecycle.listAgents() }
        if (names.isEmpty()) {
            system("No persisted agents in this project. Create one with /agent create [name].")
            return
        }
        val active = binder.activeAgent
        system("Persisted agents:\n" + names.joinToString("\n") { "  ${if (it == active) "* " else "  "}$it" })
    }

    private fun use(name: String) {
        if (name.isBlank()) {
            system("Usage: /agent use <name>")
            return
        }
        binder.bindAgent(name)
        state.activeAgentName = name
        system("Switched this session to agent '$name' (latest version).")
    }

    private fun create(name: String) {
        if (name.isBlank()) {
            system("Usage: /agent create [name]")
            return
        }
        val ref = runBlocking {
            lifecycle.createAgentVersion(name, context.modelName, context.systemPrompt, context.tools)
        }
        binder.bindAgent(ref.name)
        state.activeAgentName = ref.name
        system("Created agent '${ref.name}' version ${ref.version} from the current context and switched to it.")
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

    private fun system(text: String) = state.addMessage(ChatMessage(MessageRole.System, text))

    private companion object {
        private const val AGENT_PREFIX = "/agent"
        private val WHITESPACE = Regex("\\s+")
    }
}
