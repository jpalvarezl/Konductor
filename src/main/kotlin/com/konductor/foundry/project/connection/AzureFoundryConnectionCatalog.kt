package com.konductor.foundry.project.connection

import com.azure.ai.projects.ConnectionsClient
import com.azure.ai.projects.models.Connection

/**
 * Azure AI Projects 2.2.0 adapter for [FoundryConnectionCatalog].
 *
 * The caller owns construction of [connectionsClient]. Named lookup always opts out of credential retrieval, and the
 * mapping intentionally copies only non-secret connection metadata plus the credential type.
 */
class AzureFoundryConnectionCatalog(
    private val connectionsClient: ConnectionsClient,
) : FoundryConnectionCatalog {
    override fun listConnections(): List<FoundryConnection> =
        connectionsClient.listConnections().map(Connection::toFoundryConnection)

    override fun getConnection(name: String): FoundryConnection =
        connectionsClient.getConnection(name, false).toFoundryConnection()
}

private fun Connection.toFoundryConnection() = FoundryConnection(
    name = name,
    id = id,
    type = type.toString(),
    target = target,
    isDefault = isDefault,
    metadata = metadata?.toMap().orEmpty(),
    credentialType = credential?.type?.toString(),
)
