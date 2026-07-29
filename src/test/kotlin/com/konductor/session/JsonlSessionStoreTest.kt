package com.konductor.session

import com.konductor.core.models.UserEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class JsonlSessionStoreTest {
    private val distantPast = Instant.parse("2000-01-01T00:00:00Z")
    private val distantFuture = Instant.parse("2999-01-01T00:00:00Z")

    private fun entry(text: String, at: Instant) = UserEntry(Uuid.random(), null, at, text)

    @Test
    fun `create then append persists and survives a fresh store instance`(@TempDir root: Path) {
        val cwd = root.resolve("proj")
        val session = JsonlSessionStore(root).create(cwd, "gpt-5", "demo")
        JsonlSessionStore(root).append(session, entry("hi", distantPast))

        val loaded = JsonlSessionStore(root).load(session.id)
        assertEquals(session.id, loaded.id)
        assertEquals("demo", loaded.name)
        assertEquals("gpt-5", loaded.modelName)
        assertEquals(1, loaded.entries.size)
        assertEquals("hi", (loaded.entries[0] as UserEntry).text)
    }

    @Test
    fun `listForCwd is most-recently-updated first and isolates directories`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwdA = root.resolve("a")
        val cwdB = root.resolve("b")

        val older = store.create(cwdA, "m", "older")
        store.append(older, entry("x", distantPast))
        val newer = store.create(cwdA, "m", "newer")
        store.append(newer, entry("y", distantFuture))
        store.create(cwdB, "m", "b-only")

        val listA = store.listForCwd(cwdA)
        assertEquals(2, listA.size)
        assertEquals(newer.id, listA[0].id) // updated in the distant future -> first
        assertEquals(older.id, listA[1].id)
        assertEquals(1, listA[0].entryCount)

        assertEquals(1, store.listForCwd(cwdB).size)
        assertEquals(0, store.listForCwd(root.resolve("never-used")).size)
    }

    @Test
    fun `mostRecentForCwd returns the latest session`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("p")
        store.create(cwd, "m", "first").also { store.append(it, entry("x", distantPast)) }
        val latest = store.create(cwd, "m", "second").also { store.append(it, entry("y", distantFuture)) }

        assertEquals(latest.id, store.mostRecentForCwd(cwd)?.id)
        assertNull(store.mostRecentForCwd(root.resolve("empty")))
    }

    @Test
    fun renamePersistsEntries(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), "m", null)
        store.append(session, entry("kept", distantPast))

        store.rename(session, "renamed")

        val loaded = store.load(session.id)
        assertEquals("renamed", session.name)
        assertEquals("renamed", loaded.name)
        assertEquals(1, loaded.entries.size)
        assertEquals("kept", (loaded.entries[0] as UserEntry).text)
    }

    @Test
    fun successfulUpdatePreservesTranscriptBytes(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), "old-model", "old-name")
        store.append(session, entry("first", distantPast))
        store.append(session, entry("second", distantFuture))
        val file = store.locate(session)
        val original = Files.readAllBytes(file)
        val originalTranscript = transcriptBytes(original)
        val candidate = session.metadata.copy(
            name = "new-name",
            modelName = "new-model",
            promptAgentName = "new-agent",
        )

        store.persistMetadata(session, candidate)

        assertEquals("old-name", session.name, "persistence alone must not mutate the live session")
        assertEquals("old-model", session.modelName)
        assertNull(session.promptAgentName)
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(candidate, restarted.metadata)
        assertContentEquals(originalTranscript, transcriptBytes(Files.readAllBytes(file)))
        assertEquals(listOf("first", "second"), restarted.entries.filterIsInstance<UserEntry>().map { it.text })
    }

    @Test
    fun failureBeforeTempWriteKeepsOriginal(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.create(root.resolve("p"), "old", null)
        baseline.append(session, entry("kept", distantPast))
        val file = baseline.locate(session)
        val original = Files.readAllBytes(file)
        var writeCalled = false
        val operations = MockSessionFileOperations(
            create = { error("cannot create temp") },
            write = { _, _, _ -> writeCalled = true },
        )

        assertFailsWith<IllegalStateException> {
            JsonlSessionStore(root, operations).persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertFalse(writeCalled)
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun tempWriteFailureKeepsOriginal(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.create(root.resolve("p"), "old", null)
        baseline.append(session, entry("kept", distantPast))
        val file = baseline.locate(session)
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(
            write = { _, temporary, _ ->
                Files.writeString(temporary, "partial candidate")
                error("disk full")
            },
        )

        assertFailsWith<IllegalStateException> {
            JsonlSessionStore(root, operations).persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun replacementFailureCleansTemp(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.create(root.resolve("p"), "old", null)
        baseline.append(session, entry("kept", distantPast))
        val file = baseline.locate(session)
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(replace = { _, _ -> error("replace denied") })

        assertFailsWith<IllegalStateException> {
            JsonlSessionStore(root, operations).persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun restartSeesCompleteOldOrNewState(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.create(root.resolve("p"), "old", "accepted")
        baseline.append(session, entry("first", distantPast))
        baseline.append(session, entry("second", distantFuture))
        val candidate = session.metadata.copy(name = "candidate", modelName = "new")
        val failing = JsonlSessionStore(
            root,
            MockSessionFileOperations(replace = { _, _ -> error("crash before replace") }),
        )

        assertFailsWith<IllegalStateException> { failing.persistMetadata(session, candidate) }
        val oldRestart = JsonlSessionStore(root).load(session.id)
        assertEquals(session.metadata, oldRestart.metadata)
        assertEquals(listOf("first", "second"), oldRestart.entries.filterIsInstance<UserEntry>().map { it.text })

        JsonlSessionStore(root).persistMetadata(session, candidate)
        val newRestart = JsonlSessionStore(root).load(session.id)
        assertEquals(candidate, newRestart.metadata)
        assertEquals(listOf("first", "second"), newRestart.entries.filterIsInstance<UserEntry>().map { it.text })
    }

    @Test
    fun `locate points at the on-disk file`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), "m", null)
        val located = store.locate(session)
        assertTrue(located.toString().endsWith("${session.id}.jsonl"))
        assertTrue(located.startsWith(root))
    }

    @Test
    fun `load throws for an unknown id`(@TempDir root: Path) {
        assertFailsWith<NoSuchElementException> { JsonlSessionStore(root).load(Uuid.random()) }
    }

    private fun transcriptBytes(fileBytes: ByteArray): ByteArray {
        val headerEnd = fileBytes.indexOf('\n'.code.toByte())
        require(headerEnd >= 0)
        return fileBytes.copyOfRange(headerEnd + 1, fileBytes.size)
    }

    private fun temporaryFiles(file: Path): List<Path> =
        Files.list(file.parent).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
}

private class MockSessionFileOperations(
    private val create: (Path) -> Path = NioSessionFileOperations::createSiblingTemp,
    private val write: (Path, Path, String) -> Unit = NioSessionFileOperations::writeCandidate,
    private val replace: (Path, Path) -> Unit = NioSessionFileOperations::replace,
    private val delete: (Path) -> Unit = NioSessionFileOperations::deleteIfExists,
) : SessionFileOperations {
    override fun createSiblingTemp(target: Path): Path = create(target)

    override fun writeCandidate(source: Path, temporary: Path, candidateHeader: String) =
        write(source, temporary, candidateHeader)

    override fun replace(temporary: Path, target: Path) = replace.invoke(temporary, target)

    override fun deleteIfExists(path: Path) = delete(path)
}
