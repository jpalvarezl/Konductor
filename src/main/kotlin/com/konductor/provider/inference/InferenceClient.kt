package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow

interface InferenceClient {
    suspend fun respond(request: InferenceRequest): InferenceResponse
    fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk>
    suspend fun close()
}
