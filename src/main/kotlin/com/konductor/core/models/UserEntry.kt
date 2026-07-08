package com.konductor.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
@SerialName("user")
data class UserEntry(
    override val id: Uuid,
    override val parentId: Uuid?,
    override val timestamp: Instant,
    val text: String
) : Entry
