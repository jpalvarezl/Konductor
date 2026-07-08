package com.konductor.session

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SessionCodecTest {
    private val ts = Instant.parse("2026-07-08T10:15:30Z")

    @Test
    fun `header round-trips and persists the absolute cwd`() {
        val session = Session(Uuid.random(), "my session", Path.of("repo/x"), "gpt-5", ts)
        val line = SessionCodec.encodeHeader(session)
        assertTrue(line.contains("\"type\":\"header\""))
        val decoded = SessionCodec.decodeHeader(line)
        assertEquals(session.id, decoded.id)
        assertEquals("my session", decoded.name)
        // cwd is persisted as the absolute, normalized path (safest cross-run representation).
        assertEquals(Path.of("repo/x").toAbsolutePath().normalize(), decoded.cwd)
        assertEquals("gpt-5", decoded.modelName)
        assertEquals(ts, decoded.createdAt)
        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun `header omits the name when it is null`() {
        val session = Session(Uuid.random(), null, Path.of("/repo"), "m", ts)
        val line = SessionCodec.encodeHeader(session)
        assertTrue(!line.contains("\"name\""))
        assertEquals(null, SessionCodec.decodeHeader(line).name)
    }

    @Test
    fun `user entry round-trips including embedded newlines`() {
        val entry = UserEntry(Uuid.random(), null, ts, "hello\nworld")
        assertEquals(entry, SessionCodec.decodeEntry(SessionCodec.encodeEntry(entry)))
    }

    @Test
    fun `assistant entry round-trips with tool calls and usage`() {
        val entry = AssistantEntry(
            id = Uuid.random(),
            parentId = Uuid.random(),
            timestamp = ts,
            text = "answer",
            toolCalls = listOf(ToolCall("c1", "read", """{"path":"x"}""")),
            usage = Usage(10, 5, 15),
        )
        assertEquals(entry, SessionCodec.decodeEntry(SessionCodec.encodeEntry(entry)))
    }

    @Test
    fun `assistant entry round-trips without usage or tool calls`() {
        val entry = AssistantEntry(Uuid.random(), null, ts, "answer")
        assertEquals(entry, SessionCodec.decodeEntry(SessionCodec.encodeEntry(entry)))
    }

    @Test
    fun `tool call entry round-trips`() {
        val entry = ToolCallEntry(Uuid.random(), Uuid.random(), ts, ToolCall("c1", "edit", """{"a":1}"""))
        assertEquals(entry, SessionCodec.decodeEntry(SessionCodec.encodeEntry(entry)))
    }

    @Test
    fun `tool result entry round-trips including flags`() {
        val entry = ToolResultEntry(
            id = Uuid.random(),
            parentId = Uuid.random(),
            timestamp = ts,
            result = ToolResult("c1", "body", isError = true, truncatedBytes = 42),
        )
        assertEquals(entry, SessionCodec.decodeEntry(SessionCodec.encodeEntry(entry)))
    }

    @Test
    fun `compaction entry round-trips`() {
        val entry = CompactionEntry(Uuid.random(), null, ts, "## summary", Uuid.random(), 48_000)
        assertEquals(entry, SessionCodec.decodeEntry(SessionCodec.encodeEntry(entry)))
    }

    @Test
    fun `decodeHeader rejects a newer schema version`() {
        val future = """{"type":"header","id":"${Uuid.random()}","version":999,""" +
            """"cwd":"/x","model":"m","createdAt":"2026-07-08T10:00:00Z"}"""
        assertFailsWith<IllegalArgumentException> { SessionCodec.decodeHeader(future) }
    }
}
