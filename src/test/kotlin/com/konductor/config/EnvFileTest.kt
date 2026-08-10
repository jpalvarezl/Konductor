package com.konductor.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvFileTest {
    @Test
    fun `reads KEY=VALUE ignoring comments, blanks, export prefix and quotes`() {
        val file = Files.createTempFile("konductor", ".env")
        Files.writeString(
            file,
            """
            # a comment

            FOUNDRY_PROJECT_ENDPOINT=https://x.ai.azure.com/api/projects/p
            export FOUNDRY_MODEL_NAME='gpt-5'
            QUOTED="double"
            """.trimIndent(),
        )

        val map = EnvFile.read(file)

        assertEquals("https://x.ai.azure.com/api/projects/p", map["FOUNDRY_PROJECT_ENDPOINT"])
        assertEquals("gpt-5", map["FOUNDRY_MODEL_NAME"])
        assertEquals("double", map["QUOTED"])
        Files.deleteIfExists(file)
    }

    @Test
    fun `read returns empty for a missing file`() {
        assertEquals(emptyMap(), EnvFile.read(java.nio.file.Path.of("does-not-exist-konductor.env")))
    }

    @Test
    fun `parses already read text separately and the last duplicate wins`() {
        val parsed = EnvFile.parse("A=first\r\nA='last'\rB=value\n")
        val sources = EnvFile.trustedProjectSources("A=project\n", mapOf("A" to "operator")::get)

        assertEquals(mapOf("A" to "last", "B" to "value"), parsed)
        assertEquals("operator", sources.effectiveValue("A"))
        assertEquals("project", sources.projectValue("A"))
    }

    @Test
    fun `process sources do not open or expose project dotenv`() {
        val process = mapOf("A" to "operator")
        val sources = EnvFile.processSources(process::get)

        assertEquals("operator", sources.processValue("A"))
        assertNull(sources.projectValue("A"))
        assertNull(sources.effectiveValue("MISSING"))
    }

    @Test
    fun `trusted sources retain process and project lookups separately`() {
        val file = Files.createTempFile("konductor", ".env")
        Files.writeString(file, "A=project\nB=project\n")
        val sources = EnvFile.trustedProjectSources(file, mapOf("A" to "operator")::get)

        assertEquals("operator", sources.processValue("A"))
        assertEquals("project", sources.projectValue("A"))
        assertEquals("operator", sources.effectiveValue("A"))
        assertEquals("project", sources.effectiveValue("B"))
        Files.deleteIfExists(file)
    }

    @Test
    fun `overlay prefers real env over file and fills gaps`() {
        val file = Files.createTempFile("konductor", ".env")
        Files.writeString(file, "A=fromFile\nB=fromFile\n")
        val base = mapOf("A" to "fromEnv", "C" to "   ") // blank C is treated as absent

        val env = EnvFile.overlay(file) { base[it] }

        assertEquals("fromEnv", env("A")) // real env wins
        assertEquals("fromFile", env("B")) // file fills the gap
        assertNull(env("C")) // blank env, not in file -> null
        Files.deleteIfExists(file)
    }
}
