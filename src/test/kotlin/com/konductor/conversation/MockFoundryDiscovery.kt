package com.konductor.conversation

import com.konductor.agent.AgentLoop
import com.konductor.core.AppState
import com.konductor.foundry.project.connection.FoundryConnection
import com.konductor.foundry.project.connection.FoundryConnectionCatalog
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.i18n.AppStrings

internal fun mockFoundryDiscovery(
    state: AppState,
    agentLoop: AgentLoop,
    deployments: MockFoundryDeploymentCatalog = MockFoundryDeploymentCatalog(),
    connections: MockFoundryConnectionCatalog = MockFoundryConnectionCatalog(),
    strings: AppStrings = AppStrings.english(),
): FoundryDiscoveryCommand = FoundryDiscoveryCommand(
    state,
    agentLoop,
    deployments,
    connections,
    strings,
)

internal class MockFoundryDeploymentCatalog(
    private val values: List<FoundryDeployment> = emptyList(),
    private val failure: Exception? = null,
    private val beforeList: (() -> Unit)? = null,
) : FoundryDeploymentCatalog {
    var listCalls: Int = 0
        private set

    override fun listDeployments(): List<FoundryDeployment> {
        listCalls++
        beforeList?.invoke()
        failure?.let { throw it }
        return values
    }

    override fun getDeployment(name: String): FoundryDeployment = values.single { it.name == name }
}

internal class MockFoundryConnectionCatalog(
    private val values: List<FoundryConnection> = emptyList(),
    private val failure: Exception? = null,
) : FoundryConnectionCatalog {
    override fun listConnections(): List<FoundryConnection> {
        failure?.let { throw it }
        return values
    }

    override fun getConnection(name: String): FoundryConnection = values.single { it.name == name }
}
