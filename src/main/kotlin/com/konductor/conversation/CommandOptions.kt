package com.konductor.conversation

import com.konductor.foundry.project.deployment.FOUNDRY_MODEL_DEPLOYMENT_TYPE
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
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
