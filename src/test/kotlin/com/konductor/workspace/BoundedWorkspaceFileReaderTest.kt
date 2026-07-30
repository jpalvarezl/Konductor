package com.konductor.workspace

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedWorkspaceFileReaderTest {
    private val reader = BoundedWorkspaceFileReader()

    @Test
    fun `reads exact limit and rejects one byte over without truncating`(@TempDir root: Path) {
        val exact = Files.write(root.resolve("exact"), ByteArray(64) { it.toByte() })
        val oversized = Files.write(root.resolve("oversized"), ByteArray(65))

        val result = reader.read(exact, root, 64)

        assertEquals(exact.toRealPath(), result.canonicalPath)
        assertContentEquals(Files.readAllBytes(exact), result.bytes)
        assertFailsWith<WorkspaceFileReadException> { reader.read(oversized, root, 64) }
    }

    @Test
    fun `zero-byte budget accepts only an empty file`(@TempDir root: Path) {
        val empty = Files.createFile(root.resolve("empty"))
        val nonEmpty = Files.writeString(root.resolve("non-empty"), "x")

        assertContentEquals(byteArrayOf(), reader.read(empty, root, 0).bytes)
        assertFailsWith<WorkspaceFileReadException> { reader.read(nonEmpty, root, 0) }
    }

    @Test
    fun `contained symlink reads canonical target and escaping symlink fails`(
        @TempDir root: Path,
        @TempDir outside: Path,
    ) {
        val target = Files.writeString(root.resolve("target"), "inside")
        val outsideTarget = Files.writeString(outside.resolve("secret"), "outside")
        try {
            Files.createSymbolicLink(root.resolve("alias"), target)
            Files.createSymbolicLink(root.resolve("escape"), outsideTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        val result = reader.read(root.resolve("alias"), root, 100)

        assertEquals(target.toRealPath(), result.canonicalPath)
        assertContentEquals("inside".toByteArray(), result.bytes)
        assertFailsWith<WorkspaceFileReadException> { reader.read(root.resolve("escape"), root, 100) }
    }

    @Test
    fun `dangling link directory and missing entries are selected-file failures`(@TempDir root: Path) {
        val directory = Files.createDirectory(root.resolve("directory"))
        assertFailsWith<WorkspaceFileReadException> { reader.read(directory, root, 100) }
        assertFailsWith<WorkspaceFileReadException> { reader.read(root.resolve("missing"), root, 100) }

        val dangling = root.resolve("dangling")
        try {
            Files.createSymbolicLink(dangling, root.resolve("absent-target"))
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }
        assertFailsWith<WorkspaceFileReadException> { reader.read(dangling, root, 100) }
    }

    @Test
    fun `expected canonical target detects a change between selection and read`(@TempDir root: Path) {
        val selected = Files.writeString(root.resolve("selected"), "content")
        val other = Files.writeString(root.resolve("other"), "other")

        assertFailsWith<WorkspaceFileReadException> {
            reader.read(selected, root, 100, expectedTarget = other.toRealPath())
        }
    }
}
