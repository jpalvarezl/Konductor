package com.konductor.conversation

import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandOptionsTest {
    @Test
    fun loadsSdkFreeModelOptions() = runBlocking {
        val provider = FoundryModelOptionProvider(
            MockOptionDeploymentCatalog(
                listOf(
                    FoundryDeployment("app", "AppDeployment"),
                    FoundryDeployment("deployment-a", "ModelDeployment", modelName = "gpt-a"),
                ),
            ),
        )

        assertEquals(
            listOf(CommandOption("deployment-a", detail = "gpt-a")),
            provider.loadOptions(),
        )
    }

    @Test
    fun propagatesCatalogFailures() = runBlocking {
        val expected = IllegalStateException("offline")
        val provider = FoundryModelOptionProvider(MockOptionDeploymentCatalog(failure = expected))

        val actual = runCatching { provider.loadOptions() }.exceptionOrNull()

        assertEquals(expected::class, actual?.let { it::class })
        assertEquals(expected.message, actual?.message)
    }
}

private class MockOptionDeploymentCatalog(
    private val deployments: List<FoundryDeployment> = emptyList(),
    private val failure: Exception? = null,
) : FoundryDeploymentCatalog {
    override fun listDeployments(): List<FoundryDeployment> {
        failure?.let { throw it }
        return deployments
    }

    override fun getDeployment(name: String): FoundryDeployment = deployments.single { it.name == name }
}
