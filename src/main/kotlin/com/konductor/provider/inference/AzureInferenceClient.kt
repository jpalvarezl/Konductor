package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClientBuilder
import com.konductor.config.Configuration
import com.konductor.core.models.AssistantEntry
import com.konductor.core.models.CompactionEntry
import com.konductor.core.models.Entry
import com.konductor.core.models.ToolCall
import com.konductor.core.models.ToolCallEntry
import com.konductor.core.models.ToolResultEntry
import com.konductor.core.models.ToolSpec
import com.konductor.core.models.Usage
import com.konductor.core.models.UserEntry
import com.openai.core.JsonValue
import com.openai.client.OpenAIClient
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The AI-SDK chokepoint — the only class that imports the Foundry Responses/Agents surface (`com.openai...`
 * and `com.azure.ai...`). Identity/credential types (`com.azure.core.credential` / `com.azure.identity`) are
 * separate and owned by [Configuration], which handles auth.
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
     * Map [InferenceRequest] → `ResponseCreateParams.Builder` (call sites `.build()` it). Tools are declared
     * as `FunctionTool`s (M2); the `instructions`-omitting persisted-agent path lands in M2.5.
     */
    private fun buildParams(request: InferenceRequest): ResponseCreateParams.Builder {
        val builder = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.systemPrompt)
            .input(ResponseCreateParams.Input.ofResponse(serializeHistory(request.history)))
        request.temperature?.let { builder.temperature(it) }
        request.tools.forEach { builder.addTool(it.toFunctionTool()) }
        return builder
    }

    /**
     * A neutral [ToolSpec] → SDK `FunctionTool`. `strict = false`: the built-in tools have optional
     * parameters that are intentionally absent from `required`, which OpenAI/Foundry strict mode forbids
     * (it demands every property be required). The tool's JSON-schema [JsonObject] becomes the function
     * `parameters` — each top-level key converted to a neutral [JsonValue] via [toPlainValue].
     */
    private fun ToolSpec.toFunctionTool(): FunctionTool {
        val schema = FunctionTool.Parameters.builder()
        parameters.forEach { (key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value.toPlainValue())) }
        return FunctionTool.builder()
            .name(name)
            .description(description)
            .parameters(schema.build())
            .strict(false)
            .build()
    }

    /**
     * Convert a kotlinx-serialization [JsonElement] into the plain Kotlin structure (`Map`/`List`/`String`/
     * number/`Boolean`/`null`) that openai-java's [JsonValue.from] understands, so the tool schema crosses the
     * SDK boundary without leaking either JSON model.
     */
    private fun JsonElement.toPlainValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        is JsonObject -> mapValues { it.value.toPlainValue() }
        is JsonArray -> map { it.toPlainValue() }
    }

    /**
     * Reconstruct the transcript as Responses input items: user/assistant entries become messages, a
     * [ToolCallEntry] becomes a `function_call` item, and a [ToolResultEntry] becomes a `function_call_output`
     * matched to it by `callId`. `CompactionEntry` serialization arrives with M4.
     */
    private fun serializeHistory(history: List<Entry>): List<ResponseInputItem> =
        history.map { entry ->
            when (entry) {
                is UserEntry -> message(EasyInputMessage.Role.USER, entry.text)
                is AssistantEntry -> message(EasyInputMessage.Role.ASSISTANT, entry.text)
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
                is CompactionEntry ->
                    error("compaction serialization lands in M4; unexpected ${entry::class.simpleName}")
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
