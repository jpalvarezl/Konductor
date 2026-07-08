package com.konductor.session

import com.konductor.core.models.UserEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `rename rewrites the header in place and preserves entries`(@TempDir root: Path) {
        val store = JsonlSessionStore(root)
        val session = store.create(root.resolve("p"), "m", null)
        store.append(session, entry("kept", distantPast))

        store.rename(session, "renamed")

        val loaded = store.load(session.id)
        assertEquals("renamed", loaded.name)
        assertEquals(1, loaded.entries.size)
        assertEquals("kept", (loaded.entries[0] as UserEntry).text)
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
}
