package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Test double for [InferenceClient]: returns queued [InferenceResponse]s in order and records the requests
 * it received, so the vendor-neutral Prompt loop can be exercised offline. Running out of queued responses
 * throws — which doubles as a convenient way to drive the provider's failure path.
 *
 * [respondStreaming] replays a queued response as one [InferenceChunk.TextDelta] (when the text is non-empty)
 * followed by the terminal [InferenceChunk.Completed], mirroring the real streaming contract.
 */
class FakeInferenceClient(
    responses: List<InferenceResponse> = emptyList(),
) : InferenceClient {
    constructor(vararg responses: InferenceResponse) : this(responses.toList())

    private val queued = ArrayDeque(responses)

    val requests: MutableList<InferenceRequest> = mutableListOf()

    var closed: Boolean = false
        private set

    override suspend fun respond(request: InferenceRequest): InferenceResponse {
        requests += request
        return nextResponse()
    }

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
        requests += request
        val response = nextResponse()
        if (response.text.isNotEmpty()) emit(InferenceChunk.TextDelta(response.text))
        emit(InferenceChunk.Completed(response))
    }

    override suspend fun close() {
        closed = true
    }

    private fun nextResponse(): InferenceResponse =
        queued.removeFirstOrNull()
            ?: error("FakeInferenceClient had no queued response for request #${requests.size}")
}
