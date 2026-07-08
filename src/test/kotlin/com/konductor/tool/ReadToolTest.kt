package com.konductor.tool

import com.konductor.core.models.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadToolTest {
    private fun call(argumentsJson: String) = ToolCall("call-1", "read", argumentsJson)

    @Test
    fun `numbers lines from 1`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("a.txt").writeText("one\ntwo\nthree")

            val result = ReadTool().execute(call("""{"path":"a.txt"}"""), ToolContext(dir))

            assertFalse(result.isError)
            assertEquals(
                listOf("     1\tone", "     2\ttwo", "     3\tthree"),
                result.output.split("\n"),
            )
        }
    }

    @Test
    fun `offset and limit page the file`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("a.txt").writeText("one\ntwo\nthree\nfour")

            val result = ReadTool().execute(call("""{"path":"a.txt","offset":2,"limit":2}"""), ToolContext(dir))

            assertEquals(listOf("     2\ttwo", "     3\tthree"), result.output.split("\n"))
        }
    }

    @Test
    fun `missing file is an error result`(@TempDir dir: Path) {
        runBlocking {
            val result = ReadTool().execute(call("""{"path":"nope.txt"}"""), ToolContext(dir))

            assertTrue(result.isError)
            assertTrue(result.output.contains("no such file"))
        }
    }

    @Test
    fun `binary file is refused`(@TempDir dir: Path) {
        runBlocking {
            java.nio.file.Files.write(dir.resolve("bin"), byteArrayOf(1, 2, 0, 3))

            val result = ReadTool().execute(call("""{"path":"bin"}"""), ToolContext(dir))

            assertTrue(result.isError)
            assertTrue(result.output.contains("binary"))
        }
    }
}
