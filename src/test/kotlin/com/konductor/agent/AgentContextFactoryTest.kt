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
    private val credential = TokenCredential { Mono.just(AccessToken("token", OffsetDateTime.now().plusHours(1))) }

    @Test
    fun `assembles base append one rendered context block then environment`() {
        val records = listOf(
            ContextFileRecord("/repo/a&b/AGENTS.md", "root\ntext"),
            ContextFileRecord("/repo/empty/AGENTS.md", ""),
        )
        val rendered = requireNotNull(ContextBlockRenderer.render(records))
        val context = AgentContextFactory.build(
            configuration(systemPromptOverride = "base\rline", systemPromptAppend = "append\r\nline"),
            cwd = Path.of("/repo"),
            contextFiles = records,
        )

        assertEquals("base\nline\n\nappend\nline", context.baseSystemPrompt)
        assertTrue(context.dynamicPreamble.startsWith("$rendered\n\nEnvironment:"))
        assertEquals(context.baseSystemPrompt + "\n\n" + context.dynamicPreamble, context.systemPrompt)
        assertEquals(1, context.systemPrompt.windowed(rendered.length).count { it == rendered })
        assertTrue(context.systemPrompt.contains("path=\"/repo/a&amp;b/AGENTS.md\""))
        assertFalse(context.systemPrompt.endsWith('\n'))
    }

    @Test
    fun `omits empty components but retains whitespace-only append`() {
        val context = AgentContextFactory.build(
            configuration(systemPromptOverride = "base", systemPromptAppend = "   "),
            cwd = Path.of("."),
            contextFiles = emptyList(),
        )

        assertEquals("base\n\n   ", context.baseSystemPrompt)
        assertTrue(context.dynamicPreamble.startsWith("Environment:"))
        assertEquals(context.baseSystemPrompt + "\n\n" + context.dynamicPreamble, context.systemPrompt)

        val emptyAppend = AgentContextFactory.build(
            configuration(systemPromptOverride = "base", systemPromptAppend = ""),
            cwd = Path.of("."),
        )
        assertEquals("base", emptyAppend.baseSystemPrompt)
    }

    private fun configuration(
        systemPromptOverride: String,
        systemPromptAppend: String?,
    ): Configuration = Configuration(
        projectEndpoint = "https://example.ai.azure.com/api/projects/p",
        tokenCredential = credential,
        model = "model",
        systemPromptOverride = systemPromptOverride,
        systemPromptAppend = systemPromptAppend,
    )
}
