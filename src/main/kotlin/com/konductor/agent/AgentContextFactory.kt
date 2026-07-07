package com.konductor.agent

import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
import java.nio.file.Path
import java.time.LocalDate

/**
 * Assembles the [AgentContext] (the preamble the model sees before the transcript) from resolved
 * [Configuration]. M1 covers the base coding-agent prompt + an environment header, plus the
 * `systemPromptOverride` / `systemPromptAppend` hooks; context-file discovery (`AGENTS.md`) and the tool
 * surface arrive with M2 (docs/spec/agent-context.md).
 */
object AgentContextFactory {
    private val BASE_PROMPT = """
        You are Konductor, a terminal coding agent operating in the user's current working directory.
        - Read before you conclude; make minimal, correct changes.
        - Explain briefly, then act. Stop when the task is done.
    """.trimIndent()

    fun build(
        configuration: Configuration,
        cwd: Path = Path.of("").toAbsolutePath(),
    ): AgentContext {
        val systemPrompt = buildString {
            append(configuration.systemPromptOverride ?: BASE_PROMPT)
            append("\n\n").append(environmentHeader(cwd))
            configuration.systemPromptAppend?.let { append("\n\n").append(it) }
        }
        return AgentContext(
            systemPrompt = systemPrompt,
            tools = emptyList(), // M2 wires the ToolRegistry
            modelName = configuration.model,
            temperature = configuration.temperature,
        )
    }

    private fun environmentHeader(cwd: Path): String {
        val os = System.getProperty("os.name") ?: "unknown"
        return "Environment: cwd=$cwd, os=$os, date=${LocalDate.now()}."
    }
}
