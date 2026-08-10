package com.konductor.agent

import com.konductor.workspace.WorkspaceFileReadException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContextFileLoaderTest {
    private val loader = ContextFileLoader()

    @Test
    fun `loads global then root-to-cwd with per-level fallback and normalized content`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val global = Files.write(
            config.resolve("CLAUDE.md"),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "global\r\nline\r".toByteArray(),
        )
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        val ignoredFallback = Files.writeString(root.resolve("CLAUDE.md"), "ignored")
        val rootAgents = Files.writeString(root.resolve("AGENTS.md"), "root")
        val cwd = Files.createDirectories(root.resolve("sub/deep"))
        val nestedFallback = Files.writeString(cwd.resolve("CLAUDE.md"), "nested\rbody")

        val records = loader.load(cwd, config)

        assertEquals(
            listOf(
                ContextFileRecord(ContextBlockRenderer.displayPath(global.toRealPath()), "global\nline\n"),
                ContextFileRecord(ContextBlockRenderer.displayPath(rootAgents.toRealPath()), "root"),
                ContextFileRecord(ContextBlockRenderer.displayPath(nestedFallback.toRealPath()), "nested\nbody"),
            ),
            records,
        )
        // Keep this fixture explicit: AGENTS.md wins without inspecting the fallback's contents.
        assertEquals("ignored", Files.readString(ignoredFallback))
    }

    @Test
    fun `empty selected files are retained and no candidates returns no records`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        val empty = Files.createFile(root.resolve("AGENTS.md"))

        assertEquals(
            listOf(ContextFileRecord(ContextBlockRenderer.displayPath(empty.toRealPath()), "")),
            loader.load(root, config),
        )
        Files.delete(empty)
        assertEquals(emptyList(), loader.load(root, config))
    }

    @Test
    fun `canonical target aliases are deduplicated at their first position`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        val cwd = Files.createDirectory(root.resolve("nested"))
        val target = Files.writeString(cwd.resolve("AGENTS.md"), "shared")
        try {
            Files.createSymbolicLink(root.resolve("AGENTS.md"), target)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        assertEquals(
            listOf(ContextFileRecord(ContextBlockRenderer.displayPath(target.toRealPath()), "shared")),
            loader.load(cwd, config),
        )
    }

    @Test
    fun `present invalid AGENTS does not fall back to CLAUDE`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        Files.createDirectory(root.resolve("AGENTS.md"))
        Files.writeString(root.resolve("CLAUDE.md"), "must not be used")

        assertFailsWith<WorkspaceFileReadException> { loader.load(root, config) }
    }

    @Test
    fun `strict UTF-8 rejects malformed selected content`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        Files.write(root.resolve("AGENTS.md"), byteArrayOf(0xC3.toByte(), 0x28))

        assertFailsWith<ContextFileLoadException> { loader.load(root, config) }
    }

    @Test
    fun `per-file byte limit is exact`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        val file = root.resolve("AGENTS.md")
        Files.write(file, ByteArray(ContextFileLoader.MAX_FILE_BYTES))
        assertEquals(1, loader.load(root, config).size)

        Files.write(file, ByteArray(ContextFileLoader.MAX_FILE_BYTES + 1))
        assertFailsWith<WorkspaceFileReadException> { loader.load(root, config) }
    }

    @Test
    fun `aggregate limit counts actual bytes and never truncates`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        var cwd = root
        repeat(5) { index ->
            if (index > 0) cwd = Files.createDirectory(cwd.resolve("d$index"))
            Files.write(cwd.resolve("AGENTS.md"), ByteArray(ContextFileLoader.MAX_FILE_BYTES))
        }

        assertFailsWith<ContextFileLoadException> { loader.load(cwd, config) }
    }

    @Test
    fun `no context switch bypasses all discovery and path validation`() {
        assertEquals(
            emptyList(),
            loader.load(Path.of("missing-cwd"), Path.of("missing-config"), includeContextFiles = false),
        )
    }

    @Test
    fun `deduplicated file count is bounded to 32`(@TempDir temp: Path) {
        val config = Files.createDirectory(temp.resolve("config"))
        val root = Files.createDirectory(temp.resolve("workspace"))
        Files.createDirectory(root.resolve(".git"))
        var cwd = root
        Files.writeString(cwd.resolve("AGENTS.md"), "0")
        repeat(ContextFileLoader.MAX_FILES) { index ->
            cwd = Files.createDirectory(cwd.resolve("d$index"))
            Files.writeString(cwd.resolve("AGENTS.md"), index.toString())
        }

        assertFailsWith<ContextFileLoadException> { loader.load(cwd, config) }
    }
}
