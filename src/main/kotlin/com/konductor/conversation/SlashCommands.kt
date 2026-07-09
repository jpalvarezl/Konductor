package com.konductor.conversation

/**
 * Central registry of supported slash-commands for the TUI.
 *
 * Keep this aligned with [ConversationController.handleCommand] and any additional command handlers
 * (e.g., [PromptAgentCommand]).
 */
object SlashCommands {
    /**
     * Top-level commands. For subcommands (like `/agent create`) completion is handled separately.
     */
    val topLevel: List<String> = listOf(
        "/new",
        "/resume",
        "/name",
        "/session",
        "/agent",
        "/quit",
        "/exit",
    )

    /**
     * Optional subcommand suggestions keyed by top-level command.
     * These are used only when the user already typed the parent command.
     */
    val subcommands: Map<String, List<String>> = mapOf(
        "/agent" to listOf("list", "use", "create"),
    )
}
