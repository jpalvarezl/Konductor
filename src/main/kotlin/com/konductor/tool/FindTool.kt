package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.io.path.exists

/**
 * `find` — glob file paths under a base directory (default: the working directory). Patterns are matched
 * against paths **relative to the working directory** (e.g. `src/**/*.kt`), so they read naturally. Noise
 * directories (`.git`, `node_modules`, `target`, `build`, …) are pruned; results are sorted and capped.
 * See docs/spec/tools.md.
 */
class FindTool : Tool {
    override val spec = ToolSpec(
        name = NAME,
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
        val root = ctx.cwd.toRealPath()
        val base = resolveInCwd(ctx.cwd, args.path ?: ".")
        if (!base.exists()) return ToolResult(call.callId, "find: no such path: ${args.path}", isError = true)

        val matches = globMatcher(args.pattern)

        val results = withContext(Dispatchers.IO) {
            val found = mutableListOf<String>()
            walkFilesSkippingIgnored(base) { file ->
                val relative = root.relativize(file)
                if (matches(relative)) found += relative.toString()
                found.size < MAX_RESULTS
            }
            found.sorted()
        }

        return ToolResult(call.callId, results.joinToString("\n").ifEmpty { "(no matches)" })
    }

    companion object {
        const val NAME = "find"
        private const val MAX_RESULTS = 500
    }
}
