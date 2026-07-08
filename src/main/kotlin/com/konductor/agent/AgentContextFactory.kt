package com.konductor.agent

import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import java.nio.file.Path
import java.time.LocalDate

/**
 * Assembles the [AgentContext] (the preamble the model sees before the transcript) from resolved
 * [Configuration]. Covers the base coding-agent prompt + an environment header, plus the
 * `systemPromptOverride` / `systemPromptAppend` hooks, and the caller-supplied tool surface (M2). Context-file
 * discovery (`AGENTS.md`) is still deferred (docs/spec/agent-context.md).
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
        tools: List<ToolSpec> = emptyList(),
    ): AgentContext {
        val basePrompt = configuration.systemPromptOverride ?: BASE_PROMPT
        val environment = environmentHeader(cwd)
        // Stable (baked into a persisted agent): the base prompt + the configured append — never the env header,
        // which must stay live per turn.
        val baseSystemPrompt = buildString {
            append(basePrompt)
            configuration.systemPromptAppend?.let { append("\n\n").append(it) }
        }
        // Ephemeral instructions keep the original order: base -> env -> append.
        val systemPrompt = buildString {
            append(basePrompt)
            append("\n\n").append(environment)
            configuration.systemPromptAppend?.let { append("\n\n").append(it) }
        }
        return AgentContext(
            systemPrompt = systemPrompt,
            tools = tools,
            modelName = configuration.model,
            temperature = configuration.temperature,
            baseSystemPrompt = baseSystemPrompt,
            dynamicPreamble = environment,
        )
    }

    private fun environmentHeader(cwd: Path): String {
        val os = System.getProperty("os.name") ?: "unknown"
        return "Environment: cwd=$cwd, os=$os, date=${LocalDate.now()}."
    }
}
