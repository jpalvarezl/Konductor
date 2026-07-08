package com.konductor.provider.inference

import com.azure.ai.agents.AgentsClient
import com.azure.ai.agents.AgentsClientBuilder
import com.azure.ai.agents.models.FunctionTool as AzureFunctionTool
import com.azure.ai.agents.models.PromptAgentDefinition
import com.azure.core.util.BinaryData
import com.konductor.config.Configuration
import com.konductor.core.models.ToolSpec
import kotlinx.coroutines.Dispatchers
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
 * Azure implementation of the persisted PromptAgent lifecycle ([PromptAgentClient]) — the SDK boundary for the
 * Agents *management* surface (`AgentsClient`), kept separate from inference. Blocking SDK calls run on
 * [Dispatchers.IO].
 */
class AzurePromptAgentClient(configuration: Configuration) : PromptAgentClient {
    private val agentsClient: AgentsClient = AgentsClientBuilder()
        .endpoint(configuration.projectEndpoint)
        .credential(configuration.tokenCredential)
        .buildAgentsClient()

    override suspend fun listAgents(): List<String> =
        withContext(Dispatchers.IO) { agentsClient.listAgents().map { it.name }.distinct() }

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = withContext(Dispatchers.IO) {
        val definition = PromptAgentDefinition(model)
            .setInstructions(instructions)
            .setTools(tools.map { it.toAzureTool() })
        val created = agentsClient.createAgentVersion(name, definition)
        PromptAgentRef(created.name, created.version)
    }

    /**
     * Map a neutral [ToolSpec] to an Agents `FunctionTool`. Each top-level JSON-schema key is carried as
     * `BinaryData.fromObject` of its plain value so it serializes as **structured JSON** (object/array/string) —
     * `fromString` would double-encode each value as an escaped JSON *string*, baking an invalid schema.
     */
    private fun ToolSpec.toAzureTool(): AzureFunctionTool =
        AzureFunctionTool(name, parameters.mapValues { BinaryData.fromObject(it.value.toPlainValue()) }, false)
            .setDescription(description)

    private fun JsonElement.toPlainValue(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
        is JsonObject -> mapValues { it.value.toPlainValue() }
        is JsonArray -> map { it.toPlainValue() }
    }
}
