package com.konductor.tool

/**
 * The built-in tool set (docs/spec/tools.md) and the default [ToolRegistry] factory. Read-only tools come
 * first, then the mutating ones; a read-only run is `registry(allow = setOf("read", "ls", "find", "grep"))`.
 */
object BuiltinTools {
    private val tools: List<Tool> = listOf(
        ReadTool(),
        LsTool(),
        FindTool(),
        GrepTool(),
        BashTool(),
        WriteTool(),
        EditTool(),
    )
    private val toolNames: Set<String> = tools.mapTo(linkedSetOf()) { it.spec.name }

    /** Every built-in tool, in a stable advertised order. */
    fun all(): List<Tool> = tools

    /** Build a registry over [all], honoring an optional allow-list (`null` ⇒ everything enabled). */
    fun registry(allow: Set<String>? = null): ToolRegistry = ToolRegistry(all(), allow)

    /** Stable names used by configuration and CLI validation. */
    fun names(): Set<String> = toolNames
}
