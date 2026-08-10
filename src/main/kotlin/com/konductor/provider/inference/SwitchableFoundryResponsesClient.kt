package com.konductor.provider.inference

import com.konductor.core.models.normalizePromptAgentName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/**
 * A [FoundryResponsesClient] that atomically switches one immutable PromptAgent-name/delegate holder. Preparation
 * builds an unpublished replacement while the old holder remains routable; commit publishes the pair in one volatile
 * assignment and disposes the superseded delegate best-effort. This is request-shape selection within Foundry, not
 * backend swappability.
 */
class SwitchableFoundryResponsesClient(
    private val factory: (agentName: String?) -> FoundryResponsesClient,
    initialAgent: String? = null,
) : FoundryResponsesClient, PromptAgentBinder {
    private data class Holder(
        val agentName: String?,
        val delegate: FoundryResponsesClient,
    )

    @Volatile
    private var holder: Holder = normalizePromptAgentName(initialAgent).let { Holder(it, factory(it)) }

    override val activeAgent: String?
        get() = holder.agentName

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult =
        holder.delegate.respond(request)

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> =
        holder.delegate.respondStreaming(request)

    override suspend fun close() = holder.delegate.close()

    override fun prepareBinding(agentName: String?): PreparedPromptAgentBinding {
        val normalized = normalizePromptAgentName(agentName)
        if (normalized == holder.agentName) {
            return PreparedPromptAgentBinding(normalized, commitAction = {})
        }

        // Construct the complete immutable candidate during the fallible prepare phase. After durable metadata
        // acceptance, commit performs no allocation: it only publishes this holder and suppresses old-client cleanup.
        val candidate = Holder(normalized, factory(normalized))
        return PreparedPromptAgentBinding(
            agentName = candidate.agentName,
            commitAction = {
                val previous = holder
                holder = candidate
                // The holder swap is already effective. Cleanup cannot turn it into a reported rejection.
                runCatching { runBlocking { previous.delegate.close() } }
            },
            abortAction = {
                runBlocking { candidate.delegate.close() }
            },
        )
    }
}
