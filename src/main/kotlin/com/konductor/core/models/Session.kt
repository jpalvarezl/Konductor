package com.konductor.core.models

import java.nio.file.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Validate a PromptAgent binding name without changing its identity. `null` explicitly selects the ephemeral Prompt
 * adapter; a non-null name must contain non-whitespace text and must already have no leading or trailing whitespace.
 * TUI/configuration parsers trim user-authored values before they reach this domain boundary. Provider and persistence
 * code reject invalid values here rather than silently binding or recording a different name.
 *
 * @return [name] unchanged, including an explicit `null` ephemeral selection.
 */
internal fun requireValidPromptAgentName(name: String?): String? {
    require(name == null || name.isNotBlank()) { "PromptAgent name must be non-blank when present." }
    require(name == null || name == name.trim()) { "PromptAgent name must already be trimmed." }
    return name
}

/**
 * A `Session` is a persisted conversation. This model mirrors the on-disk JSONL schema
 * (`docs/spec/sessions.md`): a header (this object's scalar fields) followed by one line per [Entry].
 *
 * @property id The unique identifier for the session (also the JSONL file name).
 * @property name Optional user-given label.
 * @property cwd The working directory the session belongs to (drives the on-disk grouping).
 * @property modelName The active model for the session (updated by `/model`).
 * @property createdAt When the session (its header) was first written.
 * @property entries The ordered transcript entries, appended as they are produced.
 */
data class Session(
    val id: Uuid,
    var name: String?,
    val cwd: Path,
    var modelName: String,
    val createdAt: Instant,
    /** The persisted PromptAgent (M2.5) this session was bound to, or null for ephemeral. Header metadata only. */
    var promptAgentName: String? = null,
    /** Durable Foundry Hosted binding. Null for Prompt and non-persisted sessions. */
    var hostedBinding: HostedSessionBinding? = null,
    val entries: MutableList<Entry> = mutableListOf(),
) {
    /** Immutable snapshot of every persisted header fact. */
    val header: SessionHeader
        get() = SessionHeader(id, name, cwd, modelName, createdAt, promptAgentName, hostedBinding)

    /** Immutable candidate for the mutable header fields, suitable for persistence before a live commit. */
    val metadata: SessionMetadata
        get() = SessionMetadata(name, modelName, promptAgentName, hostedBinding)

    /** Commit metadata already accepted by the session store. Callers persist the candidate before invoking this. */
    fun commitMetadata(candidate: SessionMetadata) {
        name = candidate.name
        modelName = candidate.modelName
        promptAgentName = candidate.promptAgentName
        hostedBinding = candidate.hostedBinding
    }
}

/** Immutable persisted identity and binding facts decoded without loading a transcript. */
data class SessionHeader(
    val id: Uuid,
    val name: String?,
    val cwd: Path,
    val modelName: String,
    val createdAt: Instant,
    val promptAgentName: String? = null,
    val hostedBinding: HostedSessionBinding? = null,
) {
    /** Materialize an empty live session; transcript loading remains the store's responsibility. */
    fun toSession(): Session =
        Session(id, name, cwd, modelName, createdAt, promptAgentName, hostedBinding)
}

/** Identity of the one Foundry Hosted session whose server-owned history belongs to a persisted local session. */
data class HostedSessionBinding(
    val agentName: String,
    val sessionId: String,
)

/** The mutable portion of a session header, captured immutably for candidate persistence. */
data class SessionMetadata(
    val name: String?,
    val modelName: String,
    val promptAgentName: String?,
    val hostedBinding: HostedSessionBinding? = null,
)
