package com.konductor.foundry.project.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FoundryConnectionCatalogTest {
    @Test
    fun `application DTO has only the approved non-secret SDK-free fields`() {
        val fields = FoundryConnection::class.java.declaredFields.associateBy { it.name }

        assertEquals(
            setOf("name", "id", "type", "target", "isDefault", "metadata", "credentialType"),
            fields.keys,
        )
        assertFalse(fields.values.any { it.genericType.typeName.contains("com.azure.") })
    }
}
