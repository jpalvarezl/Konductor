package com.konductor.tool

/**
 * Holds the built-in tools and applies the optional allow-list. Read-only mode is simply
 * `allow = {"read", "ls", "find", "grep"}` — the mutating tools are then absent from both [enabled] (what is
 * advertised to the model) and [get] (what the executor can resolve), so the model cannot mutate the
 * workspace even if it hallucinates a `write`/`edit`/`bash` call. `allow = null` enables everything.
 * See docs/spec/tools.md.
 */
class ToolRegistry(
    tools: List<Tool>,
    private val allow: Set<String>? = null,
) {
    private val byName: Map<String, Tool> = tools.associateBy { it.spec.name }

    /** The active tools: all of them unless an allow-list restricts the set. */
    fun enabled(): List<Tool> = byName.values.filter { isAllowed(it.spec.name) }

    /** Resolve an enabled tool by name; `null` if unknown or excluded by the allow-list. */
    fun get(name: String): Tool? = byName[name]?.takeIf { isAllowed(name) }

    private fun isAllowed(name: String): Boolean = allow == null || name in allow
}
