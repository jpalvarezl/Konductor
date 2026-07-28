package com.konductor.foundry.project.deployment

import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.identity.DefaultAzureCredentialBuilder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in live test for deployment discovery through the production adapter. It is skipped unless
 * `KONDUCTOR_LIVE_TESTS=1` and requires `FOUNDRY_PROJECT_ENDPOINT`, `FOUNDRY_MODEL_NAME`, and an identity available to
 * [DefaultAzureCredentialBuilder] (normally `az login`).
 *
 * Run: `KONDUCTOR_LIVE_TESTS=1 ./mvnw -Dtest=AzureFoundryDeploymentCatalogLiveTest test`.
 */
@EnabledIfEnvironmentVariable(named = "KONDUCTOR_LIVE_TESTS", matches = "(?i)^(1|true|yes)$")
class AzureFoundryDeploymentCatalogLiveTest {
    @Test
    fun `configured model deployment is discoverable and gettable with real metadata`() {
        val endpoint = requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT")
        val deploymentName = requiredEnvironment("FOUNDRY_MODEL_NAME")
        val deploymentsClient = AIProjectClientBuilder()
            .endpoint(endpoint)
            .credential(DefaultAzureCredentialBuilder().build())
            .buildDeploymentsClient()
        val catalog = AzureFoundryDeploymentCatalog(deploymentsClient)

        val deployments = catalog.listDeployments()
        val names = deployments.map(FoundryDeployment::name)
        assertEquals(names.sorted(), names, "Deployment names should use the catalog's stable ordering")
        assertEquals(names.distinct(), names, "Deployment names should be unique in the catalog")

        val discovered = assertNotNull(
            deployments.singleOrNull { it.name == deploymentName },
            "Configured deployment '$deploymentName' was not returned by DeploymentsClient.listDeployments()",
        )
        assertMeaningfulMetadata(discovered)

        val fetched = catalog.getDeployment(deploymentName)
        assertEquals(deploymentName, fetched.name)
        assertEquals(discovered.type, fetched.type, "List and get should agree on the deployment type")
        assertMeaningfulMetadata(fetched)

        val model = fetched.modelName ?: "<not returned>"
        val publisher = fetched.modelPublisher ?: "<not returned>"
        println("LIVE deployment name='${fetched.name}' type='${fetched.type}' model='$model' publisher='$publisher'")
    }

    private fun assertMeaningfulMetadata(deployment: FoundryDeployment) {
        assertTrue(deployment.name.isNotBlank(), "Deployment name must not be blank")
        assertEquals(
            "ModelDeployment",
            deployment.type,
            "FOUNDRY_MODEL_NAME must identify a model deployment",
        )
        listOf(
            deployment.modelName,
            deployment.modelVersion,
            deployment.modelPublisher,
            deployment.connectionName,
        ).filterNotNull().forEach { value ->
            assertTrue(value.isNotBlank(), "Optional deployment metadata must be meaningful when returned")
        }
        deployment.capabilities.forEach { (name, value) ->
            assertTrue(name.isNotBlank(), "Capability names must not be blank")
            assertTrue(value.isNotBlank(), "Capability '$name' must have a non-blank value")
        }
        deployment.sku?.let { sku ->
            assertTrue(sku.capacity >= 0, "SKU capacity must not be negative")
            listOf(sku.family, sku.name, sku.size, sku.tier).filterNotNull().forEach { value ->
                assertTrue(value.isNotBlank(), "Optional SKU metadata must be meaningful when returned")
            }
        }
    }

    private fun requiredEnvironment(name: String): String =
        assertNotNull(System.getenv(name)?.trim()?.ifBlank { null }, "$name must be set for live tests")
}
