package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClientBuilder
import com.konductor.config.Configuration
import kotlinx.coroutines.flow.Flow

class AzureInferenceClient(configuration: Configuration): InferenceClient{

    private val betaAgentsAsyncClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .beta().buildBetaAgentsAsyncClient()

    private val agentsAsyncClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .buildAgentsAsyncClient()

    override suspend fun respond(request: InferenceRequest): InferenceResponse {
        TODO("Not yet implemented")
    }

    override fun respondStream(request: InferenceRequest): Flow<InferenceChunk> {
        TODO("Not yet implemented")
    }

    override suspend fun close() {
        TODO("Not yet implemented")
    }

}
