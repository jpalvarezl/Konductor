package com.konductor.tool

import com.konductor.core.models.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LsFindGrepToolTest {
    // grep is pinned to the portable in-process path so tests are deterministic regardless of whether `rg`
    // happens to be on PATH; a separate guarded test covers the ripgrep path.
    private fun grep() = GrepTool(preferRipgrep = false)

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
    fun `find matches top-level files with a doublestar prefix`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("Root.kt").writeText("x") // top-level: Java NIO glob **/ would miss this without the fix

            val result = FindTool().execute(ToolCall("c", "find", """{"pattern":"**/*.kt"}"""), ToolContext(dir))

            assertTrue(result.output.contains("Root.kt"), "expected top-level Root.kt in: ${result.output}")
        }
    }

    @Test
    fun `find prunes ignored directories`(@TempDir dir: Path) {
        runBlocking {
            Files.createDirectories(dir.resolve("src"))
            Files.createDirectories(dir.resolve("node_modules/pkg"))
            dir.resolve("src/Main.kt").writeText("x")
            dir.resolve("node_modules/pkg/Dep.kt").writeText("x")

            val result = FindTool().execute(ToolCall("c", "find", """{"pattern":"**/*.kt"}"""), ToolContext(dir))

            assertTrue(result.output.contains("Main.kt"))
            assertFalse(result.output.contains("node_modules"), "node_modules must be pruned: ${result.output}")
        }
    }

    @Test
    fun `grep returns path line-number and line for each match`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("a.kt").writeText("fun main() {}\nval x = 1\n")

            val result = grep().execute(ToolCall("c", "grep", """{"pattern":"fun "}"""), ToolContext(dir))

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

            val result = grep().execute(
                ToolCall("c", "grep", """{"pattern":"needle","glob":"**/*.kt"}"""),
                ToolContext(dir),
            )

            assertFalse(result.isError)
            assertTrue(result.output.contains("a.kt:1:needle here"), "got: ${result.output}")
            assertFalse(result.output.contains("notes.md"))
        }
    }

    @Test
    fun `grep prunes ignored directories`(@TempDir dir: Path) {
        runBlocking {
            Files.createDirectories(dir.resolve("src"))
            Files.createDirectories(dir.resolve("target"))
            dir.resolve("src/a.kt").writeText("needle here")
            dir.resolve("target/generated.kt").writeText("needle in build output")

            val result = grep().execute(ToolCall("c", "grep", """{"pattern":"needle"}"""), ToolContext(dir))

            assertFalse(result.isError)
            assertTrue(result.output.contains("a.kt:1:needle here"))
            assertFalse(result.output.contains("target"), "build output must be pruned: ${result.output}")
        }
    }

    @Test
    fun `grep reports an invalid regex`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("a.kt").writeText("x")

            val result = grep().execute(ToolCall("c", "grep", """{"pattern":"[unterminated"}"""), ToolContext(dir))

            assertTrue(result.isError)
            assertTrue(result.output.contains("invalid regex"))
        }
    }

    @Test
    fun `grep skips non-UTF-8 files`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("good.txt").writeText("needle here")
            // Invalid UTF-8 bytes followed by the search term: the file must be skipped, not matched as garbage.
            Files.write(dir.resolve("bad.bin"), byteArrayOf(0xC3.toByte(), 0x28) + "needle".toByteArray())

            val result = grep().execute(ToolCall("c", "grep", """{"pattern":"needle"}"""), ToolContext(dir))

            assertFalse(result.isError)
            assertTrue(result.output.contains("good.txt:1:needle here"))
            assertFalse(result.output.contains("bad.bin"))
        }
    }

    @Test
    fun `grep via ripgrep finds matches when rg is available`(@TempDir dir: Path) {
        assumeTrue(GrepTool.RIPGREP_AVAILABLE, "ripgrep not on PATH; skipping the rg-backed path")
        runBlocking {
            dir.resolve("a.kt").writeText("fun main() {}\n")

            val result = GrepTool(preferRipgrep = true).execute(
                ToolCall("c", "grep", """{"pattern":"fun "}"""),
                ToolContext(dir),
            )

            assertFalse(result.isError)
            assertTrue(result.output.contains("a.kt:1:fun main() {}"), "got: ${result.output}")
        }
    }
}
