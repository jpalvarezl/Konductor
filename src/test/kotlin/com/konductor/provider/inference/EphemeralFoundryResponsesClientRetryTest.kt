package com.konductor.provider.inference

import com.konductor.core.models.UserEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

class EphemeralFoundryResponsesClientRetryTest {
    @Test
    fun unaryOverflowMapsBeforeTransientRetry() {
        val transport = ThrowingOpenAIClient(listOf(serviceFailure(400, POSITIVE)))
        val adapter = EphemeralFoundryResponsesClient(transport.client)

        assertFailsWith<PromptContextOverflowException> { runBlocking { adapter.respond(request()) } }

        assertEquals(1, transport.calls)
    }

    @Test
    fun unaryCancellationWrappingTransientCausePropagatesWithoutRetry() {
        val cancellation = cancellationWrappingTransient()
        val transport = ThrowingOpenAIClient(listOf(cancellation))
        val adapter = EphemeralFoundryResponsesClient(transport.client)

        val terminal = assertFailsWith<CancellationException> { runBlocking { adapter.respond(request()) } }

        assertEquals(cancellation.message, terminal.message)
        assertEquals(1, transport.calls)
    }

    @Test
    fun streamingOverflowEmitsNoRetryStatusAndMakesOneTransportCall() {
        val transport = ThrowingOpenAIClient(listOf(serviceFailure(400, POSITIVE)))
        val adapter = EphemeralFoundryResponsesClient(transport.client)
        val events = mutableListOf<FoundryResponsesEvent>()

        assertFailsWith<PromptContextOverflowException> {
            runBlocking { adapter.respondStreaming(request()).collect { events += it } }
        }

        assertEquals(1, transport.calls)
        assertEquals(emptyList(), events.filterIsInstance<FoundryResponsesEvent.Retrying>())
    }

    @Test
    fun streamingCancellationWrappingTransientCauseEmitsNoRetryStatus() {
        val cancellation = cancellationWrappingTransient()
        val transport = ThrowingOpenAIClient(listOf(cancellation))
        val adapter = EphemeralFoundryResponsesClient(transport.client)
        val events = mutableListOf<FoundryResponsesEvent>()

        val terminal = assertFailsWith<CancellationException> {
            runBlocking { adapter.respondStreaming(request()).collect { events += it } }
        }

        assertEquals(cancellation.message, terminal.message)
        assertEquals(1, transport.calls)
        assertEquals(emptyList(), events.filterIsInstance<FoundryResponsesEvent.Retrying>())
    }

    @Test
    fun existingTransientStreamingPolicyStillRetriesThreeTimes() {
        val failures = List(4) { serviceFailure(429, OTHER) }
        val transport = ThrowingOpenAIClient(failures)
        val adapter = EphemeralFoundryResponsesClient(transport.client)
        val events = mutableListOf<FoundryResponsesEvent>()

        val terminal = assertFailsWith<Throwable> {
            runBlocking { adapter.respondStreaming(request()).collect { events += it } }
        }

        assertIs<com.openai.errors.OpenAIServiceException>(terminal)
        assertEquals(4, transport.calls)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<FoundryResponsesEvent.Retrying>().map { it.retryAttempt })
    }

    private fun cancellationWrappingTransient() =
        CancellationException("cancelled").apply { initCause(serviceFailure(429, OTHER)) }

    private fun request() = FoundryResponsesRequest(
        model = "test-model",
        systemPrompt = "system",
        history = listOf(UserEntry(Uuid.random(), null, Instant.parse("2026-01-01T00:00:00Z"), "hello")),
        tools = emptyList(),
    )

    private companion object {
        const val ROOT = "/provider/inference/context-overflow/"
        const val POSITIVE = "${ROOT}context-length-exceeded.json"
        const val OTHER = "${ROOT}other-bad-request.json"
    }
}
