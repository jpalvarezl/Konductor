package com.konductor.tool

import com.konductor.agent.NoToolExecutor
import com.konductor.core.models.ToolSpec
import com.konductor.provider.ProviderCapabilities
import com.konductor.provider.ToolExecutor
import java.nio.file.Path

/** Shared TUI/ACP composition for the client-local tool surface declared by a provider runtime. */
data class ProviderToolRuntime(
    val specs: List<ToolSpec>,
    val executor: ToolExecutor,
)

fun ProviderCapabilities.createToolRuntime(toolAllow: Set<String>?, cwd: Path): ProviderToolRuntime {
    if (!localTools) return ProviderToolRuntime(emptyList(), NoToolExecutor)
    val registry = BuiltinTools.registry(toolAllow)
    return ProviderToolRuntime(
        specs = registry.enabled().map { it.spec },
        executor = RegistryToolExecutor(registry, ToolContext(cwd)),
    )
}
