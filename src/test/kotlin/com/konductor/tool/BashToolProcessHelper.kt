package com.konductor.tool

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Real JVM process used by [BashToolTest]; invoked through the same platform shell as production commands. */
object BashToolProcessHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "normal" -> {
                println(args[1])
                System.out.flush()
                System.exit(args[2].toInt())
            }

            "line-endings" -> writeMixedLineEndings()
            "tree" -> runTree(Path.of(args[1]), churn = false)
            "churn-tree" -> runTree(Path.of(args[1]), churn = true)
            "child" -> runChild(Path.of(args[1]))
            "churn" -> runChurn(Path.of(args[1]))
            else -> error("unknown helper mode: ${args.firstOrNull()}")
        }
    }

    private fun runTree(handshake: Path, churn: Boolean) {
        val childReady = handshake.resolveSibling("${handshake.fileName}.child-ready")
        val child = ProcessBuilder(javaCommand("child", childReady.toString()))
            .inheritIO()
            .start()
        val churnReady = handshake.resolveSibling("${handshake.fileName}.churn-ready")
        val churner = if (churn) {
            ProcessBuilder(javaCommand("churn", churnReady.toString()))
                .inheritIO()
                .start()
        } else {
            null
        }

        waitForPidHandshake(childReady)
        if (churner == null) {
            writeNoNewlineOutput()
        } else {
            waitForPidHandshake(churnReady)
        }
        val pids = listOfNotNull(ProcessHandle.current().pid(), child.pid(), churner?.pid())
        writeHandshake(handshake, pids.joinToString(","))
        sleepForever()
    }

    private fun runChild(ready: Path) {
        writeHandshake(ready, ProcessHandle.current().pid().toString())
        sleepForever()
    }

    private fun runChurn(ready: Path) {
        var first = true
        while (true) {
            val child = ProcessBuilder(briefProcessCommand())
                .inheritIO()
                .start()
            if (first) {
                writeHandshake(ready, ProcessHandle.current().pid().toString())
                first = false
            }
            child.waitFor()
        }
    }

    private fun writeMixedLineEndings() {
        val boundaryLine = "a".repeat(CRLF_BOUNDARY_PREFIX_CHARS)
        System.out.print("$boundaryLine\r\ncr-line\rlf-line\nunterminated")
        System.out.flush()
    }

    private fun writeNoNewlineOutput() {
        val chunk = ByteArray(OUTPUT_CHUNK_BYTES) { 'x'.code.toByte() }
        var remaining = NO_NEWLINE_OUTPUT_CHARS
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            System.out.write(chunk, 0, count)
            remaining -= count
        }
        System.out.flush()
    }

    private fun writeHandshake(path: Path, contents: String) {
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, contents)
            try {
                Files.move(temporary, path, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // This test fixture still publishes a complete temp file when the filesystem lacks atomic moves.
                Files.move(temporary, path)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun waitForPidHandshake(path: Path): Long {
        repeat(HANDSHAKE_POLLS) {
            val pid = runCatching { Files.readString(path).toLong() }.getOrNull()
            if (pid != null && pid > 0) return pid
            Thread.sleep(HANDSHAKE_POLL_MILLIS)
        }
        error("child did not publish a valid PID handshake: $path")
    }

    private fun sleepForever(): Nothing {
        while (true) Thread.sleep(60_000)
    }

    private fun javaCommand(vararg helperArgs: String): List<String> = listOf(
        javaExecutable(),
        "-cp",
        System.getProperty("java.class.path"),
        BashToolProcessHelper::class.java.name,
        *helperArgs,
    )

    private fun briefProcessCommand(): List<String> =
        if (isWindows()) listOf("cmd.exe", "/c", "exit", "0") else listOf("sh", "-c", ":")

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (isWindows()) "java.exe" else "java",
    ).toString()

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    const val NO_NEWLINE_OUTPUT_CHARS = 400_000
    const val CRLF_BOUNDARY_PREFIX_CHARS = 8_191
    private const val OUTPUT_CHUNK_BYTES = 8_192
    private const val HANDSHAKE_POLLS = 1_000
    private const val HANDSHAKE_POLL_MILLIS = 10L
}
