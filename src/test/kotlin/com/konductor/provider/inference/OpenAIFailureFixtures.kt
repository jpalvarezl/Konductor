package com.konductor.provider.inference

import com.openai.client.OpenAIClient
import com.openai.core.http.Headers
import com.openai.errors.OpenAIServiceException
import com.openai.errors.UnexpectedStatusCodeException
import com.openai.models.ErrorObject
import com.openai.services.blocking.ResponseService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.Proxy
import java.util.Optional

internal fun serviceFailure(status: Int, fixture: String?): OpenAIServiceException {
    val error = fixture?.let { resource ->
        runCatching {
            val body = requireNotNull(object {}.javaClass.getResource(resource)).readText()
            val fields = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
            ErrorObject.builder()
                .message(fields.getValue("message").jsonPrimitive.content)
                .type(fields.getValue("type").jsonPrimitive.content)
                .code(Optional.ofNullable(fields["code"]?.jsonPrimitive?.contentOrNull))
                .param(Optional.ofNullable(fields["param"]?.jsonPrimitive?.contentOrNull))
                .build()
        }.getOrNull()
    }
    return UnexpectedStatusCodeException.builder()
        .statusCode(status)
        .headers(Headers.builder().build())
        .error(Optional.ofNullable(error))
        .build()
}

internal class ThrowingOpenAIClient(failures: List<Throwable>) {
    private val queue = ArrayDeque(failures)
    var calls: Int = 0
        private set

    private val responses = proxy<ResponseService> { method ->
        when (method) {
            "create", "createStreaming" -> {
                calls++
                throw if (queue.isEmpty()) {
                    error("No queued OpenAI failure for call #$calls")
                } else {
                    queue.removeFirst()
                }
            }
            "toString" -> "ThrowingResponseService"
            else -> error("Unexpected ResponseService method: $method")
        }
    }

    val client: OpenAIClient = proxy { method ->
        when (method) {
            "responses" -> responses
            "close" -> Unit
            "toString" -> "ThrowingOpenAIClient"
            else -> error("Unexpected OpenAIClient method: $method")
        }
    }
}

private inline fun <reified T> proxy(crossinline invoke: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        invoke(method.name)
    } as T
