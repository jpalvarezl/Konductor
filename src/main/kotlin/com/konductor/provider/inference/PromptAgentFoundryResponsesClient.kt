package com.konductor.provider.inference

import com.openai.client.OpenAIClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext

/**
 * Foundry Responses adapter bound to a persisted **PromptAgent** (M2.5). It owns the agent-scoped OpenAI client —
 * `{endpoint}/agents/{name}/endpoint/protocols/openai`, composed with `allowPreview(true)` for the required agent
 * preview-features header — and sends an **input-only** request: the agent supplies model + instructions + tools
 * from its baked definition, and the endpoint *rejects* them on the request (`400 "Not allowed when agent is
 * specified"`, verified live; matches the SDK's `tools/AgentToAgentSync` sample which invokes with just `input`).
 *
 * Because `instructions` cannot be sent, the per-turn **dynamic preamble** (rendered context block followed by the
 * environment header) rides the transcript as one leading `developer` item — the only way to keep cwd/os/date current, since the
 * agent's baked instructions freeze at create time.
 *
 * A sibling of the ephemeral [EphemeralFoundryResponsesClient] (not a branch inside it): `ProviderFactory` /
 * `SwitchableFoundryResponsesClient` pick which one to hold. Both share the pure mapping in `ResponsesMapping.kt`.
 */
class PromptAgentFoundryResponsesClient(
    private val client: OpenAIClient,
) : FoundryResponsesClient {

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult =
        try {
            client.createFoundryResponse(buildPromptAgentParams(request))
        } catch (error: Throwable) {
            throw mapFoundryResponsesFailure(error)
        }

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> =
        client.streamFoundryResponse(buildPromptAgentParams(request))
            .catch { error -> throw mapFoundryResponsesFailure(error) }

    override suspend fun close() {
        withContext(Dispatchers.IO) { client.close() }
    }

}

/** Input-only request: exactly one dynamic developer item followed by reconstructed transcript history. */
internal fun buildPromptAgentParams(request: FoundryResponsesRequest): ResponseCreateParams =
    ResponseCreateParams.builder()
        .input(ResponseCreateParams.Input.ofResponse(buildPromptAgentInput(request)))
        .build()

internal fun buildPromptAgentInput(request: FoundryResponsesRequest): List<ResponseInputItem> {
    val history = serializeHistory(request.history)
    return if (request.dynamicPreamble.isNotEmpty()) {
        listOf(responsesMessage(EasyInputMessage.Role.DEVELOPER, request.dynamicPreamble)) + history
    } else {
        history
    }
}
