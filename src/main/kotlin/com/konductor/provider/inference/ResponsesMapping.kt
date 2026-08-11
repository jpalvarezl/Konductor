package com.konductor.provider.inference

import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.openai.client.OpenAIClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Pure Foundry Responses <-> Konductor-domain mapping + call helpers shared by the ephemeral
 * [EphemeralFoundryResponsesClient] and the agent-scoped [PromptAgentFoundryResponsesClient]. Kept as free functions
 * (no state) so the two client implementations stay independent without duplicating the SDK-boundary translation;
 * each client differs only in *which* openai client it holds and *what* request it builds.
 */

/** Run one blocking Foundry Responses call on [Dispatchers.IO] and map its application result. */
internal suspend fun OpenAIClient.createFoundryResponse(params: ResponseCreateParams): FoundryResponsesResult =
    withContext(Dispatchers.IO) { toFoundryResponsesResult(responses().create(params)) }

/**
 * Stream a Responses call as [FoundryResponsesEvent]s: text deltas, then a terminal
 * [FoundryResponsesEvent.Completed].
 */
internal fun OpenAIClient.streamFoundryResponse(params: ResponseCreateParams): Flow<FoundryResponsesEvent> = flow {
    responses().createStreaming(params).use { stream ->
        for (event in stream.stream().iterator()) {
            event.outputTextDelta().orElse(null)?.let { emit(FoundryResponsesEvent.TextDelta(it.delta())) }
            event.completed().orElse(null)?.let {
                emit(FoundryResponsesEvent.Completed(toFoundryResponsesResult(it.response())))
            }
        }
    }
}.flowOn(Dispatchers.IO)

/**
 * Reconstruct the transcript as Responses input items: user/assistant entries become messages, a
 * [ToolCallEntry] becomes a `function_call` item, and a [ToolResultEntry] becomes a `function_call_output`
 * matched to it by `callId`. A [CompactionEntry] becomes a leading `developer` message carrying the summary
 * of the older turns it replaced (its kept-entry slicing already happened in `reconstructHistory`).
 */
internal fun serializeHistory(history: List<Entry>): List<ResponseInputItem> =
    history.map { entry ->
        when (entry) {
            is UserEntry -> responsesMessage(EasyInputMessage.Role.USER, entry.text)
            is AssistantEntry -> responsesMessage(EasyInputMessage.Role.ASSISTANT, entry.text)
            is ToolCallEntry -> ResponseInputItem.ofFunctionCall(
                ResponseFunctionToolCall.builder()
                    .callId(entry.call.callId)
                    .name(entry.call.name)
                    .arguments(entry.call.argumentsJson)
                    .build(),
            )
            is ToolResultEntry -> ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(entry.result.callId)
                    .output(entry.result.output)
                    .build(),
            )
            is CompactionEntry -> responsesMessage(
                EasyInputMessage.Role.DEVELOPER,
                "Summary of earlier conversation (older turns were compacted to save context):\n\n" +
                    entry.summary,
            )
        }
    }

/** Build a single Responses input message for [role] carrying [text]. */
internal fun responsesMessage(role: EasyInputMessage.Role, text: String): ResponseInputItem =
    ResponseInputItem.ofEasyInputMessage(
        // `type` is set explicitly (rather than relying on the "message" default) to work around a Foundry
        // agent-scoped endpoint bug: it defaults a missing item type to "message" for user/assistant but to
        // "" for developer/system, then rejects the ""; see docs/service_feedback/prompt_agents.md #6.
        EasyInputMessage.builder().role(role).content(text).type(EasyInputMessage.Type.MESSAGE).build(),
    )

/** Map a Foundry [Response] to the application [FoundryResponsesResult] (text, tool calls, and usage). */
internal fun toFoundryResponsesResult(response: Response): FoundryResponsesResult =
    FoundryResponsesResult(
        text = extractText(response),
        toolCalls = response.output().mapNotNull { item ->
            item.functionCall().orElse(null)?.let { ToolCall(it.callId(), it.name(), it.arguments()) }
        },
        usage = extractUsage(response),
    )

/** Concatenate the text of every output message (openai-java 4.45.0 still has no `Response.outputText()`). */
private fun extractText(response: Response): String = buildString {
    for (item in response.output()) {
        val outputMessage = item.message().orElse(null) ?: continue
        for (content in outputMessage.content()) {
            content.outputText().orElse(null)?.let { append(it.text()) }
        }
    }
}

private fun extractUsage(response: Response): Usage? =
    response.usage().orElse(null)?.let {
        Usage(
            inputTokens = it.inputTokens().toInt(),
            outputTokens = it.outputTokens().toInt(),
            totalTokens = it.totalTokens().toInt(),
        )
    }
