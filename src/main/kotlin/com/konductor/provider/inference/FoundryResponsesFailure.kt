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
 * The cause walk is deliberately bounded and identity-based. Cancellation is selected before service-error
 * classification, while malformed accessors and unknown wrappers fail closed as the original error.
 */
internal fun mapFoundryResponsesFailure(error: Throwable): Throwable {
    val causes = error.boundedCauseChain()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { return it }

    val overflow = causes.filterIsInstance<OpenAIServiceException>().firstOrNull { serviceError ->
        runCatching {
            serviceError.statusCode() == BAD_REQUEST &&
                serviceError.code().orElse(null) == CONTEXT_LENGTH_EXCEEDED
        }.getOrDefault(false)
    }
    return overflow?.let(::PromptContextOverflowException) ?: error
}

private fun Throwable.boundedCauseChain(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val chain = ArrayList<Throwable>(MAX_CAUSES)
    var current: Throwable? = this
    while (current != null && chain.size < MAX_CAUSES && seen.add(current)) {
        chain += current
        current = runCatching { current.cause }.getOrNull()
    }
    return chain
}

private const val MAX_CAUSES = 8
private const val BAD_REQUEST = 400
private const val CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded"
