package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Most basic unit of data in a session in Konductor.
 *
 * @param id Unique identifier for this entry.
 * @param parentId Optional identifier of the parent entry, to create linear (and optionally, fork) history.
 * @param timestamp The time at which this entry was created.
 */
sealed interface Entry {
    val id: Uuid
    val parentId: Uuid?
    val timestamp: Instant
}
