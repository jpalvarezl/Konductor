package com.konductor.session

import com.konductor.core.models.Entry
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.Session
import com.konductor.core.models.SessionMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** JSONL header and entry codec for Prompt v1 and durable Hosted v2 sessions. */
object SessionCodec {
    const val SCHEMA_VERSION: Int = 2

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeHeader(session: Session, metadata: SessionMetadata = session.metadata): String {
        val binding = metadata.hostedBinding
        if (binding != null) validateHostedBinding(session.id, binding)
        require(binding == null || metadata.promptAgentName == null) {
            "Hosted v2 sessions cannot also carry a PromptAgent binding."
        }
        return json.encodeToString(
            HeaderLine.serializer(),
            HeaderLine(
                id = session.id.toString(),
                version = if (binding == null) PROMPT_SCHEMA_VERSION else HOSTED_SCHEMA_VERSION,
                name = metadata.name,
                cwd = session.cwd.toAbsolutePath().normalize().toString(),
                model = metadata.modelName,
                createdAt = session.createdAt.toString(),
                promptAgentName = metadata.promptAgentName,
                hostedAgentName = binding?.agentName,
                hostedSessionId = binding?.sessionId,
            ),
        )
    }

    /** Parse a header line into an empty [Session] (entry lines are decoded separately by callers). */
    fun decodeHeader(line: String): Session {
        val header = json.decodeFromString(HeaderLine.serializer(), line)
        require(header.type == HEADER_TYPE) { "expected a header line, got type=${header.type}" }
        require(header.version in PROMPT_SCHEMA_VERSION..SCHEMA_VERSION) {
            if (header.version > SCHEMA_VERSION) {
                "session schema version ${header.version} is newer than supported ($SCHEMA_VERSION); " +
                    "upgrade Konductor to read this session."
            } else {
                "unsupported session schema version ${header.version}."
            }
        }

        val hostedBinding = when (header.version) {
            PROMPT_SCHEMA_VERSION -> {
                require(header.hostedAgentName == null && header.hostedSessionId == null) {
                    "Prompt v1 session header cannot contain Hosted binding fields."
                }
                null
            }
            HOSTED_SCHEMA_VERSION -> {
                require(header.promptAgentName == null) {
                    "Hosted v2 session header cannot contain promptAgentName."
                }
                val agentName = requireNotNull(header.hostedAgentName) {
                    "Hosted v2 session header is missing hostedAgentName."
                }
                val sessionId = requireNotNull(header.hostedSessionId) {
                    "Hosted v2 session header is missing hostedSessionId."
                }
                HostedSessionBinding(agentName, sessionId).also {
                    validateHostedBinding(Uuid.parse(header.id), it)
                }
            }
            else -> error("unreachable schema version ${header.version}")
        }

        return Session(
            id = Uuid.parse(header.id),
            name = header.name,
            cwd = Path.of(header.cwd),
            modelName = header.model,
            createdAt = Instant.parse(header.createdAt),
            promptAgentName = header.promptAgentName,
            hostedBinding = hostedBinding,
        )
    }

    fun encodeEntry(entry: Entry): String = json.encodeToString(Entry.serializer(), entry)

    fun decodeEntry(line: String): Entry = json.decodeFromString(Entry.serializer(), line)

    private fun validateHostedBinding(id: Uuid, binding: HostedSessionBinding) {
        require(binding.agentName.isNotBlank()) { "Hosted agent name cannot be blank." }
        require(binding.sessionId.isNotBlank()) { "Hosted session id cannot be blank." }
        require(binding.sessionId == id.toString()) {
            "Hosted session id '${binding.sessionId}' must equal local session id '$id'."
        }
    }

    private const val HEADER_TYPE = "header"
    private const val PROMPT_SCHEMA_VERSION = 1
    private const val HOSTED_SCHEMA_VERSION = 2

    /** Wire-only session header. Prompt uses v1; a complete Hosted binding selects v2. */
    @Serializable
    private data class HeaderLine(
        val type: String = HEADER_TYPE,
        val id: String,
        val version: Int,
        val name: String? = null,
        val cwd: String,
        val model: String,
        val createdAt: String,
        val promptAgentName: String? = null,
        val hostedAgentName: String? = null,
        val hostedSessionId: String? = null,
    )
}
