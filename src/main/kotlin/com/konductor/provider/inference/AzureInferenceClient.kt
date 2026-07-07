package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClientBuilder
import com.konductor.config.Configuration
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.openai.client.OpenAIClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The single SDK chokepoint — the only class that imports `com.azure...` / `com.openai...`.
 *
 * Builds the Foundry Responses client from a signed-in identity against `{projectEndpoint}/openai/v1` (no
 * agent required — see the ephemeral path in docs/spec/providers.md). It owns the blocking **openai** client
 * (`buildOpenAIClient()`) directly rather than the Azure `ResponsesAsyncClient` wrapper: the wrapper discards
 * the underlying client and exposes no `close()`, so its HTTP/stream executor can never be released. Owning a
 * closeable client makes [close] able to dispose it (the blocking client's threads are daemon, so shutdown is
 * clean either way). The blocking API also keeps streaming to a plain `flow { emit }` over an iterable
 * `StreamResponse`, moved onto [Dispatchers.IO] so calls never block the caller's dispatcher.
 *
 * M2.5 note: the persisted-agent binding previously planned via `AzureCreateResponseOptions.setAgentReference`
 * (a wrapper-only option) must instead attach the agent reference to the request here (params/headers), since
 * this class no longer uses that wrapper.
 */
class AzureInferenceClient(configuration: Configuration) : InferenceClient {

    private val client: OpenAIClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .buildOpenAIClient()

    override suspend fun respond(request: InferenceRequest): InferenceResponse =
        withContext(Dispatchers.IO) {
            toInferenceResponse(client.responses().create(buildParams(request).build()))
        }

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
        client.responses().createStreaming(buildParams(request).build()).use { stream ->
            for (event in stream.stream().iterator()) {
                event.outputTextDelta().orElse(null)?.let {
                    emit(InferenceChunk.TextDelta(it.delta()))
                }
                event.completed().orElse(null)?.let {
                    emit(InferenceChunk.Completed(toInferenceResponse(it.response())))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        withContext(Dispatchers.IO) { client.close() }
    }

    private fun toInferenceResponse(response: Response): InferenceResponse {
        val toolCalls = response.output().mapNotNull { item ->
            item.functionCall().orElse(null)?.let { ToolCall(it.callId(), it.name(), it.arguments()) }
        }
        return InferenceResponse(
            text = extractText(response),
            toolCalls = toolCalls,
            usage = extractUsage(response),
        )
    }

    /**
     * Map [InferenceRequest] → `ResponseCreateParams.Builder` (call sites `.build()` it). M1 sends no tools;
     * the tool declarations land in M2 and the `instructions`-omitting persisted-agent path in M2.5.
     */
    private fun buildParams(request: InferenceRequest): ResponseCreateParams.Builder {
        val builder = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.systemPrompt)
            .input(ResponseCreateParams.Input.ofResponse(serializeHistory(request.history)))
        request.temperature?.let { builder.temperature(it) }
        return builder
    }

    /**
     * Reconstruct the transcript as Responses input items. M1 only ever sees user/assistant entries
     * (no tools yet); `ToolCallEntry`/`ToolResultEntry` serialization arrives with the M2 tool loop.
     */
    private fun serializeHistory(history: List<Entry>): List<ResponseInputItem> =
        history.map { entry ->
            when (entry) {
                is UserEntry -> message(EasyInputMessage.Role.USER, entry.text)
                is AssistantEntry -> message(EasyInputMessage.Role.ASSISTANT, entry.text)
                else -> error(
                    "M1 serializes only user/assistant entries; ${entry::class.simpleName} arrives with tools in M2.",
                )
            }
        }

    private fun message(role: EasyInputMessage.Role, text: String): ResponseInputItem =
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder().role(role).content(text).build(),
        )

    /** Concatenate the text of every output message (openai-java 4.14.0 has no `Response.outputText()`). */
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
}
