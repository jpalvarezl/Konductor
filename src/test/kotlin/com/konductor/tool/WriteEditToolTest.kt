package com.konductor.tool

import com.konductor.core.models.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteEditToolTest {
    // Build argument JSON via the model so embedded quotes/newlines are escaped correctly.
    private fun args(vararg pairs: Pair<String, String>): String =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }).toString()

    @Test
    fun `write creates a file and its parent directories`(@TempDir dir: Path) {
        runBlocking {
            val result = WriteTool().execute(
                ToolCall("c", "write", args("path" to "sub/new.txt", "content" to "hello\nworld")),
                ToolContext(dir),
            )

            assertFalse(result.isError)
            assertTrue(dir.resolve("sub/new.txt").exists())
            assertEquals("hello\nworld", dir.resolve("sub/new.txt").readText())
        }
    }

    @Test
    fun `edit replaces a unique occurrence`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("f.kt").writeText("val a = 1\nval b = 2\n")

            val result = EditTool().execute(
                ToolCall("c", "edit", args("path" to "f.kt", "oldString" to "val b = 2", "newString" to "val b = 3")),
                ToolContext(dir),
            )

            assertFalse(result.isError)
            assertEquals("val a = 1\nval b = 3\n", dir.resolve("f.kt").readText())
        }
    }

    @Test
    fun `edit refuses a non-unique occurrence`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("f.kt").writeText("x\nx\n")

            val result = EditTool().execute(
                ToolCall("c", "edit", args("path" to "f.kt", "oldString" to "x", "newString" to "y")),
                ToolContext(dir),
            )

            assertTrue(result.isError)
            assertTrue(result.output.contains("not unique"))
            assertEquals("x\nx\n", dir.resolve("f.kt").readText()) // unchanged
        }
    }

    @Test
    fun `edit reports a missing occurrence`(@TempDir dir: Path) {
        runBlocking {
            dir.resolve("f.kt").writeText("hello")

            val result = EditTool().execute(
                ToolCall("c", "edit", args("path" to "f.kt", "oldString" to "absent", "newString" to "z")),
                ToolContext(dir),
            )

            assertTrue(result.isError)
            assertTrue(result.output.contains("not found"))
        }
    }
}
