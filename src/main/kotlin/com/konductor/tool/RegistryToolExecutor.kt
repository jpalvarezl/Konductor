package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.provider.ToolExecutor
import kotlinx.coroutines.CancellationException

/**
 * Bridges the harness-owned [ToolExecutor] seam ([com.konductor.provider.PromptProvider] calls it) to the
 * [ToolRegistry]: resolve the enabled tool, run it under [context], convert any unexpected failure into an
 * error result, and cap the output. Tools excluded by the allow-list (read-only mode) or unknown to the
 * registry are refused here with an error result, so a read-only run cannot mutate the workspace even if the
 * model hallucinates a mutating call. See docs/spec/tools.md.
 */
class RegistryToolExecutor(
    private val registry: ToolRegistry,
    private val context: ToolContext,
    private val maxOutputBytes: Int = MAX_TOOL_OUTPUT_BYTES,
) : ToolExecutor {
    override suspend fun execute(call: ToolCall): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult(call.callId, "unknown or disabled tool: ${call.name}", isError = true)

        val result = try {
            tool.execute(call, context)
        } catch (cancellation: CancellationException) {
            throw cancellation // never swallow coroutine cancellation
        } catch (error: Throwable) {
            ToolResult(
                callId = call.callId,
                output = "tool '${call.name}' failed: ${error.message ?: error::class.simpleName}",
                isError = true,
            )
        }
        return truncateToolResult(result, maxOutputBytes)
    }
}
