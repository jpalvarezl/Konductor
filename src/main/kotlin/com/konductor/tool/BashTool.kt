package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `bash` — run a shell command in the working directory, capturing combined stdout+stderr and the exit code
 * under a wall-clock timeout. Uses the platform shell (`cmd.exe /c` on Windows, `sh -c` elsewhere); the
 * environment header tells the model which OS it is on. This tool can do anything, so it is treated as
 * workspace-mutating and is disabled in read-only mode. See docs/spec/tools.md.
 */
class BashTool : Tool {
    override val spec = ToolSpec(
        name = "bash",
        description = "Run a shell command in the working directory; captures stdout+stderr and the exit code.",
        parameters = objectSchema(
            required = listOf("command"),
            properties = mapOf(
                "command" to prop("string", "Shell command to run (cmd.exe on Windows, sh elsewhere)."),
                "timeout" to prop("integer", "Wall-clock timeout in seconds (default $DEFAULT_TIMEOUT, max $MAX_TIMEOUT)."),
            ),
        ),
    )

    @Serializable
    private data class Args(val command: String, val timeout: Int? = null)

    override suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val args = toolJson.decodeFromString<Args>(call.argumentsJson)
        val timeoutSeconds = (args.timeout ?: DEFAULT_TIMEOUT).coerceIn(1, MAX_TIMEOUT)

        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val shell = if (isWindows) listOf("cmd.exe", "/c", args.command) else listOf("sh", "-c", args.command)

        val process = ProcessBuilder(shell)
            .directory(ctx.cwd.toFile())
            .redirectErrorStream(true)
            .start()
        process.outputStream.close() // the command gets no stdin

        val output = StringBuilder()
        val pump = thread(start = true, isDaemon = true, name = "bash-tool-output") {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (output.length < OUTPUT_CHAR_CAP) output.appendLine(line)
            }
        }

        if (!process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            pump.join(1_000)
            ToolResult(
                callId = call.callId,
                output = "bash: timed out after ${timeoutSeconds}s (partial output below)\n$output".trimEnd(),
                isError = true,
            )
        } else {
            pump.join(2_000)
            val exit = process.exitValue()
            ToolResult(call.callId, "exit code: $exit\n$output".trimEnd(), isError = exit != 0)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT = 120
        const val MAX_TIMEOUT = 600
        const val OUTPUT_CHAR_CAP = 200_000
    }
}
