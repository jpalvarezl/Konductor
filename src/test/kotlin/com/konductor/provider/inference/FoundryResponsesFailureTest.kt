package com.konductor.provider.inference

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class FoundryResponsesFailureTest {
    @Test
    fun onlyStructuredContextLengthCodeOnHttp400MapsToDomainOverflow() {
        val service = serviceFailure(400, POSITIVE)

        val mapped = mapFoundryResponsesFailure(service)

        val overflow = assertIs<PromptContextOverflowException>(mapped)
        assertSame(service, overflow.cause)
    }

    @Test
    fun wrappedServiceExceptionMapsToDomainOverflow() {
        val service = serviceFailure(400, POSITIVE)
        val wrapped = wrap(service, 2)

        assertIs<PromptContextOverflowException>(mapFoundryResponsesFailure(wrapped))
    }

    @Test
    fun wrongStatusStructuredCodeDoesNotMap() {
        listOf(429, 500).forEach { status ->
            val service = serviceFailure(status, POSITIVE)
            assertSame(service, mapFoundryResponsesFailure(service))
        }
    }

    @Test
    fun messageOnlyOtherCodeMalformedAndMissingParsedErrorsFailClosed() {
        listOf(MESSAGE_ONLY, OTHER_BAD_REQUEST, MALFORMED, null).forEach { fixture ->
            val service = serviceFailure(400, fixture)
            assertSame(service, mapFoundryResponsesFailure(service), "fixture=$fixture")
        }
    }

    @Test
    fun deeplyWrappedServiceExceptionMapsWithoutArbitraryDepthLimit() {
        val wrapped = wrap(serviceFailure(400, POSITIVE), 32)

        assertIs<PromptContextOverflowException>(mapFoundryResponsesFailure(wrapped))
    }

    @Test
    fun cyclicCausesTerminateAndFailClosed() {
        val first = RuntimeException("first")
        val second = RuntimeException("second")
        first.initCause(second)
        second.initCause(first)

        assertSame(first, mapFoundryResponsesFailure(first))
    }

    @Test
    fun nestedCancellationTakesPrecedenceOverStructuredOverflow() {
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
