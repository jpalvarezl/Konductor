package com.konductor.session

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Round-trips a [Session] to/from the on-disk JSONL schema (`docs/spec/sessions.md#entry-model--on-disk-schema`):
 * the first line is a `header`, then one line per [Entry], each a JSON object with a `type` discriminator.
 *
 * The codec is hand-rolled (rather than `@Serializable` on the domain models) so the wire format is decoupled
 * from the in-memory model and matches the spec byte-for-byte — [Uuid]/[Instant]/[Path] use their string forms.
 */
object SessionCodec {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    // --- Encoding ---------------------------------------------------------------------------------------

    fun encodeHeader(session: Session): String = buildJsonObject {
        put("type", "header")
        put("id", session.id.toString())
        put("version", SCHEMA_VERSION)
        session.name?.let { put("name", it) }
        put("cwd", session.cwd.toString())
        put("model", session.modelName)
        put("createdAt", session.createdAt.toString())
    }.toString()

    fun encodeEntry(entry: Entry): String = when (entry) {
        is UserEntry -> baseObject(entry, "user") { put("text", entry.text) }
        is AssistantEntry -> baseObject(entry, "assistant") {
            put("text", entry.text)
            putJsonArray("toolCalls") { entry.toolCalls.forEach { add(encodeToolCall(it)) } }
            entry.usage?.let { putJsonObject("usage") { putUsage(it) } }
        }
        is ToolCallEntry -> baseObject(entry, "tool_call") {
            putJsonObject("call") { putToolCall(entry.call) }
        }
        is ToolResultEntry -> baseObject(entry, "tool_result") {
            put("callId", entry.result.callId)
            put("output", entry.result.output)
            put("isError", entry.result.isError)
            put("truncatedBytes", entry.result.truncatedBytes)
        }
        is CompactionEntry -> baseObject(entry, "compaction") {
            put("summary", entry.summary)
            put("firstKeptEntryId", entry.firstKeptEntryId.toString())
            put("tokensBefore", entry.tokensBefore)
        }
    }

    private fun baseObject(entry: Entry, type: String, extra: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("type", type)
            put("id", entry.id.toString())
            entry.parentId?.let { put("parentId", it.toString()) }
            put("timestamp", entry.timestamp.toString())
            extra()
        }.toString()

    private fun JsonObjectBuilder.putToolCall(call: ToolCall) {
        put("callId", call.callId)
        put("name", call.name)
        put("argumentsJson", call.argumentsJson)
    }

    private fun encodeToolCall(call: ToolCall): JsonObject = buildJsonObject { putToolCall(call) }

    private fun JsonObjectBuilder.putUsage(usage: Usage) {
        put("inputTokens", usage.inputTokens)
        put("outputTokens", usage.outputTokens)
        put("totalTokens", usage.totalTokens)
    }

    // --- Decoding ---------------------------------------------------------------------------------------

    /** Parse a header line into an empty [Session] (entries are appended by [decodeEntry] callers). */
    fun decodeHeader(line: String): Session {
        val obj = json.parseToJsonElement(line).jsonObject
        require(obj.str("type") == "header") { "expected a header line, got type=${obj.strOrNull("type")}" }
        return Session(
            id = Uuid.parse(obj.str("id")),
            name = obj.strOrNull("name"),
            cwd = Path.of(obj.str("cwd")),
            modelName = obj.str("model"),
            createdAt = Instant.parse(obj.str("createdAt")),
        )
    }

    fun decodeEntry(line: String): Entry {
        val obj = json.parseToJsonElement(line).jsonObject
        val id = Uuid.parse(obj.str("id"))
        val parentId = obj.strOrNull("parentId")?.let(Uuid::parse)
        val timestamp = Instant.parse(obj.str("timestamp"))
        return when (val type = obj.str("type")) {
            "user" -> UserEntry(id, parentId, timestamp, obj.str("text"))
            "assistant" -> AssistantEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                text = obj.str("text"),
                toolCalls = obj["toolCalls"]?.let { it as? JsonArray }
                    ?.map { decodeToolCall(it.jsonObject) } ?: emptyList(),
                usage = obj["usage"]?.takeUnless { it is JsonNull }?.let { decodeUsage(it.jsonObject) },
            )
            "tool_call" -> ToolCallEntry(id, parentId, timestamp, decodeToolCall(obj.getValue("call").jsonObject))
            "tool_result" -> ToolResultEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                result = ToolResult(
                    callId = obj.str("callId"),
                    output = obj.str("output"),
                    isError = obj.getValue("isError").jsonPrimitive.boolean,
                    truncatedBytes = obj.getValue("truncatedBytes").jsonPrimitive.int,
                ),
            )
            "compaction" -> CompactionEntry(
                id = id,
                parentId = parentId,
                timestamp = timestamp,
                summary = obj.str("summary"),
                firstKeptEntryId = Uuid.parse(obj.str("firstKeptEntryId")),
                tokensBefore = obj.getValue("tokensBefore").jsonPrimitive.int,
            )
            else -> throw IllegalArgumentException("unknown entry type '$type'")
        }
    }

    private fun decodeToolCall(obj: JsonObject): ToolCall =
        ToolCall(obj.str("callId"), obj.str("name"), obj.str("argumentsJson"))

    private fun decodeUsage(obj: JsonObject): Usage = Usage(
        inputTokens = obj.getValue("inputTokens").jsonPrimitive.int,
        outputTokens = obj.getValue("outputTokens").jsonPrimitive.int,
        totalTokens = obj.getValue("totalTokens").jsonPrimitive.int,
    )

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}
