package com.konductor.config

import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal `.env` support for local dev: overlays a cwd `.env` file *under* the real environment, so
 * `FOUNDRY_PROJECT_ENDPOINT` / `FOUNDRY_MODEL_NAME` can live in a .gitignored file instead of the shell —
 * a bare `mvn` (or `java -jar …`) run from the project root then picks them up automatically.
 *
 * Real (non-blank) environment variables always win; the file only fills gaps. Parsing is deliberately
 * simple: `KEY=VALUE` lines, ignoring blanks and `#` comments, tolerating an optional `export ` prefix and
 * surrounding single/double quotes (so a `wr-load`-written `KEY='value'` block is read verbatim).
 */
object EnvFile {
    /** Distinct operator-controlled and trust-eligible project environment sources. */
    class Sources internal constructor(
        private val process: (String) -> String?,
        private val project: Map<String, String>,
    ) {
        fun processValue(name: String): String? = process(name)?.takeIf { it.isNotBlank() }

        fun projectValue(name: String): String? = project[name]

        fun effectiveValue(name: String): String? = processValue(name) ?: projectValue(name)

        fun processLookup(): (String) -> String? = ::processValue

        fun projectLookup(): (String) -> String? = ::projectValue

        fun effectiveLookup(): (String) -> String? = ::effectiveValue
    }

    /** Capture real process environment without opening a project file. */
    fun processSources(base: (String) -> String? = System::getenv): Sources = Sources(base, emptyMap())

    /**
     * Add an already trust-eligible project dotenv. Callers must not invoke this until workspace trust is established.
     */
    fun trustedProjectSources(
        path: Path,
        process: (String) -> String? = System::getenv,
    ): Sources = Sources(process, read(path))

    /** Parse already bounded, strict-decoded dotenv text without performing filesystem I/O. */
    fun trustedProjectSources(
        content: String,
        process: (String) -> String? = System::getenv,
    ): Sources = Sources(process, parse(content))

    /**
     * Legacy compatibility/test overlay. Production keeps [Sources.processLookup] separate and parses a bounded
     * project dotenv only after workspace trust has been resolved.
     */
    fun overlay(
        path: Path = Path.of(".env"),
        base: (String) -> String? = System::getenv,
    ): (String) -> String? = trustedProjectSources(path, base).effectiveLookup()

    fun read(path: Path): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        return parse(Files.readString(path))
    }

    /** Parse dotenv text; duplicate keys retain the last value, matching the legacy file reader. */
    fun parse(content: String): Map<String, String> = content.lineSequence().mapNotNull(::parseLine).toMap()

    private fun parseLine(raw: String): Pair<String, String>? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        val idx = line.indexOf('=')
        if (idx <= 0) return null
        val key = line.substring(0, idx).trim().removePrefix("export ").trim()
        if (key.isEmpty()) return null
        return key to unquote(line.substring(idx + 1).trim())
    }

    private fun unquote(value: String): String {
        val quoted = value.length >= 2 &&
            ((value.first() == '\'' && value.last() == '\'') || (value.first() == '"' && value.last() == '"'))
        return if (quoted) value.substring(1, value.length - 1) else value
    }
}
