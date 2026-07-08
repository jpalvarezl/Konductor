package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.stream.Collectors
import kotlin.io.path.exists

/**
 * `find` — glob file paths under a base directory (default: the working directory). Patterns are matched
 * against paths **relative to the working directory** (e.g. `src/**/*.kt`), so they read naturally. Results
 * are sorted and capped; the executor truncates further if needed. See docs/spec/tools.md.
 */
class FindTool : Tool {
    override val spec = ToolSpec(
        name = "find",
        description = "Find files by glob pattern (matched against working-directory-relative paths, e.g. src/**/*.kt).",
        parameters = objectSchema(
            required = listOf("pattern"),
            properties = mapOf(
                "pattern" to prop("string", "Glob pattern, e.g. **/*.kt or src/**/Main.kt."),
                "path" to prop("string", "Base directory to search under, relative to cwd (default '.')."),
            ),
        ),
    )

    @Serializable
    private data class Args(val pattern: String, val path: String? = null)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val root = ctx.cwd.toAbsolutePath().normalize()
        val base = resolveInCwd(ctx.cwd, args.path ?: ".")
        if (!base.exists()) return ToolResult(call.callId, "find: no such path: ${args.path}", isError = true)

        val matcher = FileSystems.getDefault().getPathMatcher("glob:${args.pattern}")

        val matches: List<String> = withContext(Dispatchers.IO) {
            Files.walk(base).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { root.relativize(it) }
                    .filter { matcher.matches(it) }
                    .map { it.toString() }
                    .sorted()
                    .limit(MAX_RESULTS.toLong())
                    .collect(Collectors.toList())
            }
        }

        return ToolResult(call.callId, matches.joinToString("\n").ifEmpty { "(no matches)" })
    }

    private companion object {
        const val MAX_RESULTS = 500
    }
}
