package com.konductor.foundry.project.deployment

import com.konductor.foundry.FoundryTestBase
import kotlin.test.Test
import kotlin.test.assertEquals

/** Minimal record/playback/live proof through the production Projects SDK adapter. */
class AzureFoundryDeploymentCatalogIT : FoundryTestBase() {
    @Test
    fun getConfiguredDeployment() {
        val deploymentName = if (isPlayback()) REDACTED_DEPLOYMENT else requiredEnvironment("FOUNDRY_MODEL_NAME")
        val catalog = AzureFoundryDeploymentCatalog(projectClientBuilder().buildDeploymentsClient())

        val deployment = catalog.getDeployment(deploymentName)

        assertEquals(deploymentName, deployment.name)
        assertEquals("ModelDeployment", deployment.type)
    }
}
