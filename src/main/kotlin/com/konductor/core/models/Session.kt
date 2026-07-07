package com.konductor.core.models

import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/**
 * A `Session` is a persisted conversation, this model represents `Session` schema to be used
 * for the on-disk JSONL schema.
 *
 * @property id The unique identifier for the session.
 * @property name User given name.
 * @property cwd The current working directory associated with the session.
 * @property modelName The name of the model used in the session.
 * @property entries A mutable list of entries associated with the session.
 */
data class Session(
    val id: Uuid,
    var name: String?,
    val cwd: Path,
    val modelName: String,
    val entries: MutableList<Entry> = mutableListOf()
)
