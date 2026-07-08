package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClientBuilder
import com.konductor.config.Configuration
import com.konductor.core.models.ToolSpec
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
 * The **ephemeral** Prompt inference client (the default, no persisted agent) and the AI-SDK chokepoint for that
 * path. Builds the Foundry Responses client against {projectEndpoint}/openai/v1 from a signed-in identity, owning
 * the blocking openai client (buildOpenAIClient()) directly rather than the Azure ResponsesAsyncClient wrapper
 * (which discards the closeable client and cannot release its executor). Streaming stays a plain flow over the
 * iterable StreamResponse on Dispatchers.IO. The Responses<->domain mapping is shared (ResponsesMapping.kt).
 *
 * Agent-agnostic by design: binding to a persisted PromptAgent is a *separate* client
 * ([AzurePromptAgentInferenceClient]) chosen by ProviderFactory/SwappableInferenceClient, never a branch here.
 */
class AzureInferenceClient(configuration: Configuration) : InferenceClient {

    private val client: OpenAIClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .buildOpenAIClient()

    override suspend fun respond(request: InferenceRequest): InferenceResponse =
        client.respondInference(buildParams(request))

    override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> =
        client.streamInference(buildParams(request))

    override suspend fun close() {
        withContext(Dispatchers.IO) { client.close() }
    }

    /** Full ephemeral request: model + instructions + tools + the reconstructed transcript. */
    private fun buildParams(request: InferenceRequest): ResponseCreateParams {
        val builder = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.systemPrompt)
            .input(ResponseCreateParams.Input.ofResponse(serializeHistory(request.history)))
        request.temperature?.let { builder.temperature(it) }
        request.tools.forEach { builder.addTool(it.toFunctionTool()) }
        return builder.build()
    }

    /**
     * A neutral [ToolSpec] -> SDK FunctionTool. strict = false: the built-in tools have optional parameters that
     * are intentionally absent from required, which OpenAI/Foundry strict mode forbids. Each top-level JSON-schema
     * key is converted to a neutral JsonValue via toPlainValue.
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

    private fun JsonElement.toPlainValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        is JsonObject -> mapValues { it.value.toPlainValue() }
        is JsonArray -> map { it.toPlainValue() }
    }
}
