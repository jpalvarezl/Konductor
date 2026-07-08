package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Round-trips a [Session] to/from the on-disk JSONL schema (`docs/spec/sessions.md#entry-model--on-disk-schema`):
 * the first line is a `header`, then one line per [Entry], each a JSON object with a `type` discriminator.
 *
 * Entries are serialized directly via the domain models' generated `@Serializable` polymorphic serializer
 * (`type` discriminator + `@SerialName` on each subtype); `kotlin.uuid.Uuid` and `kotlin.time.Instant` have
 * built-in serializers. The header is a small local DTO ([HeaderLine]) rather than an annotation on [Session],
 * so the domain `Session` stays free of wire-format fields and the `java.nio.file.Path` cwd is handled here
 * (serialized as its **absolute, normalized** string, the safest cross-run representation).
 *
 * JSON config: `type` discriminator, unknown keys ignored (forward-compat), nulls omitted, defaults encoded.
 */
object SessionCodec {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeHeader(session: Session): String = json.encodeToString(
        HeaderLine.serializer(),
        HeaderLine(
            id = session.id.toString(),
            name = session.name,
            cwd = session.cwd.toAbsolutePath().normalize().toString(),
            model = session.modelName,
            createdAt = session.createdAt.toString(),
            promptAgentName = session.promptAgentName,
        ),
    )

    /** Parse a header line into an empty [Session] (entry lines are decoded separately by callers). */
    fun decodeHeader(line: String): Session {
        val header = json.decodeFromString(HeaderLine.serializer(), line)
        require(header.type == HEADER_TYPE) { "expected a header line, got type=${header.type}" }
        require(header.version <= SCHEMA_VERSION) {
            "session schema version ${header.version} is newer than supported ($SCHEMA_VERSION); " +
                "upgrade Konductor to read this session."
        }
        return Session(
            id = Uuid.parse(header.id),
            name = header.name,
            cwd = Path.of(header.cwd),
            modelName = header.model,
            createdAt = Instant.parse(header.createdAt),
            promptAgentName = header.promptAgentName,
        )
    }

    fun encodeEntry(entry: Entry): String = json.encodeToString(Entry.serializer(), entry)

    fun decodeEntry(line: String): Entry = json.decodeFromString(Entry.serializer(), line)

    private const val HEADER_TYPE = "header"

    /**
     * On-disk header line. A local DTO (not an annotation on [Session]) so wire-only fields — the `type`
     * discriminator, schema `version`, and the `cwd`/`id`/`createdAt` string forms — stay out of the domain.
     */
    @Serializable
    private data class HeaderLine(
        val type: String = HEADER_TYPE,
        val id: String,
        val version: Int = SCHEMA_VERSION,
        val name: String? = null,
        val cwd: String,
        val model: String,
        val createdAt: String,
        val promptAgentName: String? = null,
    )
}
