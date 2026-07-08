package com.konductor.tool

import com.konductor.core.models.ToolResult
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolSupportTest {
    @Test
    fun `resolveInCwd rejects parent-directory escapes`(@TempDir dir: Path) {
        assertFailsWith<IllegalArgumentException> { resolveInCwd(dir, "../secret") }
    }

    @Test
    fun `resolveInCwd allows paths inside cwd`(@TempDir dir: Path) {
        assertTrue(resolveInCwd(dir, "sub/file.txt").startsWith(dir.toRealPath()))
    }

    @Test
    fun `resolveInCwd rejects escape through a symlinked directory`(@TempDir dir: Path, @TempDir outside: Path) {
        outside.resolve("secret.txt").writeText("top secret")
        // Creating symlinks needs privileges on Windows; skip the check where the OS won't allow it.
        try {
            Files.createSymbolicLink(dir.resolve("link"), outside)
        } catch (e: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${e.message}")
        }
        assertFailsWith<IllegalArgumentException> { resolveInCwd(dir, "link/secret.txt") }
    }

    @Test
    fun `decodeUtf8OrNull returns text for valid and null for invalid UTF-8`() {
        assertEquals("héllo", decodeUtf8OrNull("héllo".toByteArray(Charsets.UTF_8)))
        assertNull(decodeUtf8OrNull(byteArrayOf(0xC3.toByte(), 0x28))) // 0xC3 with a non-continuation byte
    }

    @Test
    fun `truncateToolResult caps the total output including the marker`() {
        val truncated = truncateToolResult(ToolResult("c", "x".repeat(5_000)), maxBytes = 1_000)

        assertTrue(truncated.output.toByteArray(Charsets.UTF_8).size <= 1_000, "output exceeded maxBytes")
        assertTrue(truncated.output.contains("[output truncated"))
        assertTrue(truncated.truncatedBytes > 0)
    }
}
