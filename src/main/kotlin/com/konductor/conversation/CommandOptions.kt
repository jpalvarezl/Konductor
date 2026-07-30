package com.konductor.conversation

import com.konductor.foundry.project.deployment.FOUNDRY_MODEL_DEPLOYMENT_TYPE
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.i18n.AppStrings
import com.konductor.provider.ProviderManagement
import com.konductor.session.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Application-owned selectable value returned by a suspend command option provider. */
data class CommandOption(
    val value: String,
    val label: String = value,
    val detail: String? = null,
)

/**
 * Suspend option discovery kept separate from [TuiCommand]: dispatch does not depend on completion and providers
 * return application DTOs rather than frontend, Lanterna, or Azure SDK types.
 */
fun interface CommandOptionProvider {
    suspend fun loadOptions(): List<CommandOption>
}

/** SDK-free `/model` option adapter over the application-facing Foundry deployment catalog. */
class FoundryModelOptionProvider(
    private val deployments: FoundryDeploymentCatalog,
) : CommandOptionProvider {
    override suspend fun loadOptions(): List<CommandOption> = withContext(Dispatchers.IO) {
        deployments.listDeployments()
            .asSequence()
            .filter { it.type == FOUNDRY_MODEL_DEPLOYMENT_TYPE }
            .map { deployment ->
                CommandOption(
                    value = deployment.name,
                    detail = deployment.modelName,
                )
            }
            .toList()
    }
}

/** Read-only `/resume` option adapter over the active application's persisted-session index. */
class RecentSessionOptionProvider(
    private val listSessions: () -> List<SessionSummary>,
    private val strings: AppStrings = AppStrings.english(),
) : CommandOptionProvider {
    override suspend fun loadOptions(): List<CommandOption> = withContext(Dispatchers.IO) {
        listSessions()
            .sortedWith(compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.id.toString() })
            .map { session ->
                CommandOption(
                    value = session.id.toString(),
                    label = session.name ?: strings.unnamedSession,
                    detail = strings.paletteSessionDetail(
                        session.id.toString().take(8),
                        session.entryCount,
                        session.updatedAt.toString(),
                    ),
                )
            }
    }
}

/** Read-only `/agent use` option adapter over the managed PromptAgent lifecycle surface. */
class PromptAgentOptionProvider(
    private val management: ProviderManagement.PromptAgents,
    private val strings: AppStrings = AppStrings.english(),
) : CommandOptionProvider {
    override suspend fun loadOptions(): List<CommandOption> {
        val activeAgent = management.binder.activeAgent
        return management.lifecycle.listAgents().map { name ->
            CommandOption(
                value = name,
                detail = strings.palettePromptAgentDetail(name == activeAgent),
            )
        }
    }
}
