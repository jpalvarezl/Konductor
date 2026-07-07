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
