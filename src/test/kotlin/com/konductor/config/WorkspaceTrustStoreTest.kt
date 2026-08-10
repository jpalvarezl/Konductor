package com.konductor.config

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceTrustStoreTest {
    @Test
    fun `missing store is valid empty and persistence is strict atomic and keeps lock`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val store = WorkspaceTrustStore(config, workspace)

        val firstRead = store.read()
        val empty = assertIs<WorkspaceTrustSnapshot.Valid>(
            firstRead,
            (firstRead as? WorkspaceTrustSnapshot.Error)?.problem,
        )
        assertEquals(emptyMap(), empty.decisions)
        assertEquals(false, empty.storeExists)

        val saved = store.persist(empty, WorkspaceTrustDecision.Trusted)
        assertEquals(WorkspaceTrustDecision.Trusted, saved.decisionFor(workspace.toRealPath()))
        assertTrue(config.resolve(WorkspaceTrustStore.LOCK_FILE_NAME).exists())
        assertTrue(config.resolve(WorkspaceTrustStore.STORE_FILE_NAME).exists())
        assertEquals(
            emptyList(),
            config.listDirectoryEntries(".workspace-trust.*.tmp"),
            "forced candidates must not remain after publication",
        )
    }

    @Test
    fun `strict parser rejects invalid json documents`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val path = config.resolve(WorkspaceTrustStore.STORE_FILE_NAME)
        val store = WorkspaceTrustStore(config, workspace)
        val workspacePath = jsonPath(workspace.toRealPath())

        val invalid = listOf(
            """{"version":1,"version":1,"workspaces":{}}""",
            """{"version":1,"workspaces":{"$workspacePath":"trusted","$workspacePath":"untrusted"}}""",
            """{"version":1,"workspaces":{},"extra":true}""",
            """{"version":1,"workspaces":{}} {}""",
            """{"version":2,"workspaces":{}}""",
            """{"version":1.0,"workspaces":{}}""",
            """{"version":1}""",
            """{"workspaces":{}}""",
            """{"version":1,"workspaces":null}""",
            """{"version":1,"workspaces":{"relative":"trusted"}}""",
            """{"version":1,"workspaces":{"$workspacePath/..":"trusted"}}""",
            """{"version":1,"workspaces":{"${jsonPath(workspace.toRealPath())}":"maybe"}}""",
        )
        invalid.forEach { json ->
            path.writeText(json)
            assertIs<WorkspaceTrustSnapshot.Error>(store.read(), json)
        }
        path.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<WorkspaceTrustSnapshot.Error>(store.read())
    }

    @Test
    fun `writer rereads and merges unrelated updates but rejects opposite same workspace`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val other = root.resolve("other").createDirectories().toRealPath()
        val config = root.resolve("config").createDirectories()
        val path = config.resolve(WorkspaceTrustStore.STORE_FILE_NAME)
        val store = WorkspaceTrustStore(config, workspace)
        val accepted = assertIs<WorkspaceTrustSnapshot.Valid>(store.read())

        path.writeText(
            """{"version":1,"workspaces":{"${jsonPath(other)}":"untrusted"}}""",
        )
        val merged = store.persist(accepted, WorkspaceTrustDecision.Trusted)
        assertEquals(WorkspaceTrustDecision.Untrusted, merged.decisionFor(other))
        assertEquals(WorkspaceTrustDecision.Trusted, merged.decisionFor(workspace.toRealPath()))

        val stale = assertIs<WorkspaceTrustSnapshot.Valid>(WorkspaceTrustStore(config, workspace).run {
            Files.delete(path)
            read()
        })
        path.writeText(
            """{"version":1,"workspaces":{"${jsonPath(workspace.toRealPath())}":"untrusted"}}""",
        )
        assertFailsWith<WorkspaceTrustConflictException> {
            WorkspaceTrustStore(config, workspace).persist(stale, WorkspaceTrustDecision.Trusted)
        }
    }

    @Test
    fun `store byte limit accepts exact size and rejects one extra byte`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val path = config.resolve(WorkspaceTrustStore.STORE_FILE_NAME)
        val store = WorkspaceTrustStore(config, workspace)
        val valid = """{"version":1,"workspaces":{}}""".toByteArray()

        path.writeBytes(valid + ByteArray(WorkspaceTrustStore.MAX_STORE_BYTES - valid.size) { ' '.code.toByte() })
        assertIs<WorkspaceTrustSnapshot.Valid>(store.read())
        Files.write(path, byteArrayOf(' '.code.toByte()), StandardOpenOption.APPEND)
        assertIs<WorkspaceTrustSnapshot.Error>(store.read())
    }

    @Test
    fun `bounded no-follow read rejects oversized and redirected store entries`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val path = config.resolve(WorkspaceTrustStore.STORE_FILE_NAME)
        val store = WorkspaceTrustStore(config, workspace)

        path.writeBytes(ByteArray(WorkspaceTrustStore.MAX_STORE_BYTES + 1) { ' '.code.toByte() })
        assertIs<WorkspaceTrustSnapshot.Error>(store.read())

        Files.delete(path)
        val target = root.resolve("target.json").also { it.writeText("""{"version":1,"workspaces":{}}""") }
        runCatching { Files.createSymbolicLink(path, target) }.onSuccess {
            assertIs<WorkspaceTrustSnapshot.Error>(store.read())
        }
    }

    @Test
    fun `posix mode and hard-link boundary checks are enforced when available`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val posix = Files.getFileAttributeView(config, PosixFileAttributeView::class.java) ?: return
        val original = posix.readAttributes().permissions()
        Files.setPosixFilePermissions(config, original + PosixFilePermission.GROUP_WRITE)
        assertIs<WorkspaceTrustSnapshot.Error>(WorkspaceTrustStore(config, workspace).read())

        Files.setPosixFilePermissions(config, original - PosixFilePermission.GROUP_WRITE)
        val storePath = config.resolve(WorkspaceTrustStore.STORE_FILE_NAME)
        storePath.writeText("""{"version":1,"workspaces":{}}""")
        Files.createLink(config.resolve("trust-hard-link.json"), storePath)
        assertIs<WorkspaceTrustSnapshot.Error>(WorkspaceTrustStore(config, workspace).read())
    }

    @Test
    fun `config directory at or below workspace is rejected before store access`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = workspace.resolve(".konductor")
        val store = WorkspaceTrustStore(config, workspace)

        val error = assertIs<WorkspaceTrustSnapshot.Error>(store.read())
        assertTrue(error.problem.contains("outside workspace"))
        assertTrue(config.notExists(), "an unsafe in-workspace config directory must not be created")
    }

    @Test
    fun `config directory symlink is rejected before target access`(@TempDir root: Path) {
        val canonicalRoot = root.toRealPath()
        val workspace = canonicalRoot.resolve("workspace").createDirectories()
        val target = canonicalRoot.resolve("outside-target").createDirectories()
        val config = workspace.resolve("redirected-config")
        try {
            Files.createSymbolicLink(config, target)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        val error = assertIs<WorkspaceTrustSnapshot.Error>(WorkspaceTrustStore(config, workspace).read())
        assertTrue(error.problem.contains("literal directory"))
        assertTrue(target.resolve(WorkspaceTrustStore.STORE_FILE_NAME).notExists())
    }

    @Test
    fun `effective user linkage failure becomes a trust store error`(@TempDir root: Path) {
        val failure = assertFailsWith<WorkspaceTrustStoreException> {
            readEffectiveUserId(root) { throw UnsatisfiedLinkError("native lookup unavailable") }
        }

        assertIs<UnsatisfiedLinkError>(failure.cause)
        assertTrue(failure.message.orEmpty().contains("effective POSIX user"))
    }

    @Test
    fun `persistent lock acquisition is bounded`(@TempDir root: Path) {
        val workspace = root.resolve("workspace").createDirectories()
        val config = root.resolve("config").createDirectories()
        val initialStore = WorkspaceTrustStore(config, workspace)
        val accepted = assertIs<WorkspaceTrustSnapshot.Valid>(initialStore.read())
        val lockPath = config.resolve(WorkspaceTrustStore.LOCK_FILE_NAME)
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val contender = WorkspaceTrustStore(config, workspace, Duration.ofMillis(40))
                assertFailsWith<WorkspaceTrustLockTimeoutException> {
                    contender.persist(accepted, WorkspaceTrustDecision.Trusted)
                }
            }
        }
        assertTrue(Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
    }

    private fun jsonPath(path: Path): String = path.toString().replace("\\", "\\\\").replace("\"", "\\\"")
}
