package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClientBuilder
import com.konductor.config.Configuration
import com.openai.client.OpenAIClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Inference client bound to a persisted **PromptAgent** (M2.5). It holds the agent-scoped openai client —
 * `{endpoint}/agents/{name}/endpoint/protocols/openai`, built with `allowPreview(true)` for the required agent
 * preview-features header — and sends an **input-only** request: the agent supplies model + instructions + tools
 * from its baked definition, and the endpoint *rejects* them on the request (`400 "Not allowed when agent is
 * specified"`, verified live; matches the SDK's `tools/AgentToAgentSync` sample which invokes with just `input`).
 *
 * Because `instructions` cannot be sent, the per-turn **dynamic preamble** (environment header + context files)
 * rides the transcript as a leading `developer` input item — the only way to keep cwd/os/date current, since the
 * agent's baked instructions freeze at create time.
 *
 * A sibling of the ephemeral [AzureInferenceClient] (not a branch inside it): `ProviderFactory` /
 * `SwappableInferenceClient` pick which one to hold. Both share the pure mapping in `ResponsesMapping.kt`.
 */
class AzurePromptAgentInferenceClient(
    configuration: Configuration,
    private val agentName: String,
) : InferenceClient {

    private val client: OpenAIClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .allowPreview(true)
        .buildAgentScopedOpenAIClient(agentName)

    override suspend fun respond(request: InferenceRequest): InferenceResponse =
        client.respondInference(buildParams(request))

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> =
        client.streamInference(buildParams(request))

    override suspend fun close() {
        withContext(Dispatchers.IO) { client.close() }
    }

    /** Input-only request: only the transcript, with the dynamic preamble prepended as a leading developer item. */
    private fun buildParams(request: InferenceRequest): ResponseCreateParams =
        ResponseCreateParams.builder()
            .input(ResponseCreateParams.Input.ofResponse(buildInput(request)))
            .build()

    private fun buildInput(request: InferenceRequest): List<ResponseInputItem> {
        val history = serializeHistory(request.history)
        return if (request.dynamicPreamble.isNotBlank()) {
            listOf(responsesMessage(EasyInputMessage.Role.DEVELOPER, request.dynamicPreamble)) + history
        } else {
            history
        }
    }
}
