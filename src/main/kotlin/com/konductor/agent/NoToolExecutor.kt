package com.konductor.agent

import com.konductor.provider.ToolExecutor

/**
 * The M1 tool surface: none. The Prompt loop never advertises tools yet, so the model should not request
 * any; if one somehow arrives, fail loudly rather than silently. Real tools + a cwd-scoped executor land
 * in M2 (docs/spec/tools.md).
 */
val NoToolExecutor: ToolExecutor = ToolExecutor { call ->
    error("No tools are available yet (M1); the model requested '${call.name}'.")
}
