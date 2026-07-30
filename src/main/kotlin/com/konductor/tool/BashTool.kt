package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
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
        name = NAME,
        description = "Run a shell command in the working directory; captures stdout+stderr and the exit code.",
        parameters = objectSchema(
            required = listOf("command"),
            properties = mapOf(
                "command" to prop("string", "Shell command to run (cmd.exe on Windows, sh elsewhere)."),
                "timeout" to prop(
                    "integer",
                    "Wall-clock timeout in seconds (default $DEFAULT_TIMEOUT, max $MAX_TIMEOUT).",
                ),
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

        // Thread-safe: the pump thread appends while the main thread may read a (partial) snapshot on timeout.
        // StringBuffer synchronizes each append/toString, so there is no data race or corrupted output.
        val output = StringBuffer()
        val pump = thread(start = true, isDaemon = true, name = "bash-tool-output") {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(OUTPUT_PUMP_CHARS)
                    var skipLfAfterCr = false
                    var unterminatedLine = false

                    fun capture(character: Char) {
                        if (output.length < OUTPUT_CHAR_CAP) output.append(character)
                    }

                    while (true) {
                        val count = reader.read(chunk)
                        if (count < 0) break

                        for (index in 0 until count) {
                            when (val character = chunk[index]) {
                                '\r' -> {
                                    capture('\n')
                                    skipLfAfterCr = true
                                    unterminatedLine = false
                                }

                                '\n' -> {
                                    if (!skipLfAfterCr) capture('\n')
                                    skipLfAfterCr = false
                                    unterminatedLine = false
                                }

                                else -> {
                                    capture(character)
                                    skipLfAfterCr = false
                                    unterminatedLine = true
                                }
                            }
                        }
                        // Keep draining and normalizing after the cap so producers cannot fill the pipe and block.
                    }
                    // BufferedReader.forEachLine appended LF after a final unterminated line; preserve that behavior.
                    if (unterminatedLine) capture('\n')
                }
            } catch (_: IOException) {
                // Expected when timeout/cancellation cleanup closes the stream to unblock this pump.
            }
        }
        var cleanedUp = false

        suspend fun cleanUp() {
            if (cleanedUp) return
            withContext(NonCancellable + Dispatchers.IO) {
                terminateTreeAndCloseStreams(process, pump)
            }
            cleanedUp = true
        }

        try {
            val completed = runInterruptible {
                process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            }
            if (!completed) {
                cleanUp()
                // Cancellation that raced with timeout cleanup still wins over producing a completed tool result.
                currentCoroutineContext().ensureActive()
                ToolResult(
                    callId = call.callId,
                    output = "bash: timed out after ${timeoutSeconds}s (partial output below)\n$output".trimEnd(),
                    isError = true,
                )
            } else {
                runInterruptible { pump.join(NORMAL_PUMP_JOIN_MILLIS) }
                val exit = process.exitValue()
                ToolResult(call.callId, "exit code: $exit\n$output".trimEnd(), isError = exit != 0)
            }
        } catch (cancellation: CancellationException) {
            try {
                cleanUp()
            } catch (cleanupFailure: Throwable) {
                // Cleanup failures must never turn coroutine cancellation into a tool failure result.
                cancellation.addSuppressed(cleanupFailure)
            }
            throw cancellation
        } finally {
            closeProcessStreams(process)
        }
    }

    private fun terminateTreeAndCloseStreams(process: Process, pump: Thread) {
        val root = process.toHandle()
        // ProcessHandle only exposes descendants that are still attached and observable at snapshot time.
        val descendants = observableDescendantsDeepestFirst(root)

        requestTermination(descendants, root, force = false)
        waitForExit(descendants, root, GRACEFUL_WAIT_MILLIS)
        requestTermination(descendants, root, force = true)
        waitForExit(descendants, root, FORCE_WAIT_MILLIS)

        closeProcessStreams(process)
        pump.joinBoundedly(OUTPUT_PUMP_JOIN_MILLIS)
    }

    private fun observableDescendantsDeepestFirst(root: ProcessHandle): List<ProcessHandle> {
        val rootPid = root.pid()
        return try {
            root.descendants().use { stream -> stream.toList() }
                .map { handle ->
                    ObservableProcess(
                        handle = handle,
                        pid = handle.pid(),
                        depth = handle.depthBelow(rootPid),
                    )
                }
                .sortedWith(compareByDescending<ObservableProcess> { it.depth }.thenByDescending { it.pid })
                .map(ObservableProcess::handle)
        } catch (_: RuntimeException) {
            // Descendants can exit or be reparented while the snapshot is ordered. Root cleanup remains mandatory.
            emptyList()
        }
    }

    private fun ProcessHandle.depthBelow(rootPid: Long): Int {
        var depth = 0
        var current = this
        try {
            repeat(MAX_PROCESS_TREE_DEPTH) {
                val parent = current.parent().orElse(null) ?: return depth
                depth++
                if (parent.pid() == rootPid) return depth
                current = parent
            }
        } catch (_: RuntimeException) {
            // Keep the depth observed before this process exited, was reparented, or became inaccessible.
        }
        return depth
    }

    private fun requestTermination(descendants: List<ProcessHandle>, root: ProcessHandle, force: Boolean) {
        try {
            descendants.forEach { it.tryDestroy(force) }
        } finally {
            // Keep root separate from descendant discovery/ordering so fallback cleanup cannot omit the shell.
            root.tryDestroy(force)
        }
    }

    private fun ProcessHandle.tryDestroy(force: Boolean) {
        try {
            if (!isAlive) return
            if (force) destroyForcibly() else destroy()
        } catch (_: RuntimeException) {
            // Cleanup is best effort when a handle exits concurrently or the JVM cannot terminate it.
        }
    }

    private fun waitForExit(descendants: List<ProcessHandle>, root: ProcessHandle, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (root.isAlive || descendants.any { it.isAlive }) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return
            try {
                Thread.sleep(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                        .coerceIn(1, PROCESS_POLL_MILLIS),
                )
            } catch (_: InterruptedException) {
                // runInterruptible cancellation may leave an interrupt racing with NonCancellable cleanup.
            }
        }
    }

    private data class ObservableProcess(
        val handle: ProcessHandle,
        val pid: Long,
        val depth: Int,
    )

    private fun closeProcessStreams(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun Thread.joinBoundedly(timeoutMillis: Long) {
        try {
            join(timeoutMillis)
        } catch (_: InterruptedException) {
            // Cleanup remains bounded even if its dispatcher thread is interrupted.
        }
    }

    companion object {
        const val NAME = "bash"
        private const val DEFAULT_TIMEOUT = 120
        private const val MAX_TIMEOUT = 600
        private const val OUTPUT_CHAR_CAP = 200_000
        private const val OUTPUT_PUMP_CHARS = 8_192
        private const val GRACEFUL_WAIT_MILLIS = 500L
        private const val FORCE_WAIT_MILLIS = 500L
        private const val OUTPUT_PUMP_JOIN_MILLIS = 1_000L
        private const val NORMAL_PUMP_JOIN_MILLIS = 2_000L
        private const val PROCESS_POLL_MILLIS = 25L
        private const val MAX_PROCESS_TREE_DEPTH = 1_024
    }
}
