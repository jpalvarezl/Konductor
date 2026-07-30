package com.konductor.core.models

import java.nio.file.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

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
