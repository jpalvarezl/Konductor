package com.konductor.provider.inference

import com.konductor.core.models.UserEntry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PromptAgentFoundryResponsesClientFailureTest {
    @Test
    fun `unary agent-scoped call maps overflow without retry`() {
        val transport = ThrowingOpenAIClient(listOf(serviceFailure(400, POSITIVE)))
        val adapter = PromptAgentFoundryResponsesClient(transport.client)

        assertFailsWith<PromptContextOverflowException> { runBlocking { adapter.respond(request()) } }

        assertEquals(1, transport.calls)
    }

    @Test
    fun `streaming agent-scoped call uses the same mapping without retry`() {
        val transport = ThrowingOpenAIClient(listOf(serviceFailure(400, POSITIVE)))
        val adapter = PromptAgentFoundryResponsesClient(transport.client)

        assertFailsWith<PromptContextOverflowException> {
            runBlocking { adapter.respondStreaming(request()).toList() }
        }

        assertEquals(1, transport.calls)
    }

    @Test
    fun `ordinary agent-scoped bad request remains the SDK failure`() {
        val service = serviceFailure(400, OTHER)
        val transport = ThrowingOpenAIClient(listOf(service))
        val adapter = PromptAgentFoundryResponsesClient(transport.client)

        val failure = assertFailsWith<Throwable> { runBlocking { adapter.respond(request()) } }

        assertEquals(service, failure)
        assertEquals(1, transport.calls)
    }

    private fun request() = FoundryResponsesRequest(
        model = "ignored-for-bound-agent",
        systemPrompt = "ignored",
        history = listOf(UserEntry(Uuid.random(), null, Instant.parse("2026-01-01T00:00:00Z"), "hello")),
        tools = emptyList(),
        dynamicPreamble = "dynamic",
    )

    private companion object {
        const val ROOT = "/provider/inference/context-overflow/"
        const val POSITIVE = "${ROOT}context-length-exceeded.json"
        const val OTHER = "${ROOT}other-bad-request.json"
    }
}
