package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow

interface InferenceClient {
    suspend fun respond(request: InferenceRequest): InferenceResponse
    fun respondStream(request: InferenceRequest): Flow<InferenceChunk>
    suspend fun close()
}
