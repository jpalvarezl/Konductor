package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import java.nio.file.Path

/**
 * A single client-side capability exposed to the model as a function tool. The harness advertises [spec] to
 * the model; when the model calls the tool, the [RegistryToolExecutor] runs [execute] locally (cwd-scoped)
 * and feeds the [ToolResult] back into the Prompt loop. See docs/spec/tools.md.
 */
interface Tool {
    val spec: ToolSpec

    /**
     * Run the [call]. Implementations parse [ToolCall.argumentsJson], do their work under [ctx], and return a
     * [ToolResult] tagged with [ToolCall.callId]. **Expected** failures (missing path, no match, non-unique
     * edit) should return a result with `isError = true` and a short message the model can act on; unexpected
     * exceptions may propagate — the executor converts them into an error result and truncates the output.
     */
    suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult
}

/**
 * Ambient execution context shared by every tool for one run. [cwd] is the containment root: every path
 * argument is resolved against it and rejected if it escapes (see [resolveInCwd]). Tool execution runs inside the
 * turn coroutine, so cancellation propagates through suspending tools and the executor.
 */
data class ToolContext(val cwd: Path)
