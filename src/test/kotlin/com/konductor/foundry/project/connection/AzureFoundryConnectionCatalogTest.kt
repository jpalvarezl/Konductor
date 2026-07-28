package com.konductor.foundry.project.connection

import com.konductor.foundry.FoundryTestBase
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Minimal connection GET exercised with the same assertions in record, playback, and live modes. */
@Disabled("Re-enable in #72 when Linux CI starts test-proxy playback")
class AzureFoundryConnectionCatalogTest : FoundryTestBase() {
    @Test
    fun getConfiguredConnectionWithoutCredentials() {
        val connectionName = configuredConnectionName()
        val catalog: FoundryConnectionCatalog = AzureFoundryConnectionCatalog(connectionsClient())

        val connection = catalog.getConnection(connectionName)

        assertEquals(connectionName, connection.name)
        assertTrue(connection.id.isNotBlank())
        assertTrue(connection.type.isNotBlank())
        assertTrue(connection.target.isNotBlank())
        assertApplicationDtoIsCredentialValueFreeAndSdkFree(connection)
    }

    private fun assertApplicationDtoIsCredentialValueFreeAndSdkFree(connection: FoundryConnection) {
        val fields = connection.javaClass.declaredFields.associateBy { it.name }
        assertEquals(
            setOf("name", "id", "type", "target", "isDefault", "metadata", "credentialType"),
            fields.keys,
        )
        assertFalse(fields.values.any { it.genericType.typeName.contains("com.azure.") })
    }
}
