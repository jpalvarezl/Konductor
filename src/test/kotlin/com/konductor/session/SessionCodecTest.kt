package com.konductor.session

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun `current header and entry schema matches the v1 JSONL golden`() {
        val cwd = Path.of("golden-project").toAbsolutePath().normalize()
        val header = Session(
            id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
            name = "golden session",
            cwd = cwd,
            modelName = "gpt-test",
            createdAt = ts,
            promptAgentName = "golden-agent",
        )
        val userId = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val assistantId = Uuid.parse("00000000-0000-0000-0000-000000000003")
        val callId = Uuid.parse("00000000-0000-0000-0000-000000000004")
        val resultId = Uuid.parse("00000000-0000-0000-0000-000000000005")
        val keptId = Uuid.parse("00000000-0000-0000-0000-000000000006")
        val compactionId = Uuid.parse("00000000-0000-0000-0000-000000000007")
        val entries = listOf(
            UserEntry(userId, null, ts, "hello\nworld"),
            AssistantEntry(
                assistantId,
                userId,
                ts,
                "answer",
                listOf(ToolCall("call-1", "read", """{"path":"README.md"}""")),
                Usage(10, 5, 15),
            ),
            ToolCallEntry(callId, assistantId, ts, ToolCall("call-2", "edit", """{"path":"a.txt"}""")),
            ToolResultEntry(resultId, callId, ts, ToolResult("call-2", "updated", isError = false, truncatedBytes = 0)),
            CompactionEntry(compactionId, resultId, ts, "## Summary", keptId, 48_000),
        )
        val actual = (listOf(SessionCodec.encodeHeader(header)) + entries.map(SessionCodec::encodeEntry))
            .joinToString("\n", postfix = "\n")
        val template = requireNotNull(javaClass.getResource("/session/current-session-v1.jsonl")) {
            "missing JSONL golden fixture"
        }.readText().replace("\r\n", "\n")
        val expected = template.replace("__CWD_JSON__", Json.encodeToString(cwd.toString()))

        assertEquals(expected, actual)

        val lines = expected.lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(header.header, SessionCodec.decodeHeader(lines.first()))
        assertEquals(entries, lines.drop(1).map(SessionCodec::decodeEntry))
    }

    @Test
    fun `hosted header and entry schema matches the v2 JSONL golden`() {
        val cwd = Path.of("hosted-golden-project").toAbsolutePath().normalize()
        val id = Uuid.parse("00000000-0000-0000-0000-000000000011")
        val header = Session(
            id = id,
            name = "hosted golden",
            cwd = cwd,
            modelName = "hosted",
            createdAt = ts,
            hostedBinding = HostedSessionBinding("golden-hosted-agent", id.toString()),
        )
        val entry = UserEntry(
            Uuid.parse("00000000-0000-0000-0000-000000000012"),
            null,
            ts,
            "hello hosted",
        )
        val actual = listOf(SessionCodec.encodeHeader(header), SessionCodec.encodeEntry(entry))
            .joinToString("\n", postfix = "\n")
        val template = requireNotNull(javaClass.getResource("/session/current-session-v2-hosted.jsonl")) {
            "missing Hosted v2 JSONL golden fixture"
        }.readText().replace("\r\n", "\n")
        val expected = template.replace("__CWD_JSON__", Json.encodeToString(cwd.toString()))

        assertEquals(expected, actual)
        val decoded = SessionCodec.decodeHeader(expected.lineSequence().first())
        assertEquals(header.header, decoded)
        assertEquals(entry, SessionCodec.decodeEntry(expected.lineSequence().drop(1).first()))
    }

    @Test
    fun `decoded Prompt header exposes exact immutable facts`() {
        val session = Session(
            Uuid.random(),
            "prompt",
            Path.of("prompt-workspace").toAbsolutePath().normalize(),
            "gpt-prompt",
            ts,
            promptAgentName = "billing-agent",
        )

        val header = SessionCodec.decodeHeader(SessionCodec.encodeHeader(session))

        assertEquals(session.header, header)
        assertEquals("billing-agent", header.promptAgentName)
        assertEquals(null, header.hostedBinding)
    }

    @Test
    fun `decoded Hosted header exposes exact immutable facts`() {
        val id = Uuid.random()
        val session = Session(
            id,
            "hosted",
            Path.of("hosted-workspace").toAbsolutePath().normalize(),
            "hosted-model",
            ts,
            hostedBinding = HostedSessionBinding("hosted-agent", id.toString()),
        )

        val header = SessionCodec.decodeHeader(SessionCodec.encodeHeader(session))

        assertEquals(session.header, header)
        assertEquals(null, header.promptAgentName)
        assertEquals(HostedSessionBinding("hosted-agent", id.toString()), header.hostedBinding)
    }

    @Test
    fun `codec rejects blank model and prompt agent names that are blank or not already trimmed`() {
        val id = Uuid.random()
        val cwd = Json.encodeToString(Path.of("workspace").toAbsolutePath().normalize().toString())
        val common = "\"type\":\"header\",\"id\":\"$id\",\"version\":1,\"cwd\":$cwd," +
            "\"createdAt\":\"2026-07-08T10:00:00Z\""

        assertFailsWith<IllegalArgumentException> {
            SessionCodec.decodeHeader("{$common,\"model\":\"   \"}")
        }
        listOf("", "   ", " billing", "billing ").forEach { persistedName ->
            assertFailsWith<IllegalArgumentException> {
                val encodedName = Json.encodeToString(persistedName)
                SessionCodec.decodeHeader("{$common,\"model\":\"m\",\"promptAgentName\":$encodedName}")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            SessionCodec.encodeHeader(
                Session(Uuid.random(), null, Path.of("/repo"), "m", ts, promptAgentName = " billing "),
            )
        }
    }

    @Test
    fun `codec rejects invalid v1 and v2 binding combinations`() {
        val id = Uuid.random()
        val common = "\"type\":\"header\",\"id\":\"$id\",\"cwd\":\"/x\",\"model\":\"m\"," +
            "\"createdAt\":\"2026-07-08T10:00:00Z\""
        val invalid = listOf(
            "{$common,\"version\":2}",
            "{$common,\"version\":2,\"hostedAgentName\":\"agent\"}",
            "{$common,\"version\":2,\"hostedSessionId\":\"$id\"}",
            "{$common,\"version\":2,\"promptAgentName\":\"prompt\",\"hostedAgentName\":\"agent\"," +
                "\"hostedSessionId\":\"$id\"}",
            "{$common,\"version\":2,\"hostedAgentName\":\"agent\",\"hostedSessionId\":\"${Uuid.random()}\"}",
            "{$common,\"version\":1,\"hostedAgentName\":\"agent\",\"hostedSessionId\":\"$id\"}",
        )

        invalid.forEach { line -> assertFailsWith<IllegalArgumentException> { SessionCodec.decodeHeader(line) } }
    }

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
    }

    @Test
    fun `header omits the name when it is null`() {
        val session = Session(Uuid.random(), null, Path.of("/repo"), "m", ts)
        val line = SessionCodec.encodeHeader(session)
        assertTrue(!line.contains("\"name\""))
        assertEquals(null, SessionCodec.decodeHeader(line).name)
    }

    @Test
    fun `header round-trips the bound prompt agent and omits it when null`() {
        val bound = Session(Uuid.random(), null, Path.of("/repo"), "m", ts, promptAgentName = "billing")
        val boundLine = SessionCodec.encodeHeader(bound)
        assertTrue(boundLine.contains("\"promptAgentName\":\"billing\""))
        assertEquals("billing", SessionCodec.decodeHeader(boundLine).promptAgentName)

        // Ephemeral sessions (no agent) keep the header free of the field.
        val ephemeral = Session(Uuid.random(), null, Path.of("/repo"), "m", ts)
        assertTrue(!SessionCodec.encodeHeader(ephemeral).contains("promptAgentName"))
        assertEquals(null, SessionCodec.decodeHeader(SessionCodec.encodeHeader(ephemeral)).promptAgentName)
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
