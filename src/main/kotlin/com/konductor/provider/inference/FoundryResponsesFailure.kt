package com.konductor.provider.inference

import com.openai.errors.OpenAIServiceException
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.IdentityHashMap

/** SDK-free signal that a Prompt Responses request exceeded the model context window. */
class PromptContextOverflowException(cause: Throwable) :
    RuntimeException("Prompt context length exceeded.", cause)

/**
 * Translate the pinned openai-java Responses failure into Konductor's application-level failure vocabulary.
 *
 * The adapter seam is expected to receive [OpenAIServiceException] directly from openai-java. Cause traversal is a
 * defensive allowance for framework or coroutine wrappers: it is identity-cycle-safe, has no arbitrary depth limit,
 * and checks the entire chain for cancellation before inspecting the exact service status and code. Malformed
 * accessors and unknown failures fail closed as the original error.
 */
internal fun mapFoundryResponsesFailure(error: Throwable): Throwable {
    error.causeSequence().filterIsInstance<CancellationException>().firstOrNull()?.let { return it }

    val overflow = error.causeSequence().filterIsInstance<OpenAIServiceException>().firstOrNull { serviceError ->
        runCatching {
            serviceError.statusCode() == BAD_REQUEST &&
                serviceError.code().orElse(null) == CONTEXT_LENGTH_EXCEEDED
        }.getOrDefault(false)
    }
    return overflow?.let(::PromptContextOverflowException) ?: error
}

/** Walk this throwable and its causes without truncating wrapper chains or looping on malformed cycles. */
internal fun Throwable.causeSequence(): Sequence<Throwable> = sequence {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this@causeSequence
    while (current != null) {
        val cause = current
        if (!seen.add(cause)) break
        yield(cause)
        current = cause.cause
    }
}

private const val BAD_REQUEST = 400
private const val CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded"
