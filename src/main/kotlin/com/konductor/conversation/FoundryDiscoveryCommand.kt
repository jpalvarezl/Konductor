package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.ModelSwitchResult
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.i18n.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Handles the TUI's SDK-free Foundry project discovery surface: `/model`, `/model list`, validated model selection,
 * and `/connections`. Blocking catalog calls run on [Dispatchers.IO], and every [AppState] mutation is applied through
 * the caller's [StateApplier] so the Lanterna event loop remains responsive.
 */
class FoundryDiscoveryCommand(
    private val state: AppState,
    private val agentLoop: AgentLoop,
    private val deployments: FoundryDeploymentCatalog,
    private val connections: FoundryConnectionCatalog,
    private val strings: AppStrings = AppStrings.english(),
) {
    val modelCommand: TuiCommand = FunctionalTuiCommand(
        BuiltInCommandDescriptors.model,
        CommandAvailabilityProvider(::modelAvailability),
    ) { invocation ->
        CommandAction.Background { applier ->
            publish(evaluateModel(invocation.rawArguments.trim()), applier)
        }
    }

    val connectionsCommand: TuiCommand = FunctionalTuiCommand(BuiltInCommandDescriptors.connections) { invocation ->
        if (invocation.rawArguments.isBlank()) {
            CommandAction.Background { applier -> publish(listConnections(), applier) }
        } else {
            // `/connections` was historically exact-only; preserve fallthrough for extra arguments.
            CommandAction.NotHandled
        }
    }

    private fun modelAvailability(): CommandAvailability = when (val restriction = agentLoop.modelSwitchRestriction()) {
        null -> CommandAvailability.Enabled
        ModelSwitchResult.Unsupported -> CommandAvailability.Disabled(strings.paletteModelUnavailable)
        is ModelSwitchResult.FixedByPromptAgent ->
            CommandAvailability.Disabled(strings.paletteModelFixedByAgent(restriction.agentName))
        is ModelSwitchResult.Invalid, is ModelSwitchResult.Switched -> CommandAvailability.Enabled
    }

    private suspend fun evaluateModel(argument: String): Effect = when {
        argument.isEmpty() -> Effect(strings.activeModel(agentLoop.modelName))
        argument.equals("list", ignoreCase = true) -> listDeployments()
        else -> switchModel(argument)
    }

    /** Publish exactly one localized system message through the controller-provided state boundary. */
    private fun publish(effect: Effect, applier: StateApplier) {
        applier {
            effect.modelName?.let {
                state.modelName = it
                state.lastUsage = null
            }
            state.addMessage(ChatMessage(MessageRole.System, effect.message))
        }
    }

    private suspend fun listDeployments(): Effect = discoverDeployments(
        onFailure = { reason -> Effect(strings.modelDiscoveryFailed(reason, agentLoop.modelName)) },
    ) { available ->
        if (available.isEmpty()) {
            Effect(strings.noModelDeployments)
        } else {
            val items = available.joinToString("\n", transform = ::deploymentLine)
            Effect(strings.modelDeployments(items))
        }
    }

    private suspend fun switchModel(name: String): Effect {
        agentLoop.modelSwitchRestriction()?.let { return renderSwitchResult(it) }
        return discoverDeployments(
            onFailure = { reason -> switchWithoutValidation(name, reason) },
        ) { available ->
            if (available.none { it.name == name }) {
                Effect(strings.modelDeploymentNotFound(name, agentLoop.modelName))
            } else {
                renderSwitchResult(agentLoop.switchModel(name))
            }
        }
    }

    private suspend fun listConnections(): Effect = try {
        val available = withContext(Dispatchers.IO) { connections.listConnections() }
        if (available.isEmpty()) {
            Effect(strings.noFoundryConnections)
        } else {
            val items = available.joinToString("\n", transform = ::connectionLine)
            Effect(strings.foundryConnections(items))
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        Effect(strings.connectionDiscoveryFailed)
    }

    private suspend fun discoverDeployments(
        onFailure: (String) -> Effect,
        onSuccess: (List<FoundryDeployment>) -> Effect,
    ): Effect = try {
        val available = withContext(Dispatchers.IO) { deployments.listDeployments() }
            .filter { it.type == MODEL_DEPLOYMENT_TYPE }
        currentCoroutineContext().ensureActive()
        onSuccess(available)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        onFailure(errorReason(error))
    }

    private fun deploymentLine(deployment: FoundryDeployment): String = strings.modelDeploymentItem(
        deployment.name,
        deployment.modelName ?: strings.statusUnavailable,
        deployment.modelVersion ?: strings.statusUnavailable,
        deployment.modelPublisher ?: strings.statusUnavailable,
        deployment.type,
    )

    private fun connectionLine(connection: FoundryConnection): String = strings.foundryConnectionItem(
        connection.name,
        connection.type,
        connection.target,
        connection.credentialType ?: strings.statusUnavailable,
        if (connection.isDefault) strings.yes else strings.no,
    )

    private fun switchWithoutValidation(name: String, reason: String): Effect =
        when (val result = agentLoop.switchModel(name)) {
            is ModelSwitchResult.Switched -> Effect(
                strings.modelSwitchedWithoutValidation(result.previous, result.current, reason),
                modelName = result.current,
            )
            else -> renderSwitchResult(result)
        }

    private fun renderSwitchResult(result: ModelSwitchResult): Effect = when (result) {
        is ModelSwitchResult.Switched ->
            Effect(strings.modelSwitched(result.previous, result.current), modelName = result.current)
        ModelSwitchResult.Unsupported -> Effect(strings.modelUnsupported)
        is ModelSwitchResult.FixedByPromptAgent -> Effect(strings.modelFixedByAgent(result.agentName))
        is ModelSwitchResult.Invalid -> Effect(strings.modelSwitchFailed(errorReason(result.error)))
    }

    private fun errorReason(error: Throwable): String =
        error.message ?: error::class.simpleName ?: strings.unknownError

    private data class Effect(
        val message: String,
        val modelName: String? = null,
    )

    companion object {
        private const val MODEL_DEPLOYMENT_TYPE = "ModelDeployment"

        /** Empty catalogs for controller-only tests and embedders that do not compose a Foundry project runtime. */
        fun empty(
            state: AppState,
            agentLoop: AgentLoop,
            strings: AppStrings = AppStrings.english(),
        ): FoundryDiscoveryCommand = FoundryDiscoveryCommand(
            state,
            agentLoop,
            deployments = object : FoundryDeploymentCatalog {
                override fun listDeployments(): List<FoundryDeployment> = emptyList()
                override fun getDeployment(name: String): FoundryDeployment = throw NoSuchElementException(name)
            },
            connections = object : FoundryConnectionCatalog {
                override fun listConnections(): List<FoundryConnection> = emptyList()
                override fun getConnection(name: String): FoundryConnection = throw NoSuchElementException(name)
            },
            strings,
        )
    }
}
