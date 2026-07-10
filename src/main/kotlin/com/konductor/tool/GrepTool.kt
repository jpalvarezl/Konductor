package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * `grep` — regex-search file contents under a base directory (default: the working directory), optionally
 * restricted by a file glob. Emits `path:lineNumber:line` per match (paths relative to cwd).
 *
 * When a `ripgrep` (`rg`) binary is on `PATH` it is used (fast, `.gitignore`-aware, binary-skipping); otherwise
 * a portable in-process implementation runs that prunes noise directories ([IGNORED_DIR_NAMES]), skips
 * binary / non-UTF-8 files, and caps the match count. Keeping the loop in-process means `grep` works with no
 * external dependency (see docs/spec/tools.md); bundling `rg` with releases is tracked in future.md.
 *
 * @param preferRipgrep whether to shell out to `rg`; defaults to [RIPGREP_AVAILABLE]. Tests pin it to `false`
 *   to exercise the portable path deterministically.
 */
class GrepTool(private val preferRipgrep: Boolean = RIPGREP_AVAILABLE) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Regex-search file contents; returns matching lines as path:lineNumber:line.",
        parameters = objectSchema(
            required = listOf("pattern"),
            properties = mapOf(
                "pattern" to prop("string", "Regular expression to search for in each line."),
                "path" to prop("string", "Base directory (or single file) to search, relative to cwd (default '.')."),
                "glob" to prop("string", "Optional glob to restrict which files are searched, e.g. **/*.kt."),
            ),
        ),
    )

    @Serializable
    private data class Args(val pattern: String, val path: String? = null, val glob: String? = null)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val base = resolveInCwd(ctx.cwd, args.path ?: ".")
        if (!base.exists()) {
            return@withContext ToolResult(call.callId, "grep: no such path: ${args.path}", isError = true)
        }
        if (preferRipgrep) runRipgrep(call, args, ctx, base) else runInProcess(call, args, ctx, base)
    }

    private fun runRipgrep(call: ToolCall, args: Args, ctx: ToolContext, base: Path): ToolResult {
        val command = buildList {
            add(RIPGREP); add("--line-number"); add("--no-heading"); add("--color"); add("never")
            args.glob?.let { add("--glob"); add(it) }
            add("--"); add(args.pattern); add(displayPath(ctx.cwd, base))
        }
        val process = ProcessBuilder(command).directory(ctx.cwd.toFile()).start()
        process.outputStream.close()

        // Drain stderr on a daemon thread so a chatty error can't deadlock the stdout read.
        val errors = StringBuffer()
        val errorPump = thread(start = true, isDaemon = true, name = "grep-rg-stderr") {
            process.errorStream.bufferedReader().forEachLine { if (errors.length < 4_096) errors.append(it).append('\n') }
        }

        val lines = mutableListOf<String>()
        process.inputStream.bufferedReader().useLines { seq ->
            for (line in seq) {
                lines += line
                if (lines.size >= MAX_MATCHES) break
            }
        }

        val finished = process.waitFor(RG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        errorPump.join(1_000)

        // ripgrep exit codes: 0 = matches, 1 = no matches, 2 = error.
        if (lines.isEmpty() && finished && process.exitValue() == 2) {
            val message = errors.toString().trim().ifEmpty { "exit code 2" }
            return ToolResult(call.callId, "grep: ripgrep error: $message", isError = true)
        }
        return ToolResult(call.callId, lines.joinToString("\n").ifEmpty { "(no matches)" })
    }

    private fun runInProcess(call: ToolCall, args: Args, ctx: ToolContext, base: Path): ToolResult {
        val root = ctx.cwd.toRealPath()
        val regex = runCatching { Regex(args.pattern) }.getOrElse {
            return ToolResult(call.callId, "grep: invalid regex: ${it.message}", isError = true)
        }
        val globPredicate = args.glob?.let { globMatcher(it) }

        val out = mutableListOf<String>()
        walkFilesSkippingIgnored(base) { file ->
            val relative = root.relativize(file)
            if (globPredicate == null || globPredicate(relative)) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                val text = bytes?.takeIf { candidate -> candidate.none { it == 0.toByte() } }?.let { decodeUtf8OrNull(it) }
                if (text != null) {
                    var lineNumber = 0
                    for (line in text.lineSequence()) {
                        lineNumber++
                        if (regex.containsMatchIn(line)) {
                            out += "$relative:$lineNumber:$line"
                            if (out.size >= MAX_MATCHES) break
                        }
                    }
                }
            }
            out.size < MAX_MATCHES
        }
        return ToolResult(call.callId, out.joinToString("\n").ifEmpty { "(no matches)" })
    }

    companion object {
        const val NAME = "grep"
        private const val MAX_MATCHES = 300
        private const val RIPGREP = "rg"
        private const val RG_TIMEOUT_SECONDS = 60L

        /** Whether an `rg` binary is resolvable on `PATH` (probed once). Drives the default of [preferRipgrep]. */
        val RIPGREP_AVAILABLE: Boolean by lazy {
            try {
                val probe = ProcessBuilder(RIPGREP, "--version").redirectErrorStream(true).start()
                probe.outputStream.close()
                probe.inputStream.readBytes()
                probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
