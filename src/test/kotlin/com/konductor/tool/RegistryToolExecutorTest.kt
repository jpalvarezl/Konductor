package com.konductor.tool

import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegistryToolExecutorTest {
    private val readOnly = setOf("read", "ls", "find", "grep")

    @Test
    fun `read-only mode refuses mutating tools and leaves the file untouched`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("f.txt").writeText("original")
            val executor = RegistryToolExecutor(BuiltinTools.registry(readOnly), ToolContext(dir))

            val write = executor.execute(ToolCall("c", "write", """{"path":"f.txt","content":"hacked"}"""))

            assertTrue(write.isError)
            assertTrue(write.output.contains("unknown or disabled tool: write"))
            assertEquals("original", dir.resolve("f.txt").readText())

            // A read-only tool still runs.
            val read = executor.execute(ToolCall("c2", "read", """{"path":"f.txt"}"""))
            assertFalse(read.isError)
        }
    }

    @Test
    fun `unknown tool is refused`(@TempDir dir: Path) {
        runBlocking {
            val executor = RegistryToolExecutor(BuiltinTools.registry(), ToolContext(dir))

            val result = executor.execute(ToolCall("c", "frobnicate", "{}"))

            assertTrue(result.isError)
            assertTrue(result.output.contains("unknown or disabled tool"))
        }
    }

    @Test
    fun `path escapes outside cwd are contained`(@TempDir dir: Path) {
        runBlocking {
            val executor = RegistryToolExecutor(BuiltinTools.registry(), ToolContext(dir))

            val result = executor.execute(ToolCall("c", "read", """{"path":"../secret"}"""))

            assertTrue(result.isError)
            assertTrue(result.output.contains("escapes the working directory"))
        }
    }

    @Test
    fun `output is capped and the call id is preserved`(@TempDir dir: Path) {
        runBlocking {
            val bigTool = object : Tool {
                override val spec = ToolSpec("big", "returns a lot", emptyMap())
                override suspend fun execute(call: ToolCall, ctx: ToolContext) =
                    ToolResult(call.callId, "x".repeat(20_000))
            }
            val executor = RegistryToolExecutor(ToolRegistry(listOf(bigTool)), ToolContext(dir), maxOutputBytes = 1_000)

            val result = executor.execute(ToolCall("keep-me", "big", "{}"))

            assertEquals("keep-me", result.callId)
            assertTrue(result.truncatedBytes > 0)
            assertTrue(result.output.contains("[output truncated"))
        }
    }
}
