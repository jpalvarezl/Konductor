package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * An [InferenceClient] decorator that implements hot-swapping the bound PromptAgent (M2.5) by **rebuilding** the
 * delegate — never mutating [AzureInferenceClient]. Each call forwards to the current delegate; [bindAgent] builds
 * a fresh delegate for the new agent (via [factory]) and closes the old one. This keeps the SDK-facing inference
 * client agent-agnostic and immutable, isolating the mutable binding here.
 *
 * Threading: [delegate] is `@Volatile`; binding happens between turns (the TUI is synchronous), so a swap never
 * races an in-flight turn.
 */
class SwappableInferenceClient(
    private val factory: (agentName: String?) -> InferenceClient,
    initialAgent: String? = null,
) : InferenceClient, PromptAgentBinder {

    @Volatile
    private var delegate: InferenceClient = factory(normalize(initialAgent))

    override var activeAgent: String? = normalize(initialAgent)
        private set

    override suspend fun respond(request: InferenceRequest): InferenceResponse = delegate.respond(request)

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = delegate.respondStreaming(request)

    override suspend fun close() = delegate.close()

    override fun bindAgent(agentName: String?) {
        val normalized = normalize(agentName)
        if (normalized == activeAgent) return
        val previous = delegate
        delegate = factory(normalized) // build the replacement before disposing the old one
        activeAgent = normalized
        runBlocking { previous.close() }
    }

    /** Blank/whitespace means "no agent" (ephemeral), so it never binds an empty agent name. */
    private fun normalize(name: String?): String? = name?.trim()?.ifBlank { null }
}
