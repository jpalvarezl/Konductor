package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.provider.inference.PromptAgentClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Handles the `/agent` TUI slash-command family for the opt-in persisted PromptAgent
 * (M2.5, docs/spec/tui.md#slash-commands): show the active agent, list agents, bind an existing one, or mint a
 * new version from the current [context] and switch to it. Results are folded into [AppState] (system lines +
 * the status-bar `activeAgentName`); it holds no Lanterna dependency so it stays unit-testable.
 *
 * Kept in a dedicated handler so [ConversationController] needs only a one-line delegation — minimising overlap
 * with the concurrently-developed session slash-commands (M3).
 */
class PromptAgentCommand(
    private val state: AppState,
    private val context: AgentContext,
    private val client: PromptAgentClient,
    private val cwd: Path = Path.of("").toAbsolutePath(),
) {
    /** Handle a line already known to start with `/agent`. Network/SDK failures render as a system line. */
    fun handle(line: String) {
        val args = line.trim().removePrefix("/agent").trim()
        try {
            when {
                args.isEmpty() -> showActive()
                args == "list" -> list()
                args.startsWith("use ") -> use(args.removePrefix("use ").trim())
                args == "use" -> system("Usage: /agent use <name>")
                args.startsWith("create ") -> create(args.removePrefix("create ").trim())
                args == "create" -> create(defaultAgentName())
                else -> system("Unknown /agent subcommand '$args'. Try: /agent [list | use <name> | create [name]].")
            }
        } catch (e: Exception) {
            system("⚠ /agent failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun showActive() =
        system("Active agent: ${client.activeAgentName ?: "ephemeral (no persisted agent)"}")

    private fun list() {
        val names = runBlocking { client.listAgentNames() }
        if (names.isEmpty()) {
            system("No persisted agents in this project. Create one with /agent create [name].")
            return
        }
        val active = client.activeAgentName
        system("Persisted agents:\n" + names.joinToString("\n") { "  ${if (it == active) "* " else "  "}$it" })
    }

    private fun use(name: String) {
        if (name.isBlank()) {
            system("Usage: /agent use <name>")
            return
        }
        client.bindAgent(name)
        state.activeAgentName = name
        system("Bound this session to agent '$name' (latest version).")
    }

    private fun create(name: String) {
        if (name.isBlank()) {
            system("Usage: /agent create [name]")
            return
        }
        val ref = runBlocking { client.createAgentVersion(name, context) }
        client.bindAgent(ref.name)
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
}
