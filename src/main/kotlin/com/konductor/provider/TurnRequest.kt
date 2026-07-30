package com.konductor.provider

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry

data class TurnRequest(
    val context: AgentContext,
    val history: List<Entry>,
)
