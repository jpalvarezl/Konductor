package com.konductor.foundry.project.connection

import com.azure.ai.projects.ConnectionsClient
import com.azure.ai.projects.models.Connection

/**
 * Azure AI Projects 2.3.0 adapter for [FoundryConnectionCatalog].
 *
 * The caller owns construction of [connectionsClient]. Named lookup always opts out of credential retrieval.
 * [Connection] SDK models stop at this adapter: [Connection.toFoundryConnection] explicitly copies only non-secret
 * metadata and the credential's authentication type into the SDK-free application DTO.
 */
class AzureFoundryConnectionCatalog(
    private val connectionsClient: ConnectionsClient,
) : FoundryConnectionCatalog {
    override fun listConnections(): List<FoundryConnection> =
        connectionsClient.listConnections().map(Connection::toFoundryConnection)

    override fun getConnection(name: String): FoundryConnection =
        connectionsClient.getConnection(name, false).toFoundryConnection()
}

/** Explicit SDK-to-application mapping; credential values have no destination field. */
private fun Connection.toFoundryConnection(): FoundryConnection = FoundryConnection(
    name = name,
    id = id,
    type = type.toString(),
    target = target,
    isDefault = isDefault,
    metadata = metadata?.toMap().orEmpty(),
    credentialType = credential?.type?.toString(),
)
