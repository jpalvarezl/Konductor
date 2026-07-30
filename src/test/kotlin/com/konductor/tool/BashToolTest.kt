package com.konductor.tool

import com.konductor.core.models.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BashToolTest {
    @Test
    fun `normal output and exit behavior is unchanged`(@TempDir dir: Path) {
        runBlocking {
            val success = BashTool().execute(
                call(helperCommand("normal", "normal-output", "0"), timeoutSeconds = 10),
                ToolContext(dir),
            )
            val failure = BashTool().execute(
                call(helperCommand("normal", "failure-output", "7"), timeoutSeconds = 10),
                ToolContext(dir),
            )

            assertFalse(success.isError, success.output)
            assertEquals("exit code: 0\nnormal-output", success.output)
            assertTrue(failure.isError, failure.output)
            assertEquals("exit code: 7\nfailure-output", failure.output)
        }
    }

    @Test
    fun `multiline CRLF and CR output is normalized across fixed-size chunks`(@TempDir dir: Path) {
        runBlocking {
            val result = BashTool().execute(
                call(helperCommand("line-endings"), timeoutSeconds = 10),
                ToolContext(dir),
            )

            val boundaryLine = "a".repeat(BashToolProcessHelper.CRLF_BOUNDARY_PREFIX_CHARS)
            assertFalse(result.isError, result.output)
            assertEquals(
                "exit code: 0\n$boundaryLine\ncr-line\nlf-line\nunterminated",
                result.output,
            )
        }
    }

    @Test
    fun `timeout caps a no-newline stream and terminates the observable process tree`(@TempDir dir: Path) {
        runBlocking {
            val handshake = dir.resolve("timeout-pids.txt")
            val execution = async {
                BashTool().execute(
                    call(helperCommand("tree", handshake.toString()), timeoutSeconds = TIMEOUT_SECONDS),
                    ToolContext(dir),
                )
            }

            val pids = awaitHandshake(handshake, expectedPidCount = 2)
            val result = execution.await()

            val timeoutHeader = "bash: timed out after ${TIMEOUT_SECONDS}s (partial output below)\n"
            assertTrue(result.isError)
            assertTrue(result.output.startsWith(timeoutHeader))
            val partialOutput = result.output.removePrefix(timeoutHeader)
            assertEquals(OUTPUT_CHAR_CAP, partialOutput.length)
            assertTrue(partialOutput.all { it == 'x' })
            assertTrue(BashToolProcessHelper.NO_NEWLINE_OUTPUT_CHARS > partialOutput.length)
            awaitDead(pids)
        }
    }

    @Test
    fun `cancellation is prompt transparent and terminates descendants during process churn`(@TempDir dir: Path) {
        runBlocking {
            val handshake = dir.resolve("cancellation-pids.txt")
            val execution = async {
                BashTool().execute(
                    call(helperCommand("churn-tree", handshake.toString()), timeoutSeconds = 30),
                    ToolContext(dir),
                )
            }
            val pids = awaitHandshake(handshake, expectedPidCount = 3)
            val cancellation = CancellationException("cancel bash test")

            val elapsed = measureTimeMillis {
                execution.cancel(cancellation)
                val thrown = assertFailsWith<CancellationException> { execution.await() }
                assertEquals(cancellation.message, thrown.message)
            }

            assertTrue(elapsed < CANCELLATION_LIMIT_MILLIS, "cancellation cleanup took ${elapsed}ms")
            assertTrue(handshake.exists(), "side effects completed before cancellation are not rolled back")
            awaitDead(pids)
        }
    }

    private fun call(command: String, timeoutSeconds: Int): ToolCall = ToolCall(
        callId = "bash-call",
        name = BashTool.NAME,
        argumentsJson = buildJsonObject {
            put("command", command)
            put("timeout", timeoutSeconds)
        }.toString(),
    )

    private fun helperCommand(vararg args: String): String {
        val commandParts = listOf(
            javaExecutable(),
            "-cp",
            helperClasspath(),
            BashToolProcessHelper::class.java.name,
        ) + args
        val command = commandParts.joinToString(" ") { shellQuote(it) }
        // cmd.exe /c strips the first and last quote unless a quoted executable command gets an extra outer pair.
        return if (isWindows()) "\"$command\"" else command
    }

    private fun helperClasspath(): String = listOf(
        BashToolProcessHelper::class.java.protectionDomain.codeSource.location.toURI(),
        Unit::class.java.protectionDomain.codeSource.location.toURI(),
    ).joinToString(File.pathSeparator) { Path.of(it).toString() }

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (isWindows()) "java.exe" else "java",
    ).toString()

    private fun shellQuote(argument: String): String =
        if (isWindows()) {
            "\"${argument.replace("\"", "\\\"")}\""
        } else {
            "'${argument.replace("'", "'\"'\"'")}'"
        }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private suspend fun awaitHandshake(path: Path, expectedPidCount: Int): List<Long> =
        withTimeout(HANDSHAKE_TIMEOUT_MILLIS) {
            while (true) {
                readValidHandshake(path, expectedPidCount)?.let { return@withTimeout it }
                delay(POLL_MILLIS)
            }
            error("unreachable")
        }

    private fun readValidHandshake(path: Path, expectedPidCount: Int): List<Long>? {
        val contents = runCatching { path.readText() }.getOrNull() ?: return null
        val fields = contents.split(',')
        if (fields.size != expectedPidCount) return null
        val pids = fields.map { it.toLongOrNull() ?: return null }
        return pids.takeIf { values -> values.all { it > 0 } && values.distinct().size == expectedPidCount }
    }

    private suspend fun awaitDead(pids: List<Long>) = withTimeout(PROCESS_EXIT_TIMEOUT_MILLIS) {
        while (pids.any(::isAlive)) delay(POLL_MILLIS)
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)

    companion object {
        private const val POLL_MILLIS = 25L
        private const val TIMEOUT_SECONDS = 5
        private const val OUTPUT_CHAR_CAP = 200_000
        private const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        private const val PROCESS_EXIT_TIMEOUT_MILLIS = 5_000L
        private const val CANCELLATION_LIMIT_MILLIS = 4_000L
    }
}
