package com.konductor.agent

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentContextFactoryTest {
    private val credential = TokenCredential { _ -> Mono.just(AccessToken("t", OffsetDateTime.now().plusHours(1))) }

    private fun config(override: String? = null, append: String? = null) = Configuration(
        projectEndpoint = "https://x.ai.azure.com/api/projects/p",
        tokenCredential = credential,
        model = "gpt-x",
        systemPromptOverride = override,
        systemPromptAppend = append,
    )

    @Test
    fun `stable base excludes the dynamic env header and append`() {
        val ctx = AgentContextFactory.build(config(override = "BASE", append = "APPEND"), cwd = Path.of("home", "proj"))

        // What gets baked into a persisted agent is stable only: no cwd/date, no project append.
        assertEquals("BASE", ctx.baseSystemPrompt)
        assertFalse(ctx.baseSystemPrompt.contains("Environment:"))
        assertFalse(ctx.baseSystemPrompt.contains("APPEND"))
        // The per-turn dynamic part carries the env header, then the append.
        assertTrue(ctx.dynamicPreamble.contains("Environment:"))
        assertTrue(ctx.dynamicPreamble.trimEnd().endsWith("APPEND"))
    }

    @Test
    fun `ephemeral system prompt keeps base then env then append order`() {
        val prompt = AgentContextFactory.build(config(override = "BASE", append = "APPEND")).systemPrompt

        assertTrue(prompt.startsWith("BASE"))
        // The env header precedes the append (order preserved from before the stable/dynamic split).
        val envAt = prompt.indexOf("Environment:")
        val appendAt = prompt.indexOf("APPEND")
        assertTrue(envAt in 0 until appendAt)
    }
}
