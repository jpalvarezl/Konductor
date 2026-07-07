package com.konductor.core.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class CompactionEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val summary: String,
    val firstKeptEntryId: Uuid,
    val tokensBefore: Int
): Entry
