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
    /**
     * An env lookup that returns the real env var when set (non-blank), otherwise the value from [path].
     * Pass to [Configuration.load] as its `env` argument.
     */
    fun overlay(
        path: Path = Path.of(".env"),
        base: (String) -> String? = System::getenv,
    ): (String) -> String? {
        val fromFile = read(path)
        return { name -> base(name)?.takeIf { it.isNotBlank() } ?: fromFile[name] }
    }

    fun read(path: Path): Map<String, String> {
        if (!Files.exists(path)) return emptyMap()
        return Files.readAllLines(path).mapNotNull(::parseLine).toMap()
    }

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
