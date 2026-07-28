package com.konductor.provider

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResult
import com.konductor.core.models.ToolResultEntry
import com.konductor.provider.inference.FoundryResponsesEvent
import com.konductor.provider.inference.FoundryResponsesClient
import com.konductor.provider.inference.FoundryResponsesRequest
import com.konductor.provider.inference.FoundryResponsesResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Foundry Prompt-kind [AgentProvider]: owns the client-side tool loop while delegating each Responses call to an
 * injected [FoundryResponsesClient]. That Foundry SDK adapter isolates mapping and lifecycle while keeping the loop
 * testable; it is not a non-Foundry backend extension point.
 *
 * This sits on the loop-ownership axis (above [FoundryResponsesClient], not an instance of it); see the two-seam
 * design in docs/spec/architecture.md#two-axes-two-seams and the loop in
 * docs/spec/providers.md#the-harness-owned-loop-foundry-sdk-decoupled.
 *
 * Each model call is **streamed**: text deltas are relayed as `AgentEvent.TextDelta` for a responsive UI,
 * and the terminal [FoundryResponsesEvent.Completed] carries the aggregated response used to finish the turn. The
 * loop re-requests until the model returns a final answer (no tool calls), appending `ToolCall`/`ToolResult`
 * entries to a working copy of the history between requests. M1 sends no tools, so the tool branch stays
 * dormant (a single streamed request → deltas + `UsageReported` + `TurnCompleted`); tools land in M2.
 */
class PromptProvider(
    private val responsesClient: FoundryResponsesClient,
    // Guards against a model that never converges (e.g. re-issuing a failing edit forever). The production
    // value is threaded from Configuration.maxToolIterations via ProviderFactory; this default only applies to
    // direct construction (tests).
    private val maxToolIterations: Int = 30,
) : AgentProvider {
    override val kind: AgentKind = AgentKind.Prompt
    override val capabilities: ProviderCapabilities =
        ProviderCapabilities.prompt(promptAgentManagement = false)

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        val history: MutableList<Entry> = request.history.toMutableList()

        // B (duplicate short-circuit): remember only the *immediately preceding* tool call, so a tight spin
        // (the model re-issuing the identical failing call) is caught, while a legitimate re-read after an edit
        // — read -> edit -> read — is not, because those calls are not adjacent.
        var previousCallKey: Pair<String, String>? = null
        var previousResult: ToolResult? = null
        var toolRounds = 0

        while (true) {
            var completed: FoundryResponsesResult? = null
            responsesClient.respondStreaming(
                FoundryResponsesRequest(
                    model = request.context.modelName,
                    systemPrompt = request.context.systemPrompt,
                    history = history.toList(),
                    tools = request.context.tools,
                    temperature = request.context.temperature,
                    dynamicPreamble = request.context.dynamicPreamble,
                ),
            ).collect { chunk ->
                when (chunk) {
                    is FoundryResponsesEvent.TextDelta -> emit(AgentEvent.TextDelta(chunk.text))
                    is FoundryResponsesEvent.Retrying -> emit(
                        AgentEvent.Retrying(
                            reason = chunk.reason,
                            retryAttempt = chunk.retryAttempt,
                            maxRetries = chunk.maxRetries,
                            delayMs = chunk.delayMs,
                        ),
                    )
                    is FoundryResponsesEvent.Completed -> completed = chunk.result
                }
            }
            val result = completed
                ?: error("Foundry Responses stream ended without a completed result")
            result.usage?.let { emit(AgentEvent.UsageReported(it)) }

            if (result.toolCalls.isEmpty()) {
                emit(AgentEvent.TurnCompleted(result.toAssistantEntry(parentId = history.lastOrNull()?.id)))
                return@flow
            }

            // A (iteration cap): this result wants another round of tools. Bound the number of rounds so a
            // model that never returns a final answer terminates the turn instead of looping unbounded.
            toolRounds++
            if (toolRounds > maxToolIterations) {
                emit(AgentEvent.TurnCompleted(cappedAssistantEntry(history.lastOrNull()?.id)))
                return@flow
            }

            for (call in result.toolCalls) {
                emit(AgentEvent.ToolCallStarted(call))
                val callKey = call.name to call.argumentsJson
                val toolResult = if (callKey == previousCallKey) {
                    duplicateCallNudge(call, previousResult) // skip re-executing an identical, adjacent call
                } else {
                    tools.execute(call)
                }
                emit(AgentEvent.ToolCallCompleted(call, toolResult))
                previousCallKey = callKey
                previousResult = toolResult

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
                    result = toolResult,
                )
            }
        }
    }.catch { error ->
        // `catch` is flow-exception-transparent and does not swallow cancellation: surface everything else
        // (auth/config/SDK/tool failures) as a Failed event so the UI can render it instead of freezing.
        emit(AgentEvent.Failed(error))
    }

    override suspend fun close() = responsesClient.close()

    private fun FoundryResponsesResult.toAssistantEntry(parentId: Uuid?): AssistantEntry =
        AssistantEntry(
            id = Uuid.random(),
            parentId = parentId,
            timestamp = Clock.System.now(),
            text = text,
            toolCalls = toolCalls,
            usage = usage,
        )

    /** Terminal assistant entry emitted when the tool-iteration cap (A) is hit, so the turn ends visibly. */
    private fun cappedAssistantEntry(parentId: Uuid?): AssistantEntry =
        AssistantEntry(
            id = Uuid.random(),
            parentId = parentId,
            timestamp = Clock.System.now(),
            text = "Stopped after $maxToolIterations tool iterations without reaching a final answer. The most " +
                "recent tool result is above — refine the request or try again.",
        )

    /** Synthetic result (B) for a call identical to the previous one: skip execution and nudge a new approach. */
    private fun duplicateCallNudge(call: ToolCall, previous: ToolResult?): ToolResult {
        val priorOutput = previous?.output?.take(NUDGE_PRIOR_OUTPUT_CHARS).orEmpty()
        val priorClause = if (priorOutput.isNotBlank()) " It previously returned:\n$priorOutput" else ""
        val hint = if (call.name == "edit") {
            " Re-read the file to copy the exact current text (including whitespace), or add more surrounding " +
                "context so oldString matches exactly once."
        } else {
            " Change the arguments or try a different tool."
        }
        return ToolResult(
            callId = call.callId,
            output = "${call.name}: skipped — identical to your previous call, so the result will not change." +
                "$priorClause$hint",
            isError = true,
        )
    }

    private companion object {
        const val NUDGE_PRIOR_OUTPUT_CHARS = 500
    }
}
