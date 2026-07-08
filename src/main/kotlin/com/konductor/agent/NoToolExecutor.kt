package com.konductor.agent

import com.konductor.provider.ToolExecutor

/**
 * A [ToolExecutor] for contexts that advertise **no** tools (e.g. tests, or a future no-tools config): the
 * model should never request one, so if a call arrives, fail loudly rather than silently. Production wiring
 * uses [com.konductor.tool.RegistryToolExecutor] with the built-in tool set instead.
 */
val NoToolExecutor: ToolExecutor = ToolExecutor { call ->
    error("No tools are available in this configuration; the model requested '${call.name}'.")
}
