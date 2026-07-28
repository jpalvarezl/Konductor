package com.konductor.foundry.project.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoundryDeploymentCatalogTest {
    @Test
    fun `canonicalizes application deployments by exact name with first duplicate winning`() {
        val firstBeta = FoundryDeployment(name = "beta", type = "ModelDeployment", modelVersion = "first")
        val deployments = listOf(
            firstBeta,
            FoundryDeployment(name = "alpha", type = "ModelDeployment"),
            firstBeta.copy(modelVersion = "second"),
            FoundryDeployment(name = "Alpha", type = "ModelDeployment"),
        )

        val actual = canonicalizeDeployments(deployments)

        assertEquals(listOf("Alpha", "alpha", "beta"), actual.map(FoundryDeployment::name))
        assertEquals("first", actual.single { it.name == "beta" }.modelVersion)
        assertTrue(canonicalizeDeployments(emptyList()).isEmpty())
    }

    @Test
    fun `application deployment DTOs expose no Azure SDK types`() {
        val dtoTypes = listOf(FoundryDeployment::class.java, FoundryDeploymentSku::class.java)

        assertTrue(
            dtoTypes.flatMap { it.declaredFields.asIterable() }
                .none { it.type.name.startsWith("com.azure") },
        )
    }
}
