package com.konductor.provider.inference

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class FoundryResponsesFailureTest {
    @Test
    fun `only structured context length code on HTTP 400 maps to domain overflow`() {
        val service = serviceFailure(400, POSITIVE)

        val mapped = mapFoundryResponsesFailure(service)

        val overflow = assertIs<PromptContextOverflowException>(mapped)
        assertSame(service, overflow.cause)
    }

    @Test
    fun `bounded wrapper walk finds the service exception through seven wrappers`() {
        val service = serviceFailure(400, POSITIVE)
        val wrapped = wrap(service, 7)

        assertIs<PromptContextOverflowException>(mapFoundryResponsesFailure(wrapped))
    }

    @Test
    fun `wrong status structured code does not map`() {
        listOf(429, 500).forEach { status ->
            val service = serviceFailure(status, POSITIVE)
            assertSame(service, mapFoundryResponsesFailure(service))
        }
    }

    @Test
    fun `message only other code malformed and missing parsed errors fail closed`() {
        listOf(MESSAGE_ONLY, OTHER_BAD_REQUEST, MALFORMED, null).forEach { fixture ->
            val service = serviceFailure(400, fixture)
            assertSame(service, mapFoundryResponsesFailure(service), "fixture=$fixture")
        }
    }

    @Test
    fun `cause beyond eight-node bound is not inspected`() {
        val wrapped = wrap(serviceFailure(400, POSITIVE), 8)

        assertSame(wrapped, mapFoundryResponsesFailure(wrapped))
    }

    @Test
    fun `cyclic causes terminate and fail closed`() {
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        first.initCause(second)
        second.initCause(first)

        assertSame(first, mapFoundryResponsesFailure(first))
    }

    @Test
    fun `nested cancellation takes precedence over a structured overflow`() {
        val cancellation = CancellationException("cancelled")
        val service = serviceFailure(400, POSITIVE)
        val wrapped = RuntimeException("outer", RuntimeException("middle", cancellation))
        cancellation.initCause(service)

        val mapped = mapFoundryResponsesFailure(wrapped)

        assertSame(cancellation, mapped)
        assertEquals("cancelled", mapped.message)
    }

    private fun wrap(error: Throwable, wrappers: Int): Throwable {
        var current = error
        repeat(wrappers) { current = RuntimeException("wrapper-$it", current) }
        return current
    }

    private companion object {
        const val ROOT = "/provider/inference/context-overflow/"
        const val POSITIVE = "${ROOT}context-length-exceeded.json"
        const val MESSAGE_ONLY = "${ROOT}message-only.json"
        const val OTHER_BAD_REQUEST = "${ROOT}other-bad-request.json"
        const val MALFORMED = "${ROOT}malformed.json"
    }
}
