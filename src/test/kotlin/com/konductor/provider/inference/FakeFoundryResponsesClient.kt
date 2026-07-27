package com.konductor.provider.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Test double for [FoundryResponsesClient]: returns queued [FoundryResponsesResult]s in order and records the
 * requests it received, so the Foundry Prompt loop can be exercised offline without network calls. If the result
 * queue is empty, it throws — which doubles as a convenient way to drive the provider's failure path.
 *
 * [respondStreaming] replays a queued result as one [FoundryResponsesEvent.TextDelta] (when text is non-empty),
 * followed by the terminal [FoundryResponsesEvent.Completed], mirroring the real streaming contract.
 */
class FakeFoundryResponsesClient(
    results: List<FoundryResponsesResult> = emptyList(),
) : FoundryResponsesClient {
    constructor(vararg results: FoundryResponsesResult) : this(results.toList())

    private val queued = ArrayDeque(results)

    val requests: MutableList<FoundryResponsesRequest> = mutableListOf()

    var closed: Boolean = false
        private set

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult {
        requests += request
        return nextResult()
    }

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
        requests += request
        val result = nextResult()
        if (result.text.isNotEmpty()) emit(FoundryResponsesEvent.TextDelta(result.text))
        emit(FoundryResponsesEvent.Completed(result))
    }

    override suspend fun close() {
        closed = true
    }

    private fun nextResult(): FoundryResponsesResult =
        queued.removeFirstOrNull()
            ?: error("FakeFoundryResponsesClient had no queued result for request #${requests.size}")
}
