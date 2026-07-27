package com.konductor.foundry.project.deployment

import com.azure.ai.projects.models.Deployment
import com.azure.core.util.BinaryData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoundryDeploymentCatalogTest {
    @Test
    fun `lists no deployments when the project is empty`() {
        val catalog = adapter(emptyList())

        assertTrue(catalog.listDeployments().isEmpty())
    }

    @Test
    fun `maps base deployments and multiple service results`() {
        val catalog = adapter(
            listOf(
                deployment("""{"type":"Deployment","name":"tools"}"""),
                deployment("""{"type":"Deployment","name":"embeddings"}"""),
            ),
        )

        assertEquals(
            listOf(
                FoundryDeployment(name = "embeddings", type = "Deployment"),
                FoundryDeployment(name = "tools", type = "Deployment"),
            ),
            catalog.listDeployments(),
        )
    }

    @Test
    fun `maps model deployment metadata without leaking SDK models`() {
        val sdkDeployment = deployment(
            """
            {
              "type": "ModelDeployment",
              "name": "chat",
              "modelName": "gpt-5",
              "modelVersion": "2026-01-01",
              "modelPublisher": "OpenAI",
              "capabilities": {
                "vision": "true",
                "chatCompletion": "true"
              },
              "sku": {
                "capacity": 20,
                "family": "standard",
                "name": "GlobalStandard",
                "size": "medium",
                "tier": "Standard"
              },
              "connectionName": "aoai-connection"
            }
            """.trimIndent(),
        )
        val catalog = adapter(listOf(sdkDeployment))

        val actual = catalog.getDeployment("chat")

        assertEquals(
            FoundryDeployment(
                name = "chat",
                type = "ModelDeployment",
                modelName = "gpt-5",
                modelVersion = "2026-01-01",
                modelPublisher = "OpenAI",
                capabilities = mapOf("chatCompletion" to "true", "vision" to "true"),
                sku = FoundryDeploymentSku(
                    capacity = 20,
                    family = "standard",
                    name = "GlobalStandard",
                    size = "medium",
                    tier = "Standard",
                ),
                connectionName = "aoai-connection",
            ),
            actual,
        )
        assertEquals(listOf("chatCompletion", "vision"), actual.capabilities.keys.toList())
        assertTrue(FoundryDeployment::class.java.declaredFields.none { it.type.name.startsWith("com.azure") })
    }

    @Test
    fun `sorts by exact name and keeps the first duplicate returned by the service`() {
        val firstDuplicate = deployment(
            """{"type":"ModelDeployment","name":"beta","modelVersion":"first"}""",
        )
        val catalog = adapter(
            listOf(
                firstDuplicate,
                deployment("""{"type":"Deployment","name":"alpha"}"""),
                deployment("""{"type":"ModelDeployment","name":"beta","modelVersion":"second"}"""),
                deployment("""{"type":"Deployment","name":"Alpha"}"""),
            ),
        )

        val actual = catalog.listDeployments()

        assertEquals(listOf("Alpha", "alpha", "beta"), actual.map(FoundryDeployment::name))
        assertEquals("first", actual.single { it.name == "beta" }.modelVersion)
    }

    @Test
    fun `catalog interface supports deterministic SDK-free fakes`() {
        val expected = FoundryDeployment(name = "chat", type = "ModelDeployment", modelName = "gpt-5")
        val fake: FoundryDeploymentCatalog = FakeFoundryDeploymentCatalog(listOf(expected))

        assertEquals(listOf(expected), fake.listDeployments())
        assertEquals(expected, fake.getDeployment("chat"))
    }

    private fun adapter(deployments: List<Deployment>): AzureFoundryDeploymentCatalog =
        AzureFoundryDeploymentCatalog(
            listSdkDeployments = { deployments },
            getSdkDeployment = { name -> deployments.first { it.name == name } },
        )

    private fun deployment(json: String): Deployment =
        BinaryData.fromString(json).toObject(Deployment::class.java)
}

private class FakeFoundryDeploymentCatalog(
    deployments: List<FoundryDeployment>,
) : FoundryDeploymentCatalog {
    private val byName = deployments.associateBy(FoundryDeployment::name)

    override fun listDeployments(): List<FoundryDeployment> = byName.values.sortedBy(FoundryDeployment::name)

    override fun getDeployment(name: String): FoundryDeployment = checkNotNull(byName[name])
}
