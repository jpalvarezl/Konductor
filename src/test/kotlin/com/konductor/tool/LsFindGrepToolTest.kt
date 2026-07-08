package com.konductor.tool

import com.konductor.core.models.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LsFindGrepToolTest {
    @Test
    fun `ls lists directories first then files`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("z.txt").writeText("")
            dir.resolve("a.txt").writeText("")
            Files.createDirectory(dir.resolve("sub"))

            val result = LsTool().execute(ToolCall("c", "ls", "{}"), ToolContext(dir))

            assertEquals(listOf("[d] sub", "[f] a.txt", "[f] z.txt"), result.output.split("\n"))
        }
    }

    @Test
    fun `find globs relative to the working directory`(@TempDir dir: Path) {
        runBlocking {
            Files.createDirectories(dir.resolve("src"))
            dir.resolve("src/Main.kt").writeText("x")
            dir.resolve("README.md").writeText("x")

            val result = FindTool().execute(ToolCall("c", "find", """{"pattern":"**/*.kt"}"""), ToolContext(dir))

            assertFalse(result.isError)
            assertTrue(result.output.contains("Main.kt"), "expected Main.kt in: ${result.output}")
            assertFalse(result.output.contains("README"))
        }
    }

    @Test
    fun `grep returns path line-number and line for each match`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("a.kt").writeText("fun main() {}\nval x = 1\n")

            val result = GrepTool().execute(
                ToolCall("c", "grep", """{"pattern":"fun "}"""),
                ToolContext(dir),
            )

            assertFalse(result.isError)
            assertTrue(result.output.contains("a.kt:1:fun main() {}"), "got: ${result.output}")
        }
    }

    @Test
    fun `grep restricts the search with a glob`(@TempDir dir: Path) {
        runBlocking {
            Files.createDirectories(dir.resolve("src"))
            dir.resolve("src/a.kt").writeText("needle here")
            dir.resolve("notes.md").writeText("needle here too")

            val result = GrepTool().execute(
                ToolCall("c", "grep", """{"pattern":"needle","glob":"**/*.kt"}"""),
                ToolContext(dir),
            )

            assertFalse(result.isError)
            assertTrue(result.output.contains("a.kt:1:needle here"), "got: ${result.output}")
            assertFalse(result.output.contains("notes.md"))
        }
    }

    @Test
    fun `grep reports an invalid regex`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("a.kt").writeText("x")

            val result = GrepTool().execute(ToolCall("c", "grep", """{"pattern":"[unterminated"}"""), ToolContext(dir))

            assertTrue(result.isError)
            assertTrue(result.output.contains("invalid regex"))
        }
    }
}
