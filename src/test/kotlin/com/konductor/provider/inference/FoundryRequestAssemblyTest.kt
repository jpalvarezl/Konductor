package com.konductor.provider.inference

import com.openai.models.responses.EasyInputMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoundryRequestAssemblyTest {
    @Test
    fun `ephemeral request forwards assembled instructions byte for byte`() {
        val exact = "base\n\nappend\n\n<project_context>\n  \n</project_context>\n\nEnvironment: cwd=/repo"
        val params = buildEphemeralParams(request(systemPrompt = exact, dynamicPreamble = "dynamic"))

        assertEquals(exact, params.instructions().orElseThrow())
        assertTrue(params.model().isPresent)
        assertTrue(params.input().orElseThrow().asResponse().isEmpty())
    }

    @Test
    fun `PromptAgent request is input-only with one exact developer preamble`() {
        val exact = "<project_context>\n \n</project_context>\n\nEnvironment: cwd=/repo"
        val params = buildPromptAgentParams(request(systemPrompt = "must-not-be-sent", dynamicPreamble = exact))
        val input = params.input().orElseThrow().asResponse()
        val developer = input.single().asEasyInputMessage()

        assertEquals(EasyInputMessage.Role.DEVELOPER, developer.role())
        assertEquals(exact, developer.content().asTextInput())
        assertFalse(params.model().isPresent)
        assertFalse(params.instructions().isPresent)
        assertFalse(params.tools().isPresent)
    }

    @Test
    fun `PromptAgent retains whitespace-only preamble and omits only zero length`() {
        val whitespace = buildPromptAgentInput(request(dynamicPreamble = " \t "))
        val empty = buildPromptAgentInput(request(dynamicPreamble = ""))

        assertEquals(" \t ", whitespace.single().asEasyInputMessage().content().asTextInput())
        assertTrue(empty.isEmpty())
    }

    private fun request(
        systemPrompt: String = "system",
        dynamicPreamble: String,
    ): FoundryResponsesRequest = FoundryResponsesRequest(
        model = "model",
        systemPrompt = systemPrompt,
        history = emptyList(),
        tools = emptyList(),
        dynamicPreamble = dynamicPreamble,
    )
}
