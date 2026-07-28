package com.konductor.foundry.project.connection

import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.identity.DefaultAzureCredentialBuilder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opt-in live validation of the Azure AI Projects 2.2.0 connection adapter. The test is skipped unless
 * `KONDUCTOR_LIVE_TESTS` is enabled and requires `FOUNDRY_PROJECT_ENDPOINT` plus a DefaultAzureCredential login.
 * It only uses the no-credential adapter operations and never requests or prints credential values.
 *
 * Run: `KONDUCTOR_LIVE_TESTS=1 mvn -Dtest=AzureFoundryConnectionCatalogLiveTest test`.
 */
@EnabledIfEnvironmentVariable(named = "KONDUCTOR_LIVE_TESTS", matches = "(?i)^(1|true|yes)$")
class AzureFoundryConnectionCatalogLiveTest {
    @Test
    fun `lists and gets a real connection through the SDK-free adapter`() {
        val endpoint = requireNotNull(System.getenv("FOUNDRY_PROJECT_ENDPOINT")) {
            "FOUNDRY_PROJECT_ENDPOINT is required when KONDUCTOR_LIVE_TESTS is enabled"
        }.also {
            require(it.isNotBlank()) { "FOUNDRY_PROJECT_ENDPOINT must not be blank" }
        }
        val connectionsClient = AIProjectClientBuilder()
            .endpoint(endpoint)
            .credential(DefaultAzureCredentialBuilder().build())
            .buildConnectionsClient()
        val catalog: FoundryConnectionCatalog = AzureFoundryConnectionCatalog(connectionsClient)

        val listedConnections = catalog.listConnections()
        assertTrue(listedConnections.isNotEmpty(), "The live Foundry project must contain at least one connection")

        val listed = listedConnections.first()
        val fetched = catalog.getConnection(listed.name)

        // Both values crossed the explicit Connection -> FoundryConnection mapping in the production adapter.
        assertSameSafeMetadata(listed, fetched)
        assertApplicationDtoIsCredentialValueFreeAndSdkFree(fetched)
    }

    private fun assertSameSafeMetadata(listed: FoundryConnection, fetched: FoundryConnection) {
        assertEquals(listed.name, fetched.name)
        assertEquals(listed.id, fetched.id)
        assertEquals(listed.type, fetched.type)
        assertEquals(listed.target, fetched.target)
        assertEquals(listed.isDefault, fetched.isDefault)
        assertEquals(listed.metadata, fetched.metadata)
        assertEquals(listed.credentialType, fetched.credentialType)
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
