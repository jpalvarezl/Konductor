package com.konductor.foundry.project.deployment

import com.konductor.foundry.FoundryTestBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Record/playback/live coverage through the production Projects SDK adapter. */
class AzureFoundryDeploymentCatalogTest : FoundryTestBase() {
    @Test
    fun getConfiguredDeployment() {
        val deploymentName = configuredDeploymentName()
        val catalog = AzureFoundryDeploymentCatalog(deploymentsClient())

        val deployment = catalog.getDeployment(deploymentName)

        assertEquals(deploymentName, deployment.name)
        assertEquals("ModelDeployment", deployment.type)
    }

    @Test
    fun listCatalog() {
        val deploymentName = configuredDeploymentName()
        val catalog = AzureFoundryDeploymentCatalog(deploymentsClient(fullCatalog = true))

        val deployments = catalog.listDeployments()
        sanitizeDeploymentInventory(deployments, deploymentName)

        assertEquals(9, deployments.size, "Re-record when the approved project inventory changes")
        val names = deployments.map(FoundryDeployment::name)
        assertEquals(names.sorted(), names, "Deployment names should use the catalog's stable ordering")
        assertEquals(names.distinct(), names, "Deployment names should be unique in the catalog")
        deployments.forEach(::assertMeaningfulMetadata)

        val discovered = assertNotNull(
            deployments.singleOrNull { it.name == deploymentName },
            "The configured deployment was not returned by the production unfiltered listDeployments path",
        )
        assertEquals("ModelDeployment", discovered.type, "The configured deployment must be a model deployment")

        val fetched = catalog.getDeployment(deploymentName)
        assertEquals(deploymentName, fetched.name)
        assertEquals(discovered.type, fetched.type, "List and get should agree on the deployment type")
        assertMeaningfulMetadata(fetched)
    }

    private fun assertMeaningfulMetadata(deployment: FoundryDeployment) {
        assertTrue(deployment.name.isNotBlank(), "Deployment name must not be blank")
        assertTrue(deployment.type.isNotBlank(), "Deployment type must not be blank")
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
}
