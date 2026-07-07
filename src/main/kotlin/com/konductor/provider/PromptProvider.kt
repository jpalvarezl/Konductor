package com.konductor.provider

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.provider.inference.InferenceChunk
import com.konductor.provider.inference.InferenceClient
import com.konductor.provider.inference.InferenceRequest
import com.konductor.provider.inference.InferenceResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Prompt-kind [AgentProvider]: owns the client-side tool loop while staying vendor-neutral by
 * delegating each individual model call to an injected [InferenceClient] — the sole SDK chokepoint.
 *
 * This sits on the loop-ownership axis (above [InferenceClient], not an instance of it); see the
 * two-seam design in docs/spec/architecture.md#two-axes-two-seams and the loop in
 * docs/spec/providers.md#the-harness-owned-loop-vendor-neutral.
 *
 * Each model call is **streamed**: text deltas are relayed as `AgentEvent.TextDelta` for a responsive UI,
 * and the terminal [InferenceChunk.Completed] carries the aggregated response used to finish the turn. The
 * loop re-requests until the model returns a final answer (no tool calls), appending `ToolCall`/`ToolResult`
 * entries to a working copy of the history between requests. M1 sends no tools, so the tool branch stays
 * dormant (a single streamed request → deltas + `UsageReported` + `TurnCompleted`); tools land in M2.
 */
class PromptProvider(private val inference: InferenceClient) : AgentProvider {
    override val kind: AgentKind = AgentKind.Prompt

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        val history: MutableList<Entry> = request.history.toMutableList()

        while (true) {
            var completed: InferenceResponse? = null
            inference.respondStreaming(
                InferenceRequest(
                    model = request.context.modelName,
                    systemPrompt = request.context.systemPrompt,
                    history = history.toList(),
                    tools = request.context.tools,
                    temperature = request.context.temperature,
                ),
            ).collect { chunk ->
                when (chunk) {
                    is InferenceChunk.TextDelta -> emit(AgentEvent.TextDelta(chunk.text))
                    is InferenceChunk.Completed -> completed = chunk.response
                }
            }
            val response = completed
                ?: error("inference stream ended without a completed response")
            response.usage?.let { emit(AgentEvent.UsageReported(it)) }

            if (response.toolCalls.isEmpty()) {
                emit(AgentEvent.TurnCompleted(response.toAssistantEntry(parentId = history.lastOrNull()?.id)))
                return@flow
            }

            for (call in response.toolCalls) {
                emit(AgentEvent.ToolCallStarted(call))
                val result = tools.execute(call)
                emit(AgentEvent.ToolCallCompleted(call, result))

                val callEntry = ToolCallEntry(
                    id = Uuid.random(),
                    parentId = history.lastOrNull()?.id,
                    timestamp = Clock.System.now(),
                    call = call,
                )
                history += callEntry
                history += ToolResultEntry(
                    id = Uuid.random(),
                    parentId = callEntry.id,
                    timestamp = Clock.System.now(),
                    result = result,
                )
            }
        }
    }.catch { error ->
        // `catch` is flow-exception-transparent and does not swallow cancellation: surface everything else
        // (auth/config/SDK/tool failures) as a Failed event so the UI can render it instead of freezing.
        emit(AgentEvent.Failed(error))
    }

    override suspend fun close() = inference.close()

    private fun InferenceResponse.toAssistantEntry(parentId: Uuid?): AssistantEntry =
        AssistantEntry(
            id = Uuid.random(),
            parentId = parentId,
            timestamp = Clock.System.now(),
            text = text,
            toolCalls = toolCalls,
            usage = usage,
        )
}
