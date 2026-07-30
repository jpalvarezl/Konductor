package com.konductor.agent

import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import java.nio.file.Path
import java.time.LocalDate

/**
 * Assembles the [AgentContext] (the preamble the model sees before the transcript) from resolved
 * [Configuration]. The caller supplies already discovered structured context records; this factory invokes the one
 * renderer and preserves its bytes in both ephemeral and persisted Prompt request shapes.
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
        contextFiles: List<ContextFileRecord> = emptyList(),
    ): AgentContext {
        val basePrompt = normalizeNewlines(configuration.systemPromptOverride ?: BASE_PROMPT)
        val append = configuration.systemPromptAppend?.let(::normalizeNewlines)
        val contextBlock = ContextBlockRenderer.render(contextFiles)
        val environment = normalizeNewlines(environmentHeader(cwd))
        // Stable definitions bake base + append. Context provenance and environment remain cwd-dynamic.
        val baseSystemPrompt = joinNonEmpty(basePrompt, append)
        val dynamicPreamble = joinNonEmpty(contextBlock, environment)
        val systemPrompt = joinNonEmpty(baseSystemPrompt, dynamicPreamble)
        return AgentContext(
            systemPrompt = systemPrompt,
            tools = tools,
            modelName = configuration.model,
            temperature = configuration.temperature,
            baseSystemPrompt = baseSystemPrompt,
            dynamicPreamble = dynamicPreamble,
        )
    }

    private fun joinNonEmpty(vararg components: String?): String =
        components.filterNotNull().filter(String::isNotEmpty).joinToString("\n\n")

    private fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

    private fun environmentHeader(cwd: Path): String {
        val os = System.getProperty("os.name") ?: "unknown"
        return "Environment: cwd=$cwd, os=$os, date=${LocalDate.now()}."
    }
}
