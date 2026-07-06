package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface Entry {
    val id: Uuid
    val parentId: Uuid?
    val timestamp: Instant
}
