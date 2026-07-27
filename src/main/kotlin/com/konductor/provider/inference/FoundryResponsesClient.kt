package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow

/**
 * One-call adapter over Foundry Responses for the Prompt provider.
 *
 * Implementations isolate Foundry SDK request shapes, mapping, lifecycle, preview churn, and deterministic tests.
 * This is not an extension point for another inference service or backend.
 */
interface FoundryResponsesClient {
    suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult
    fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent>
    suspend fun close()
}
