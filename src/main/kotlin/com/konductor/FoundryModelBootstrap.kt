package com.konductor

import com.konductor.config.BootstrapConfig
import com.konductor.config.Configuration
import com.konductor.conversation.CommandOption
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentKind
import com.konductor.tui.StartupModelSelectorResult
import kotlinx.coroutines.CancellationException

/** Process-local result of resolving a final TUI model before provider or session construction. */
internal sealed interface FoundryModelBootstrapResult {
    data class Ready(
        val configuration: Configuration,
        val startupSystemMessages: List<String> = emptyList(),
    ) : FoundryModelBootstrapResult

    data class Failure(val message: String) : FoundryModelBootstrapResult
    data object Cancelled : FoundryModelBootstrapResult
}

/**
 * Finalize a bootstrap configuration, consulting the deployment catalog only for an unresolved fresh Prompt run.
 * The callbacks keep catalog and terminal ownership outside this Lanterna/SDK-free decision seam.
 */
internal fun resolveFoundryModelBootstrap(
    bootstrap: BootstrapConfig,
    strings: AppStrings,
    loadModelOptions: () -> List<CommandOption>,
    selectModel: (List<CommandOption>) -> StartupModelSelectorResult,
): FoundryModelBootstrapResult {
    if (bootstrap.agentKind != AgentKind.Prompt || bootstrap.model != null) {
        return FoundryModelBootstrapResult.Ready(Configuration.finalizeBootstrap(bootstrap))
    }

    val options = try {
        loadModelOptions()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return FoundryModelBootstrapResult.Failure(strings.startupModelDiscoveryFailed)
    }

    return when (options.size) {
        0 -> FoundryModelBootstrapResult.Failure(strings.startupModelNoDeployments)
        1 -> {
            val model = options.single().value
            FoundryModelBootstrapResult.Ready(
                Configuration.finalizeBootstrap(bootstrap, model),
                listOf(strings.startupModelAutoSelected(model)),
            )
        }
        else -> when (val selected = try {
            selectModel(options)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return FoundryModelBootstrapResult.Failure(strings.startupModelDiscoveryFailed)
        }) {
            StartupModelSelectorResult.Cancelled -> FoundryModelBootstrapResult.Cancelled
            is StartupModelSelectorResult.Selected -> FoundryModelBootstrapResult.Ready(
                Configuration.finalizeBootstrap(bootstrap, selected.value),
            )
        }
    }
}
