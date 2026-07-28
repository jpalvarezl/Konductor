package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * A [FoundryResponsesClient] that switches between the ephemeral and PromptAgent-bound Foundry Responses adapters
 * by **rebuilding** its delegate when [bindAgent] changes the active PromptAgent (M2.5). It never mutates
 * [EphemeralFoundryResponsesClient]. Each call forwards to the selected adapter; binding builds a fresh adapter via
 * [factory] and closes the old one. This is request-shape selection within Foundry, not backend swappability.
 *
 * Threading: [delegate] is `@Volatile`; binding happens between turns (the TUI is synchronous), so a switch never
 * races an in-flight turn.
 */
class SwitchableFoundryResponsesClient(
    private val factory: (agentName: String?) -> FoundryResponsesClient,
    initialAgent: String? = null,
) : FoundryResponsesClient, PromptAgentBinder {

    @Volatile
    private var delegate: FoundryResponsesClient = factory(normalize(initialAgent))

    override var activeAgent: String? = normalize(initialAgent)
        private set

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult = delegate.respond(request)

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> =
        delegate.respondStreaming(request)

    override suspend fun close() = delegate.close()

    override fun bindAgent(agentName: String?) {
        val normalized = normalize(agentName)
        if (normalized == activeAgent) return
        val previous = delegate
        delegate = factory(normalized) // build the replacement before disposing the old one
        activeAgent = normalized
        // Disposal is best-effort: the swap has already taken effect, so a failure to close the previous client
        // must not surface as a bind failure (callers would then skip the UI/session-header update for a bind
        // that did happen).
        runCatching { runBlocking { previous.close() } }
    }

    /** Blank/whitespace means "no agent" (ephemeral), so it never binds an empty agent name. */
    private fun normalize(name: String?): String? = name?.trim()?.ifBlank { null }
}
