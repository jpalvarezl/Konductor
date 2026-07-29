package com.konductor.foundry.project.deployment

import com.konductor.foundry.FoundryTestBase
import kotlin.test.Test
import kotlin.test.assertEquals

/** Minimal record/playback/live proof through the production Projects SDK adapter. */
class AzureFoundryDeploymentCatalogTest : FoundryTestBase() {
    @Test
    fun getConfiguredDeployment() {
        val deploymentName = configuredDeploymentName()
        val catalog = AzureFoundryDeploymentCatalog(deploymentsClient())

        val deployment = catalog.getDeployment(deploymentName)

        assertEquals(deploymentName, deployment.name)
        assertEquals("ModelDeployment", deployment.type)
    }
}
