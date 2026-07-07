package com.konductor.provider

import com.konductor.core.models.AgentContext
import com.konductor.core.models.Entry

/** Opaque server-side session identifier used by the Hosted provider. */
typealias SessionRef = String

data class TurnRequest(
    val context: AgentContext,
    val history: List<Entry>,             // full client-owned transcript (Prompt provider)
    val sessionRef: SessionRef? = null,   // server-side session id (Hosted provider)
)
