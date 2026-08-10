package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.agent.ModelSwitchPreparation
import com.konductor.agent.ModelSwitchResult
import com.konductor.core.AppState
import com.konductor.core.ChatMessage
import com.konductor.core.MessageRole
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FOUNDRY_MODEL_DEPLOYMENT_TYPE
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
class FoundryDiscoveryCommand private constructor(
    private val state: AppState,
    private val agentLoop: AgentLoop,
    private val discovery: DiscoveryComposition,
    private val strings: AppStrings,
) {
    constructor(
        state: AppState,
        agentLoop: AgentLoop,
        deployments: FoundryDeploymentCatalog,
        connections: FoundryConnectionCatalog,
        strings: AppStrings = AppStrings.english(),
    ) : this(state, agentLoop, DiscoveryComposition.Available(deployments, connections), strings)

    val modelCommand: TuiCommand = FunctionalTuiCommand(
        BuiltInCommandDescriptors.model,
        CommandAvailabilityProvider(::modelAvailability),
    ) { invocation ->
        CommandAction.Background { command ->
            commit(evaluateModel(invocation.rawArguments.trim()), command)
        }
    }

    val connectionsCommand: TuiCommand = FunctionalTuiCommand(BuiltInCommandDescriptors.connections) { invocation ->
        if (invocation.rawArguments.isBlank()) {
            CommandAction.Background { command ->
                commit(PreparedEffect.Message(listConnections()), command)
            }
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

    private suspend fun evaluateModel(argument: String): PreparedEffect = when {
        argument.isEmpty() -> PreparedEffect.Message(Effect(strings.activeModel(agentLoop.modelName)))
        argument.equals("list", ignoreCase = true) -> PreparedEffect.Message(listDeployments())
        else -> switchModel(argument)
    }

    /** Cross the one command gate before either read-only publication or model persistence/live publication. */
    private suspend fun commit(prepared: PreparedEffect, command: LocalCommandContext) {
        command.commit {
            when (prepared) {
                is PreparedEffect.Message -> apply { state.publish(prepared.effect) }
                is PreparedEffect.Switch -> try {
                    val switched = agentLoop.commitModelSwitch(prepared.preparation)
                    apply { state.publish(prepared.render(switched)) }
                } catch (error: Exception) {
                    fail { state.publish(Effect(strings.modelSwitchFailed(errorReason(error)))) }
                }
            }
        }
    }

    /** Publish exactly one localized system message inside an accepted command commit. */
    private fun AppState.publish(effect: Effect) {
        effect.modelName?.let {
            modelName = it
            lastUsage = null
        }
        addMessage(ChatMessage(MessageRole.System, effect.message))
    }

    private suspend fun listDeployments(): Effect = when (val composition = discovery) {
        DiscoveryComposition.Unavailable -> Effect(strings.modelDiscoveryUnavailable)
        is DiscoveryComposition.Available -> discoverDeployments(
            composition.deployments,
            onFailure = { reason -> Effect(strings.modelDiscoveryFailed(reason, agentLoop.modelName)) },
        ) { available ->
            if (available.isEmpty()) {
                Effect(strings.noModelDeployments)
            } else {
                val items = available.joinToString("\n", transform = ::deploymentLine)
                Effect(strings.modelDeployments(items))
            }
        }
    }

    private suspend fun switchModel(name: String): PreparedEffect {
        agentLoop.modelSwitchRestriction()?.let {
            return PreparedEffect.Message(renderSwitchResult(it))
        }
        return when (val composition = discovery) {
            DiscoveryComposition.Unavailable -> prepareSwitch(name, strings::modelSwitchedWithoutDiscovery)
            is DiscoveryComposition.Available -> discoverDeployments(
                composition.deployments,
                onFailure = { reason ->
                    prepareSwitch(name) { previous, current ->
                        strings.modelSwitchedWithoutValidation(previous, current, reason)
                    }
                },
            ) { available ->
                if (available.none { it.name == name }) {
                    PreparedEffect.Message(Effect(strings.modelDeploymentNotFound(name, agentLoop.modelName)))
                } else {
                    prepareSwitch(name, strings::modelSwitched)
                }
            }
        }
    }

    private suspend fun listConnections(): Effect = when (val composition = discovery) {
        DiscoveryComposition.Unavailable -> Effect(strings.connectionDiscoveryUnavailable)
        is DiscoveryComposition.Available -> try {
            val available = withContext(Dispatchers.IO) { composition.connections.listConnections() }
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
    }

    private suspend fun <T> discoverDeployments(
        deployments: FoundryDeploymentCatalog,
        onFailure: (String) -> T,
        onSuccess: (List<FoundryDeployment>) -> T,
    ): T = try {
        val available = withContext(Dispatchers.IO) { deployments.listDeployments() }
            .filter { it.type == FOUNDRY_MODEL_DEPLOYMENT_TYPE }
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

    private fun prepareSwitch(
        name: String,
        switchedMessage: (String, String) -> String,
    ): PreparedEffect = when (val preparation = agentLoop.prepareModelSwitch(name)) {
        is ModelSwitchPreparation.Ready -> PreparedEffect.Switch(preparation) { switched ->
            Effect(
                switchedMessage(switched.previous, switched.current),
                modelName = switched.current,
            )
        }
        is ModelSwitchPreparation.Rejected -> PreparedEffect.Message(renderSwitchResult(preparation.result))
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

    private sealed interface PreparedEffect {
        data class Message(val effect: Effect) : PreparedEffect
        data class Switch(
            val preparation: ModelSwitchPreparation.Ready,
            val render: (ModelSwitchResult.Switched) -> Effect,
        ) : PreparedEffect
    }

    private sealed interface DiscoveryComposition {
        data class Available(
            val deployments: FoundryDeploymentCatalog,
            val connections: FoundryConnectionCatalog,
        ) : DiscoveryComposition

        data object Unavailable : DiscoveryComposition
    }

    companion object {
        /**
         * Explicit composition for an embedder that intentionally has no Foundry project discovery surface.
         * Unlike valid empty catalogs, commands explain that discovery was not supplied.
         */
        fun unavailable(
            state: AppState,
            agentLoop: AgentLoop,
            strings: AppStrings = AppStrings.english(),
        ): FoundryDiscoveryCommand = FoundryDiscoveryCommand(
            state,
            agentLoop,
            DiscoveryComposition.Unavailable,
            strings,
        )
    }
}
