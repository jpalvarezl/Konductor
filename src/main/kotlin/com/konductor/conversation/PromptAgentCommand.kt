package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.i18n.AppStrings
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
 * As a [TuiCommand], it returns its current immediate behavior to the canonical registry while retaining the nested
 * `list|use|create` parser and provider dependencies here.
 */
class PromptAgentCommand(
    private val state: AppState,
    // A provider, not a snapshot: `/model` swaps the active AgentContext at runtime, so `create` must read the
    // CURRENT context (and its model) when minting an agent version rather than one captured at construction.
    private val contextProvider: () -> AgentContext,
    private val binder: PromptAgentBinder,
    private val lifecycle: PromptAgentClient,
    private val recordAgent: (String?) -> Unit,
    private val cwd: Path = Path.of("").toAbsolutePath(),
    private val strings: AppStrings = AppStrings.english(),
) : TuiCommand {
    override val descriptor: CommandDescriptor = BuiltInCommandDescriptors.agent

    /** Return the current immediate behavior; nested arguments remain owned by this command. */
    override fun execute(invocation: CommandInvocation): CommandAction = CommandAction.Immediate {
        val args = invocation.rawArguments.trim()
        val (subcommand, argument) = args.split(Regex("\\s+"), limit = 2).let {
            it.first().lowercase() to it.getOrElse(1) { "" }.trim()
        }
        try {
            when {
                args.isEmpty() -> showActive()
                subcommand == "list" -> list()
                subcommand == "use" -> use(argument)
                subcommand == "create" -> create(argument.ifBlank { defaultAgentName() })
                else -> system(strings.agentUnknownSubcommand(args))
            }
        } catch (e: Exception) {
            system(strings.agentFailed(errorReason(e)))
        }
    }

    /**
     * A brand-new session adopts (and records) the currently-bound agent, so startup/`/new` keeps the active
     * agent bound rather than silently dropping it.
     */
    fun onFreshSession() {
        val current = binder.activeAgent ?: return
        state.activeAgentName = current
        recordAgent(current)
    }

    /**
     * A resumed session restores its saved agent. Persisted agents are **volatile** (they can be deleted
     * server-side), so this validates existence first (via the lifecycle client) and falls back to ephemeral —
     * with a notice — if the agent is gone. Because the transcript, not the agent, holds the context, that
     * fallback is safe (no data loss). A session that was ephemeral (`null`) unbinds any currently-active agent.
     */
    fun onResumedSession(savedAgent: String?) {
        if (savedAgent == null) {
            binder.bindAgent(null)
            state.activeAgentName = null
            return
        }
        val available = runCatching { runBlocking { lifecycle.listAgents() } }.getOrDefault(emptyList())
        if (savedAgent in available) {
            binder.bindAgent(savedAgent)
            state.activeAgentName = savedAgent
        } else {
            binder.bindAgent(null)
            state.activeAgentName = null
            system(strings.agentUnavailable(savedAgent))
        }
    }

    /** Persist the candidate binding before switching the live binder and status bar. */
    private fun switchTo(name: String) {
        recordAgent(name)
        binder.bindAgent(name)
        state.activeAgentName = name
    }

    private fun showActive() =
        system(strings.activeAgent(binder.activeAgent ?: strings.ephemeralAgent))

    private fun list() {
        val names = runBlocking { lifecycle.listAgents() }
        if (names.isEmpty()) {
            system(strings.noPersistedAgents)
            return
        }
        val active = binder.activeAgent
        val items = names.joinToString("\n") {
            strings.persistedAgentItem(if (it == active) "* " else "  ", it)
        }
        system(strings.persistedAgents(items))
    }

    private fun use(name: String) {
        if (name.isBlank()) {
            system(strings.agentUseUsage)
            return
        }
        switchTo(name)
        system(strings.switchedAgent(name))
    }

    private fun create(name: String) {
        if (name.isBlank()) {
            system(strings.agentCreateUsage)
            return
        }
        val ref = runBlocking {
            // Bake the STABLE base prompt (no env header) into the agent; the dynamic preamble rides each turn.
            val currentContext = contextProvider()
            lifecycle.createAgentVersion(
                name,
                currentContext.modelName,
                currentContext.baseSystemPrompt,
                currentContext.tools,
            )
        }
        switchTo(ref.name)
        system(strings.createdAgent(ref.name, ref.version))
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

    private fun errorReason(error: Throwable): String =
        error.message ?: error::class.simpleName ?: strings.unknownError
}
