package com.konductor.provider.inference

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
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
    fun wrapperAroundStructuredServiceFailureFailsClosed() {
        val service = serviceFailure(400, POSITIVE)
        val wrapper = RuntimeException("wrapper", service)

        assertSame(wrapper, mapFoundryResponsesFailure(wrapper))
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
    fun directCancellationTakesPrecedenceOverStructuredCause() {
        val cancellation = CancellationException("cancelled")
        cancellation.initCause(serviceFailure(400, POSITIVE))

        assertSame(cancellation, mapFoundryResponsesFailure(cancellation))
    }

    private companion object {
        const val ROOT = "/provider/inference/context-overflow/"
        const val POSITIVE = "${ROOT}context-length-exceeded.json"
        const val MESSAGE_ONLY = "${ROOT}message-only.json"
        const val OTHER_BAD_REQUEST = "${ROOT}other-bad-request.json"
        const val MALFORMED = "${ROOT}malformed.json"
    }
}
