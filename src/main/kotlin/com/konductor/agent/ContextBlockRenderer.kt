package com.konductor.agent

import java.nio.file.Path

/** The single path-normalization, attribute-escaping, and provenance-boundary renderer for context files. */
object ContextBlockRenderer {
    /** Absolute, lexically normalized host path with `/` separators (including `//` for a Windows UNC prefix). */
    fun displayPath(canonicalPath: Path): String {
        val normalized = canonicalPath.toAbsolutePath().normalize()
        val rendered = normalized.toString()
        return if (normalized.fileSystem.separator == "\\") rendered.replace('\\', '/') else rendered
    }

    /** Return no block for no records; otherwise render the exact deterministic context boundary shape. */
    fun render(records: List<ContextFileRecord>): String? {
        if (records.isEmpty()) return null
        return buildString {
            append("<project_context>\n")
            records.forEachIndexed { index, record ->
                if (index > 0) append('\n')
                append("<project_instructions path=\"")
                append(escapeAttribute(record.displayPath))
                append("\">\n")
                append(record.content)
                append("\n</project_instructions>")
            }
            append("\n</project_context>")
        }
    }

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
