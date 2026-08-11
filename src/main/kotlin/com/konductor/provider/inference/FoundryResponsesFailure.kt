package com.konductor.provider.inference

import com.openai.errors.OpenAIServiceException
import kotlinx.coroutines.CancellationException

/** SDK-free signal that a Prompt Responses request exceeded the model context window. */
class PromptContextOverflowException(cause: Throwable) :
    RuntimeException("Prompt context length exceeded.", cause)

/**
 * Translate the pinned openai-java Responses failure into Konductor's application-level failure vocabulary.
 *
 * openai-java documents service failures as [OpenAIServiceException], and a LIVE overflow probe through the Azure
 * Agents-composed client produced a direct `BadRequestException` with no nested cause. Classification therefore checks
 * only the throwable delivered at this adapter seam. Cancellation is propagated first; malformed accessors and unknown
 * failures fail closed as the original error.
 */
internal fun mapFoundryResponsesFailure(error: Throwable): Throwable {
    if (error is CancellationException) return error
    val serviceError = error as? OpenAIServiceException ?: return error
    val isOverflow = runCatching {
        serviceError.statusCode() == BAD_REQUEST &&
            serviceError.code().orElse(null) == CONTEXT_LENGTH_EXCEEDED
    }.getOrDefault(false)
    return if (isOverflow) PromptContextOverflowException(serviceError) else error
}

private const val BAD_REQUEST = 400
private const val CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded"
