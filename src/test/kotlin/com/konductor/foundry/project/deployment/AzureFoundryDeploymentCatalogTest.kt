package com.konductor.foundry.project.deployment

import com.konductor.foundry.FoundryTestBase
import org.junit.jupiter.api.Disabled
import kotlin.test.Test
import kotlin.test.assertEquals

/** Minimal record/playback/live proof through the production Projects SDK adapter. */
@Disabled("Re-enable in #72 when Linux CI starts test-proxy playback")
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
