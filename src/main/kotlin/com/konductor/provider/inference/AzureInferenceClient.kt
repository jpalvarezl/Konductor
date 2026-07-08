package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClient
import com.azure.ai.agents.AgentsClientBuilder
import com.azure.ai.agents.models.PromptAgentDefinition
import com.azure.ai.agents.models.FunctionTool as AzurePromptFunctionTool
import com.azure.core.util.BinaryData
import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
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
 * Persisted PromptAgents (M2.5, opt-in): when bound to an agent this builds the response client via
 * `buildAgentScopedOpenAIClient(name)` — the same `responses()` API, routed through the named agent so its baked
 * `instructions` apply — omits request `instructions`, and sends only the dynamic preamble per turn. It also
 * implements [PromptAgentClient] (list/create/bind) over the Agents lifecycle surface. Unbound, it is the
 * ephemeral Prompt path unchanged.
 */
class AzureInferenceClient(configuration: Configuration) : InferenceClient, PromptAgentClient {

    // One shared builder: the Agents lifecycle client and both flavors of the openai response client (ephemeral
    // or agent-scoped) are built from it, so a /agent rebind can rebuild just the response client.
    private val builder: AgentsClientBuilder = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)

    // The Agents surface, built lazily — only the opt-in /agent list/create lifecycle needs it, so ephemeral and
    // config-bound inference never construct it. AgentsClient is not Closeable, so there is nothing to dispose.
    private val agentsClient: AgentsClient by lazy { builder.buildAgentsClient() }

    // Which persisted PromptAgent (if any) the response client is bound to. @Volatile so a /agent rebind on the
    // UI thread is visible to a turn dispatched on Dispatchers.IO; rebinding only happens between turns.
    @Volatile
    private var boundAgentName: String? = configuration.promptAgentName?.trim()?.ifBlank { null }

    // The openai Responses client: agent-scoped when bound (so `responses()` routes through the agent and its
    // baked instructions apply), else the plain ephemeral client. Identical `responses()` API either way.
    @Volatile
    private var client: OpenAIClient = buildResponseClient(boundAgentName)

    private fun buildResponseClient(agentName: String?): OpenAIClient =
        if (agentName != null) builder.buildAgentScopedOpenAIClient(agentName) else builder.buildOpenAIClient()

    override suspend fun respond(request: InferenceRequest): InferenceResponse =
        withContext(Dispatchers.IO) {
            val responseClient = client
            toInferenceResponse(responseClient.responses().create(buildParams(request).build()))
        }

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> = flow {
        val responseClient = client
        responseClient.responses().createStreaming(buildParams(request).build()).use { stream ->
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

    override val activeAgentName: String? get() = boundAgentName

    override fun bindAgent(agentName: String?) {
        val normalized = agentName?.trim()?.ifBlank { null }
        if (normalized == boundAgentName) return
        val previous = client
        // Build the replacement before disposing the old client, so a build failure leaves the current one intact.
        client = buildResponseClient(normalized)
        boundAgentName = normalized
        previous.close()
    }

    override suspend fun listAgentNames(): List<String> =
        withContext(Dispatchers.IO) { agentsClient.listAgents().map { it.name }.distinct() }

    override suspend fun createAgentVersion(agentName: String, context: AgentContext): PromptAgentRef =
        withContext(Dispatchers.IO) {
            val definition = PromptAgentDefinition(context.modelName)
                .setInstructions(context.baseSystemPrompt)
                .setTools(context.tools.map { it.toAzurePromptTool() })
            context.temperature?.let { definition.setTemperature(it) }
            val created = agentsClient.createAgentVersion(agentName, definition)
            PromptAgentRef(created.name, created.version)
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
     * Map [InferenceRequest] → `ResponseCreateParams.Builder` (call sites `.build()` it). Tools are declared as
     * `FunctionTool`s every turn on both paths. Under a bound persisted PromptAgent the agent supplies the stable
     * `instructions`, so they are omitted and only the dynamic preamble rides `input` (see [buildInput]).
     */
    private fun buildParams(request: InferenceRequest): ResponseCreateParams.Builder {
        val builder = ResponseCreateParams.builder()
            .model(request.model)
            .input(ResponseCreateParams.Input.ofResponse(buildInput(request)))
        if (boundAgentName == null) builder.instructions(request.systemPrompt)
        request.temperature?.let { builder.temperature(it) }
        request.tools.forEach { builder.addTool(it.toFunctionTool()) }
        return builder
    }

    /**
     * The Responses `input`: the reconstructed transcript, preceded by the dynamic preamble as a leading developer
     * item when a persisted agent is bound (its baked instructions omit that per-turn cwd/env/context).
     */
    private fun buildInput(request: InferenceRequest): List<ResponseInputItem> {
        val history = serializeHistory(request.history)
        return if (boundAgentName != null && request.dynamicPreamble.isNotBlank()) {
            listOf(message(EasyInputMessage.Role.DEVELOPER, request.dynamicPreamble)) + history
        } else {
            history
        }
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
     * Map a neutral [ToolSpec] to an Agents `FunctionTool` for baking into a `PromptAgentDefinition`. Each
     * top-level JSON-schema key is carried as `BinaryData.fromObject` of its plain value so it serializes as
     * **structured JSON** (object/array/string) — mirroring [toFunctionTool]'s per-request mapping. (`fromString`
     * would double-encode each value as an escaped JSON *string*, baking a structurally-invalid schema.)
     */
    private fun ToolSpec.toAzurePromptTool(): AzurePromptFunctionTool =
        AzurePromptFunctionTool(name, parameters.mapValues { BinaryData.fromObject(it.value.toPlainValue()) }, false)
            .setDescription(description)

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
