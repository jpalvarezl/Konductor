package com.konductor.foundry.project.connection

/**
 * Non-secret metadata for a connection configured in the current Azure AI Foundry project.
 *
 * This application model deliberately represents SDK expandable enums as strings and carries only the credential
 * authentication type. It has no field in which a credential value can be stored.
 */
data class FoundryConnection(
    val name: String,
    val id: String,
    val type: String,
    val target: String,
    val isDefault: Boolean,
    val metadata: Map<String, String>,
    val credentialType: String?,
)

/** Fakeable application boundary for credential-safe Foundry project connection discovery. */
interface FoundryConnectionCatalog {
    fun listConnections(): List<FoundryConnection>

    fun getConnection(name: String): FoundryConnection
}
