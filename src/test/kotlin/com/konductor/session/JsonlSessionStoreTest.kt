package com.konductor.session

import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.UserEntry
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.listDirectoryEntries
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
    fun `candidate allocation is invisible and rejects durable operations until publication`(@TempDir root: Path) {
        val sessionRoot = root.resolve("sessions")
        val store = JsonlSessionStore(sessionRoot)
        val cwd = root.resolve("workspace")
        val candidate = store.newCandidate(cwd, "gpt-5", "provisional")

        assertFalse(Files.exists(sessionRoot), "allocation must not create the session root")
        assertNull(store.locate(candidate))
        assertTrue(store.listForCwd(cwd).isEmpty())
        assertFailsWith<NoSuchElementException> { store.loadHeader(candidate.id) }
        assertFailsWith<NoSuchElementException> { store.load(candidate.id) }
        assertFailsWith<IllegalArgumentException> { store.append(candidate, entry("no", distantPast)) }
        assertFailsWith<IllegalArgumentException> { store.persistMetadata(candidate, candidate.metadata) }
        assertFailsWith<IllegalArgumentException> { store.rewrite(candidate, emptyList()) }
        assertFalse(Files.exists(sessionRoot), "rejected operations must not bootstrap a durable session")
    }

    @Test
    fun `persistNew publishes one complete header and survives restart`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val cwd = root.resolve("workspace")
        val candidate = store.newCandidate(cwd, "gpt-5", "published")

        store.persistNew(candidate)

        assertEquals(candidate.header, store.loadHeader(candidate.id))
        assertEquals(candidate, JsonlSessionStore(root).load(candidate.id))
        assertEquals(listOf(candidate.id), JsonlSessionStore(root).listForCwd(cwd).map { it.id })
        val file = requireNotNull(store.locate(candidate))
        assertEquals(listOf(SessionCodec.encodeHeader(candidate)), Files.readAllLines(file))
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun `loadHeader ignores malformed transcript content`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val candidate = store.persistedCandidate(root.resolve("workspace"), "gpt-5", null)
        Files.writeString(requireNotNull(store.locate(candidate)), "not-json\n", StandardOpenOption.APPEND)

        assertEquals(candidate.header, JsonlSessionStore(root).loadHeader(candidate.id))
        assertFailsWith<Exception> { JsonlSessionStore(root).load(candidate.id) }
    }

    @Test
    fun `load and loadHeader reject redirected session files`(@TempDir root: Path) {
        val sessionRoot = root.resolve("sessions")
        val store = JsonlSessionStore(sessionRoot)
        val candidate = store.persistedCandidate(root.resolve("workspace"), "gpt-5", null)
        val sessionFile = requireNotNull(store.locate(candidate))
        val redirectedTarget = Files.write(root.resolve("redirected.jsonl"), Files.readAllBytes(sessionFile))
        Files.delete(sessionFile)
        try {
            Files.createSymbolicLink(sessionFile, redirectedTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        assertFailsWith<NoSuchElementException> { store.loadHeader(candidate.id) }
        assertFailsWith<NoSuchElementException> { store.load(candidate.id) }
        assertTrue(store.listForCwd(candidate.cwd).isEmpty())
    }

    @Test
    fun `load and loadHeader reject redirected session directories`(@TempDir root: Path) {
        val sessionRoot = Files.createDirectory(root.resolve("sessions"))
        val redirectedTarget = Files.createDirectory(root.resolve("redirected-bucket"))
        val store = JsonlSessionStore(sessionRoot)
        val candidate = store.newCandidate(root.resolve("workspace"), "gpt-5", null)
        Files.writeString(redirectedTarget.resolve("${candidate.id}.jsonl"), SessionCodec.encodeHeader(candidate))
        try {
            Files.createSymbolicLink(sessionRoot.resolve("bucket-alias"), redirectedTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }

        assertFailsWith<NoSuchElementException> { store.loadHeader(candidate.id) }
        assertFailsWith<NoSuchElementException> { store.load(candidate.id) }
    }

    @Test
    fun `session reads reject a non-directory session root`(@TempDir root: Path) {
        val sessionRoot = Files.writeString(root.resolve("sessions"), "not a directory")
        val store = JsonlSessionStore(sessionRoot)
        val candidate = store.newCandidate(root.resolve("workspace"), "gpt-5", null)

        assertFailsWith<NoSuchElementException> { store.loadHeader(candidate.id) }
        assertFailsWith<NoSuchElementException> { store.load(candidate.id) }
        assertTrue(store.listForCwd(candidate.cwd).isEmpty())
        assertNull(store.locate(candidate))
    }

    @Test
    fun `session operations reject a redirected session root`(@TempDir root: Path) {
        val targetRoot = root.resolve("target-sessions")
        val targetStore = JsonlSessionStore(targetRoot)
        val session = targetStore.persistedCandidate(root.resolve("workspace"), "gpt-5", null)
        val redirectedRoot = root.resolve("redirected-sessions")
        try {
            Files.createSymbolicLink(redirectedRoot, targetRoot)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }
        val redirectedStore = JsonlSessionStore(redirectedRoot)

        assertFailsWith<NoSuchElementException> { redirectedStore.loadHeader(session.id) }
        assertFailsWith<NoSuchElementException> { redirectedStore.load(session.id) }
        assertTrue(redirectedStore.listForCwd(session.cwd).isEmpty())
        assertNull(redirectedStore.locate(session))
        assertFailsWith<IllegalArgumentException> {
            redirectedStore.append(session, entry("blocked", distantPast))
        }
        assertFailsWith<IllegalArgumentException> {
            redirectedStore.persistNew(redirectedStore.newCandidate(session.cwd, "gpt-5", null))
        }
    }

    @Test
    fun `persistNew rejects a redirected session directory`(@TempDir root: Path) {
        val sessionRoot = root.resolve("sessions")
        val store = JsonlSessionStore(sessionRoot)
        val cwd = root.resolve("workspace")
        val seed = store.persistedCandidate(cwd, "gpt-5", null)
        val bucket = requireNotNull(store.locate(seed)).parent
        Files.delete(requireNotNull(store.locate(seed)))
        Files.delete(bucket)
        val redirectedTarget = Files.createDirectory(root.resolve("redirected-bucket"))
        try {
            Files.createSymbolicLink(bucket, redirectedTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }
        val candidate = store.newCandidate(cwd, "gpt-5", null)

        assertFailsWith<IllegalArgumentException> { store.persistNew(candidate) }
        assertTrue(Files.list(redirectedTarget).use { it.findAny().isEmpty })
    }

    @Test
    fun `durable operations reject a redirected session directory`(@TempDir root: Path) {
        val sessionRoot = root.resolve("sessions")
        val store = JsonlSessionStore(sessionRoot)
        val session = store.persistedCandidate(root.resolve("workspace"), "gpt-5", null)
        val sessionFile = requireNotNull(store.locate(session))
        val bucket = sessionFile.parent
        val redirectedTarget = root.resolve("redirected-bucket")
        Files.move(bucket, redirectedTarget)
        try {
            Files.createSymbolicLink(bucket, redirectedTarget)
        } catch (error: Exception) {
            assumeTrue(false, "symlinks unsupported here: ${error.message}")
        }
        val redirectedFile = redirectedTarget.resolve(sessionFile.fileName)
        val accepted = Files.readAllBytes(redirectedFile)

        assertNull(store.locate(session))
        assertTrue(store.listForCwd(session.cwd).isEmpty())
        assertFailsWith<IllegalArgumentException> { store.append(session, entry("blocked", distantPast)) }
        assertFailsWith<IllegalArgumentException> {
            store.persistMetadata(session, session.metadata.copy(name = "blocked"))
        }
        assertFailsWith<IllegalArgumentException> { store.rewrite(session, emptyList()) }
        assertContentEquals(accepted, Files.readAllBytes(redirectedFile))
    }

    @Test
    fun `persistNew rejects nonempty candidates without publication`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val candidate = store.newCandidate(root.resolve("workspace"), "gpt-5", null)
        candidate.entries += entry("not empty", distantPast)

        assertFailsWith<IllegalArgumentException> { store.persistNew(candidate) }

        assertNull(store.locate(candidate))
        assertTrue(store.listForCwd(candidate.cwd).isEmpty())
    }

    @Test
    fun `persistNew never replaces an existing id`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val accepted = store.persistedCandidate(root.resolve("workspace"), "accepted", "first")
        val file = requireNotNull(store.locate(accepted))
        val original = Files.readAllBytes(file)
        val collision = accepted.copy(name = "replacement", modelName = "other")

        assertFailsWith<java.nio.file.FileAlreadyExistsException> { store.persistNew(collision) }

        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(accepted.header, store.loadHeader(accepted.id))
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun `concurrent persistNew race publishes exactly one candidate`(@TempDir root: Path) {
        val cwd = root.resolve("workspace")
        val candidate = JsonlSessionStore(root).newCandidate(cwd, "gpt-5", null)
        val executor = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val attempts = List(2) {
                executor.submit<Boolean> {
                    gate.await()
                    runCatching { JsonlSessionStore(root).persistNew(candidate) }.isSuccess
                }
            }
            gate.countDown()

            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
        assertEquals(candidate.header, JsonlSessionStore(root).loadHeader(candidate.id))
    }

    @Test
    fun `persistNew write failure cleans candidate and leaves no accepted header`(@TempDir root: Path) {
        val candidate = JsonlSessionStore(root).newCandidate(root.resolve("workspace"), "gpt-5", null)
        val operations = MockSessionFileOperations(
            newWrite = { temporary, _ ->
                Files.writeString(temporary, "partial")
                error("write failed")
            },
        )
        val store = JsonlSessionStore.withFileOperations(root, operations)

        val failure = assertFailsWith<IllegalStateException> { store.persistNew(candidate) }

        assertEquals("write failed", failure.message)
        assertNull(store.locate(candidate))
        assertTrue(store.listForCwd(candidate.cwd).isEmpty())
        val parent = root.listDirectoryEntries().single()
        assertTrue(Files.list(parent).use { it.toList() }.isEmpty())
    }

    @Test
    fun `persistNew publication failure has no fallback and cleans candidate`(@TempDir root: Path) {
        val candidate = JsonlSessionStore(root).newCandidate(root.resolve("workspace"), "gpt-5", null)
        var publishCalls = 0
        val operations = MockSessionFileOperations(
            publishNew = { temporary, target ->
                publishCalls++
                throw AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "unsupported")
            },
        )
        val store = JsonlSessionStore.withFileOperations(root, operations)

        assertFailsWith<AtomicMoveNotSupportedException> { store.persistNew(candidate) }

        assertEquals(1, publishCalls)
        assertNull(store.locate(candidate))
        assertTrue(store.listForCwd(candidate.cwd).isEmpty())
        val parent = root.listDirectoryEntries().single()
        assertTrue(Files.list(parent).use { it.toList() }.isEmpty())
    }

    @Test
    fun `persistNew cleanup failure cannot undo successful publication`(@TempDir root: Path) {
        val candidate = JsonlSessionStore(root).newCandidate(root.resolve("workspace"), "gpt-5", null)
        val operations = MockSessionFileOperations(delete = { error("cleanup failed") })
        val store = JsonlSessionStore.withFileOperations(root, operations)

        store.persistNew(candidate)

        assertEquals(candidate.header, store.loadHeader(candidate.id))
        assertEquals(1, temporaryFiles(requireNotNull(store.locate(candidate))).size)
    }

    @Test
    fun `persistNew forces header before one atomic publication`(@TempDir root: Path) {
        val events = mutableListOf<String>()
        val operations = MockSessionFileOperations(
            newWrite = { temporary, header ->
                events += "write-force-close"
                NioSessionFileOperations.writeNewHeader(temporary, header)
            },
            publishNew = { temporary, target ->
                events += "publish"
                NioSessionFileOperations.publishNewAtomically(temporary, target)
            },
            forceDirectory = { directory ->
                events += "force-directory"
                NioSessionFileOperations.forceDirectoryIfSupported(directory)
            },
        )
        val store = JsonlSessionStore.withFileOperations(root, operations)
        val candidate = store.newCandidate(root.resolve("workspace"), "gpt-5", null)

        store.persistNew(candidate)

        assertEquals(listOf("write-force-close", "publish", "force-directory"), events)
    }

    @Test
    fun `create then append persists and survives a fresh store instance`(@TempDir root: Path) {
        val cwd = root.resolve("proj")
        val session = JsonlSessionStore(root).persistedCandidate(cwd, "gpt-5", "demo")
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

        val older = store.persistedCandidate(cwdA, "m", "older")
        store.append(older, entry("x", distantPast))
        val newer = store.persistedCandidate(cwdA, "m", "newer")
        store.append(newer, entry("y", distantFuture))
        store.persistedCandidate(cwdB, "m", "b-only")

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
        store.persistedCandidate(cwd, "m", "first").also { store.append(it, entry("x", distantPast)) }
        val latest = store.persistedCandidate(cwd, "m", "second").also { store.append(it, entry("y", distantFuture)) }

        assertEquals(latest.id, store.mostRecentForCwd(cwd)?.id)
        assertNull(store.mostRecentForCwd(root.resolve("empty")))
    }

    @Test
    fun renamePersistsEntries(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("p"), "m", null)
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
        val session = store.persistedCandidate(root.resolve("p"), "old-model", "old-name")
        store.append(session, entry("first", distantPast))
        store.append(session, entry("second", distantFuture))
        val file = requireNotNull(store.locate(session))
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
    fun hostedBindingSurvivesMetadataReplacementWithTranscriptBytes(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("hosted"), "hosted", null)
        val binding = HostedSessionBinding("hosted-agent", session.id.toString())
        val reserved = session.metadata.copy(hostedBinding = binding)
        store.persistMetadata(session, reserved)
        session.commitMetadata(reserved)
        store.append(session, entry("kept", distantPast))
        val before = transcriptBytes(Files.readAllBytes(requireNotNull(store.locate(session))))

        store.rename(session, "renamed")

        val loaded = JsonlSessionStore(root).load(session.id)
        assertEquals(binding, loaded.hostedBinding)
        assertEquals("renamed", loaded.name)
        assertContentEquals(before, transcriptBytes(Files.readAllBytes(requireNotNull(store.locate(session)))))
    }

    @Test
    fun rewriteAtomicallyPersistsExactCandidateOrderAndSurvivesRestart(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.persistedCandidate(root.resolve("rewrite-success"), "model", "named")
        val summarized = entry("summarized", distantPast)
        val kept = entry("kept", distantFuture)
        val marker = CompactionEntry(
            id = Uuid.random(),
            parentId = summarized.id,
            timestamp = distantPast,
            summary = "summary",
            firstKeptEntryId = kept.id,
            tokensBefore = 42,
        )
        session.entries += listOf(summarized, kept)
        store.append(session, summarized)
        store.append(session, kept)
        val candidate = listOf(summarized, marker, kept)

        store.rewrite(session, candidate)

        assertEquals(
            listOf(summarized.id, kept.id),
            session.entries.map { it.id },
            "persistence must not mutate the live session",
        )
        assertEquals(
            listOf(
                SessionCodec.encodeHeader(session),
                SessionCodec.encodeEntry(summarized),
                SessionCodec.encodeEntry(marker),
                SessionCodec.encodeEntry(kept),
            ),
            Files.readAllLines(requireNotNull(store.locate(session))),
        )
        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(candidate.map { it.id }, restarted.entries.map { it.id })
    }

    @Test
    fun rewriteTempCreationFailureDoesNotStartCandidateWrite(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("rewrite-create-failure"), "model", null)
        val accepted = entry("accepted", distantPast)
        baseline.append(session, accepted)
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        var writeCalled = false
        var cleanupCalled = false
        val operations = MockSessionFileOperations(
            create = { error("cannot create temp") },
            rewriteWrite = { _, _ -> writeCalled = true },
            delete = { cleanupCalled = true },
        )

        val failure = assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations).rewrite(session, listOf(accepted))
        }

        assertEquals("cannot create temp", failure.message)
        assertFalse(writeCalled)
        assertFalse(cleanupCalled, "there is no temporary path to clean when creation fails")
        assertContentEquals(original, Files.readAllBytes(file))
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun rewriteCandidateWriteFailureKeepsAcceptedFile(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("rewrite-write-failure"), "model", null)
        val accepted = entry("accepted", distantPast)
        session.entries += accepted
        baseline.append(session, accepted)
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        val replacement = entry("replacement", distantFuture)
        val operations = MockSessionFileOperations(
            rewriteWrite = { temporary, _ ->
                Files.writeString(temporary, "partial candidate")
                error("disk full")
            },
        )

        val failure = assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations).rewrite(session, listOf(replacement))
        }

        assertEquals("disk full", failure.message)
        assertEquals(listOf(accepted.id), session.entries.map { it.id })
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(listOf(accepted.id), JsonlSessionStore(root).load(session.id).entries.map { it.id })
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun rewriteReplacementFailureKeepsAcceptedFile(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("rewrite-replace-failure"), "model", null)
        val accepted = entry("accepted", distantPast)
        session.entries += accepted
        baseline.append(session, accepted)
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(
            replaceAtomically = { _, _ -> error("replacement failed") },
        )

        val failure = assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations)
                .rewrite(session, listOf(entry("replacement", distantFuture)))
        }

        assertEquals("replacement failed", failure.message)
        assertEquals(listOf(accepted.id), session.entries.map { it.id })
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(listOf(accepted.id), JsonlSessionStore(root).load(session.id).entries.map { it.id })
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun rewriteCleanupFailureDoesNotMaskReplacementFailure(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("rewrite-cleanup-failure"), "model", null)
        val accepted = entry("accepted", distantPast)
        baseline.append(session, accepted)
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(
            replaceAtomically = { _, _ -> error("replacement failed") },
            delete = { error("cleanup failed") },
        )

        val failure = assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations).rewrite(session, listOf(accepted))
        }

        assertEquals("replacement failed", failure.message)
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(1, temporaryFiles(file).size)
    }

    @Test
    fun rewriteUnsupportedAtomicMoveKeepsAcceptedFile(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("rewrite-unsupported-move"), "model", null)
        val accepted = entry("accepted", distantPast)
        session.entries += accepted
        baseline.append(session, accepted)
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(
            replaceAtomically = { temporary, target ->
                throw AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "unsupported")
            },
        )

        assertFailsWith<AtomicMoveNotSupportedException> {
            JsonlSessionStore.withFileOperations(root, operations)
                .rewrite(session, listOf(entry("replacement", distantFuture)))
        }

        assertEquals(listOf(accepted.id), session.entries.map { it.id })
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals(listOf(accepted.id), JsonlSessionStore(root).load(session.id).entries.map { it.id })
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun concurrentAppendWaitsForRewriteAndFollowsCandidate(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("rewrite-concurrent"), "model", null)
        val accepted = entry("accepted", distantPast)
        baseline.append(session, accepted)
        val rewritten = entry("rewritten", distantPast)
        val concurrent = entry("concurrent", distantFuture)
        val appendExecutor = Executors.newSingleThreadExecutor()
        val appendStarted = CountDownLatch(1)
        lateinit var store: JsonlSessionStore
        lateinit var appendFuture: Future<*>
        val operations = MockSessionFileOperations(
            rewriteWrite = { temporary, candidateLines ->
                NioSessionFileOperations.writeTranscriptCandidate(temporary, candidateLines)
                appendFuture = appendExecutor.submit {
                    appendStarted.countDown()
                    store.append(session, concurrent)
                }
                assertTrue(appendStarted.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { appendFuture.get(250, TimeUnit.MILLISECONDS) }
            },
        )
        store = JsonlSessionStore.withFileOperations(root, operations)

        try {
            store.rewrite(session, listOf(rewritten))
            appendFuture.get(5, TimeUnit.SECONDS)
        } finally {
            appendExecutor.shutdownNow()
        }

        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals(listOf(rewritten.id, concurrent.id), restarted.entries.map { it.id })
    }

    @Test
    fun failureBeforeTempWriteKeepsOriginal(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("p"), "old", null)
        baseline.append(session, entry("kept", distantPast))
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        var writeCalled = false
        val operations = MockSessionFileOperations(
            create = { error("cannot create temp") },
            write = { _, _, _ -> writeCalled = true },
        )

        assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations)
                .persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertFalse(writeCalled)
        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun tempWriteFailureKeepsOriginal(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("p"), "old", null)
        baseline.append(session, entry("kept", distantPast))
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(
            write = { _, temporary, _ ->
                Files.writeString(temporary, "partial candidate")
                error("disk full")
            },
        )

        assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations)
                .persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun unsupportedAtomicMoveKeepsOriginalAndAttemptsTempCleanup(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("p"), "old", null)
        baseline.append(session, entry("kept", distantPast))
        val file = requireNotNull(baseline.locate(session))
        val original = Files.readAllBytes(file)
        val operations = MockSessionFileOperations(
            replaceAtomically = { temporary, target ->
                throw AtomicMoveNotSupportedException(temporary.toString(), target.toString(), "unsupported")
            },
        )

        assertFailsWith<AtomicMoveNotSupportedException> {
            JsonlSessionStore.withFileOperations(root, operations)
                .persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertContentEquals(original, Files.readAllBytes(file))
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertTrue(temporaryFiles(file).isEmpty())
    }

    @Test
    fun cleanupFailureDoesNotMaskReplacementFailure(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("p"), "old", null)
        val file = requireNotNull(baseline.locate(session))
        val operations = MockSessionFileOperations(
            replaceAtomically = { _, _ -> error("replacement failed") },
            delete = { error("cleanup failed") },
        )

        val failure = assertFailsWith<IllegalStateException> {
            JsonlSessionStore.withFileOperations(root, operations)
                .persistMetadata(session, session.metadata.copy(modelName = "new"))
        }

        assertEquals("replacement failed", failure.message)
        assertEquals("old", JsonlSessionStore(root).load(session.id).modelName)
        assertEquals(1, temporaryFiles(file).size)
    }

    @Test
    fun concurrentAppendWaitsForMetadataReplacementAndIsNotLost(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("p"), "old", null)
        baseline.append(session, entry("existing", distantPast))
        val appendExecutor = Executors.newSingleThreadExecutor()
        val appendStarted = CountDownLatch(1)
        lateinit var store: JsonlSessionStore
        lateinit var appendFuture: Future<*>
        val operations = MockSessionFileOperations(
            write = { source, temporary, candidateHeader ->
                NioSessionFileOperations.writeCandidate(source, temporary, candidateHeader)
                appendFuture = appendExecutor.submit {
                    appendStarted.countDown()
                    store.append(session, entry("concurrent", distantFuture))
                }
                assertTrue(appendStarted.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { appendFuture.get(250, TimeUnit.MILLISECONDS) }
            },
        )
        store = JsonlSessionStore.withFileOperations(root, operations)

        try {
            store.persistMetadata(session, session.metadata.copy(modelName = "new"))
            appendFuture.get(5, TimeUnit.SECONDS)
        } finally {
            appendExecutor.shutdownNow()
        }

        val restarted = JsonlSessionStore(root).load(session.id)
        assertEquals("new", restarted.modelName)
        assertEquals(
            listOf("existing", "concurrent"),
            restarted.entries.filterIsInstance<UserEntry>().map { it.text },
        )
    }

    @Test
    fun failedThenSuccessfulUpdateRestartsWithAcceptedState(@TempDir root: Path) {
        val baseline = JsonlSessionStore(root)
        val session = baseline.persistedCandidate(root.resolve("p"), "old", "accepted")
        baseline.append(session, entry("first", distantPast))
        baseline.append(session, entry("second", distantFuture))
        val candidate = session.metadata.copy(name = "candidate", modelName = "new")
        val failing = JsonlSessionStore.withFileOperations(
            root,
            MockSessionFileOperations(replaceAtomically = { _, _ -> error("failure before replace") }),
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
        val session = store.persistedCandidate(root.resolve("p"), "m", null)
        val located = requireNotNull(store.locate(session))
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
    private val newWrite: (Path, String) -> Unit = NioSessionFileOperations::writeNewHeader,
    private val publishNew: (Path, Path) -> Unit = NioSessionFileOperations::publishNewAtomically,
    private val forceDirectory: (Path) -> Unit = NioSessionFileOperations::forceDirectoryIfSupported,
    private val write: (Path, Path, String) -> Unit = NioSessionFileOperations::writeCandidate,
    private val rewriteWrite: (Path, List<String>) -> Unit = NioSessionFileOperations::writeTranscriptCandidate,
    private val replaceAtomically: (Path, Path) -> Unit = NioSessionFileOperations::replaceAtomically,
    private val delete: (Path) -> Unit = NioSessionFileOperations::deleteIfExists,
) : SessionFileOperations {
    override fun createSiblingTemp(target: Path): Path = create(target)

    override fun writeNewHeader(temporary: Path, header: String) = newWrite(temporary, header)

    override fun publishNewAtomically(temporary: Path, target: Path) = publishNew(temporary, target)

    override fun forceDirectoryIfSupported(directory: Path) = forceDirectory(directory)

    override fun writeCandidate(source: Path, temporary: Path, candidateHeader: String) =
        write(source, temporary, candidateHeader)

    override fun writeTranscriptCandidate(temporary: Path, candidateLines: List<String>) =
        rewriteWrite(temporary, candidateLines)

    override fun replaceAtomically(temporary: Path, target: Path) = replaceAtomically.invoke(temporary, target)

    override fun deleteIfExists(path: Path) = delete(path)
}
