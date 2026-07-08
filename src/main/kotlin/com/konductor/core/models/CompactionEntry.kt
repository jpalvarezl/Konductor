package com.konductor.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@SerialName("compaction")
data class CompactionEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val summary: String,
    val firstKeptEntryId: Uuid,
    val tokensBefore: Int
): Entry
