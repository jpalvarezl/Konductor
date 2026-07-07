package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClientBuilder
import com.azure.ai.agents.ResponsesAsyncClient
import com.konductor.config.Configuration
import kotlinx.coroutines.flow.Flow

/**
 * The single SDK chokepoint — the only class that imports `com.azure...` / `com.openai...`.
 *
 * Builds the Foundry **Responses** async client from a signed-in identity against
 * `{projectEndpoint}/openai/v1` (no agent required — see the ephemeral path in
 * docs/spec/providers.md). Request/response mapping and the model call land in M1 (`respond`),
 * streaming in M6 (`respondStreaming`), and the optional persisted-agent binding in M2.5.
 */
class AzureInferenceClient(configuration: Configuration) : InferenceClient {

    private val responses: ResponsesAsyncClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .buildResponsesAsyncClient()

    override suspend fun respond(request: InferenceRequest): InferenceResponse =
        TODO("M1: responses.createAzureResponse(AzureCreateResponseOptions(), buildParams(request)) -> InferenceResponse")

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> =
        TODO("M6: responses.createStreamingAzureResponse(...).asFlow() -> InferenceChunk")

    override suspend fun close() {
        // No client-owned resources to release yet; wired in M1 if the SDK client needs disposal.
    }
}
