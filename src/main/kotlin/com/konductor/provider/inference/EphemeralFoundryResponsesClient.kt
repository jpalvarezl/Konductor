package com.konductor.provider.inference

import com.azure.core.exception.HttpResponseException
import com.konductor.core.models.ToolSpec
import com.openai.errors.OpenAIRetryableException
import com.openai.errors.OpenAIServiceException
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.util.concurrent.TimeoutException

/**
 * Foundry Responses adapter for the **ephemeral** Prompt path (the default, with no persisted agent). It receives
 * the project-composed client for `{projectEndpoint}/openai/v1` and owns that blocking OpenAI client rather than the
 * Azure `ResponsesAsyncClient` wrapper (which discards the
 * closeable client and cannot release its executor). Streaming stays a plain flow over the iterable
 * `StreamResponse` on [Dispatchers.IO]. The Responses-to-domain mapping is shared in `ResponsesMapping.kt`.
 *
 * Persisted PromptAgent calls use the separate [PromptAgentFoundryResponsesClient], selected by
 * [SwitchableFoundryResponsesClient], because the two Foundry request shapes differ.
 */
class EphemeralFoundryResponsesClient(
    private val client: OpenAIClient,
) : FoundryResponsesClient {

    override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult =
        withTransientRetry { client.createFoundryResponse(buildEphemeralParams(request)) }

    override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> =
        flow {
            val params = buildEphemeralParams(request)
            var attempt = 0
            var backoffMs = INITIAL_RETRY_DELAY_MS
            while (true) {
                var emittedModelOutput = false
                try {
                    client.streamFoundryResponse(params).collect { chunk ->
                        if (chunk is FoundryResponsesEvent.TextDelta || chunk is FoundryResponsesEvent.Completed) {
                            emittedModelOutput = true
                        }
                        emit(chunk)
                    }
                    return@flow
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (
                        emittedModelOutput || !error.isTransientFoundryResponsesError() || attempt >= MAX_RETRIES
                    ) {
                        throw error
                    }
                    attempt += 1
                    emit(
                        FoundryResponsesEvent.Retrying(
                            reason = error.briefDescription(),
                            retryAttempt = attempt,
                            maxRetries = MAX_RETRIES,
                            delayMs = backoffMs,
                        ),
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) { client.close() }
    }

    private suspend fun <T> withTransientRetry(block: suspend () -> T): T {
        var attempt = 0
        var backoffMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!error.isTransientFoundryResponsesError() || attempt >= MAX_RETRIES) throw error
                attempt += 1
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    private fun Throwable.briefDescription(): String? =
        when (this) {
            is OpenAIServiceException -> "HTTP ${statusCode()}"
            is HttpResponseException -> "HTTP ${response.statusCode}"
            else -> message?.lineSequence()?.firstOrNull()?.take(80) ?: this::class.simpleName
        }

    private fun Throwable.isTransientFoundryResponsesError(): Boolean {
        if (this is OpenAIRetryableException) return true
        if (this is OpenAIServiceException) return statusCode() == 429 || statusCode() in 500..599
        if (this is HttpResponseException) return response.statusCode == 429 || response.statusCode in 500..599
        // SocketTimeoutException (a subclass of InterruptedIOException) is a genuine transient read timeout and
        // is retried above. A bare InterruptedIOException is deliberately NOT treated as transient: it typically
        // signals a blocking I/O interrupted by Job/thread cancellation, and retrying it would defeat Esc-cancel.
        if (this is SocketTimeoutException || this is HttpTimeoutException || this is TimeoutException) return true
        return cause?.isTransientFoundryResponsesError() == true
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val INITIAL_RETRY_DELAY_MS = 250L
        const val MAX_RETRY_DELAY_MS = 2_000L
    }
}

/** Full ephemeral request: model + exact assembled instructions + tools + reconstructed transcript. */
internal fun buildEphemeralParams(request: FoundryResponsesRequest): ResponseCreateParams {
    val builder = ResponseCreateParams.builder()
        .model(request.model)
        .instructions(request.systemPrompt)
        .input(ResponseCreateParams.Input.ofResponse(serializeHistory(request.history)))
    request.temperature?.let { builder.temperature(it) }
    request.tools.forEach { builder.addTool(it.toEphemeralFunctionTool()) }
    return builder.build()
}

private fun ToolSpec.toEphemeralFunctionTool(): FunctionTool {
    val schema = FunctionTool.Parameters.builder()
    parameters.forEach { (key, value) ->
        schema.putAdditionalProperty(key, JsonValue.from(value.toEphemeralPlainValue()))
    }
    return FunctionTool.builder()
        .name(name)
        .description(description)
        .parameters(schema.build())
        .strict(false)
        .build()
}

private fun JsonElement.toEphemeralPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonObject -> mapValues { it.value.toEphemeralPlainValue() }
    is JsonArray -> map { it.toEphemeralPlainValue() }
}
