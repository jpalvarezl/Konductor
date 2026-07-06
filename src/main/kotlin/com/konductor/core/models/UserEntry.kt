package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

class UserEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val text: String
) : Entry
