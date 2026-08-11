package com.konductor.provider.hosted

import com.azure.ai.agents.AgentsClientBuilder
import com.azure.ai.agents.implementation.http.HttpClientHelper
import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.azure.core.http.HttpClient
import com.azure.core.http.HttpHeaderName
import com.azure.core.http.HttpHeaders
import com.azure.core.http.HttpPipelineBuilder
import com.azure.core.http.HttpRequest
import com.azure.core.http.HttpResponse
import com.azure.core.util.BinaryData
import com.openai.core.JsonValue
import com.openai.models.responses.ResponseCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executable evidence for issue #101 against azure-ai-agents 2.3.0 / openai-java 4.45.0.
 *
 * The Azure adapter's direct future is a valid control: cancelling it cancels the delayed transport subscription.
 * The public agent-scoped Responses unary future and pre-header streaming close do not reach that same subscription.
 * Sync streaming close is also checked after headers: it blocks behind openai-java's active BufferedReader and does
 * not cancel the body subscription. These assertions distinguish those limitations from the proven interruptible
 * sync unary path rather than faking success at the coroutine layer.
 */
class AgentScopedResponsesCancellationEvidenceTest {
    @Test
    fun `direct Azure adapter future cancellation reaches transport`() {
        val transport = DelayedHttpClient()
        val pipeline = HttpPipelineBuilder().httpClient(transport).build()
        val adapter = HttpClientHelper.mapToOpenAIHttpClient(pipeline)
        val request = com.openai.core.http.HttpRequest.builder()
            .method(com.openai.core.http.HttpMethod.POST)
            .baseUrl("https://example.invalid")
            .addPathSegments("responses")
            .build()

        val future = adapter.executeAsync(request)
        assertTrue(transport.awaitStarted(), "the direct adapter request did not reach the Azure transport")

        assertTrue(future.cancel(true))
        assertTrue(
            transport.awaitCancelled(),
            "cancelling HttpClientHelper's direct Mono.toFuture must cancel the transport subscription",
        )
    }

    @Test
    fun `interruptible Hosted invoke cancels transport and reuses same binding`() = runBlocking {
        val transport = CancelThenSuccessHttpClient()
        val builder = agentsBuilder(transport)
        val boundary = AzureHostedAgentClient(
            builder.buildAgentsClient(),
            builder.buildAgentScopedOpenAIClient(AGENT_NAME),
        )
        try {
            val first = launch(Dispatchers.Default) { boundary.invoke(AGENT_NAME, SESSION_ID, "first") }
            assertHostedRequest(transport.awaitRequest())
            first.cancelAndJoin()
            assertTrue(transport.awaitFirstCancelled(), "first Hosted invoke did not cancel Azure transport")
            assertEquals(1, transport.sendCount(), "cancellation must not trigger a retry request")
            assertEquals(0, transport.pendingRequestCount())

            val response = boundary.invoke(AGENT_NAME, SESSION_ID, "second")
            assertHostedRequest(transport.awaitRequest())
            assertEquals("same binding survived", response.text)
            assertEquals(2, transport.sendCount())
        } finally {
            boundary.close()
        }
    }

    @Test
    fun `public agent scoped unary future cancellation does not reach transport`() {
        val transport = DelayedHttpClient()
        val client = agentScopedClient(transport)
        try {
            val future = client.responses().create(hostedParams())
            assertTrue(transport.awaitStarted(), "the public Responses request did not reach the Azure transport")
            assertHostedRequest(transport.request())

            assertTrue(future.cancel(true))
            assertTrue(future.isCancelled)
            assertFalse(
                transport.wasCancelledWithinObservationBound(),
                "azure-ai-agents/openai-java unexpectedly began propagating public future cancellation; " +
                    "re-evaluate issue #101 and replace this blocker assertion with a positive implementation proof",
            )
        } finally {
            transport.failOutstanding()
            client.close()
        }
    }

    @Test
    fun `public agent scoped streaming close before headers does not reach transport`() {
        val transport = DelayedHttpClient()
        val client = agentScopedClient(transport)
        try {
            val stream = client.responses().createStreaming(hostedParams())
            assertTrue(transport.awaitStarted(), "the public streaming request did not reach the Azure transport")
            assertHostedRequest(transport.request())

            stream.close()
            assertFalse(
                transport.wasCancelledWithinObservationBound(),
                "openai-java streaming close unexpectedly began cancelling the pre-header transport; " +
                    "re-evaluate issue #101 and add the same-binding production proof",
            )
        } finally {
            transport.failOutstanding()
            client.close()
        }
    }

    @Test
    fun `public sync streaming close after headers blocks behind body read`() {
        val transport = StreamingHeadersHttpClient()
        val client = agentsBuilder(transport).buildAgentScopedOpenAIClient(AGENT_NAME)
        val reader = daemonExecutor("i101-stream-reader")
        val closer = daemonExecutor("i101-stream-closer")
        try {
            val stream = client.responses().createStreaming(hostedParams())
            val read = reader.submit { stream.stream().forEach { } }
            assertTrue(transport.awaitBodyStarted(), "the public stream did not subscribe to the response body")
            assertHostedRequest(transport.request())

            val close = closer.submit { stream.close() }
            assertTrue(
                runCatching { close.get(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS) }.exceptionOrNull() is TimeoutException,
                "sync StreamResponse.close unexpectedly completed while readLine was blocked",
            )
            assertFalse(transport.isBodyCancelled(), "the blocked stream close unexpectedly cancelled the body")

            transport.completeBody()
            read.get(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS)
            close.get(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS)
        } finally {
            transport.completeBody()
            reader.shutdownNow()
            closer.shutdownNow()
            client.close()
        }
    }

    private fun daemonExecutor(name: String) = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    private fun agentScopedClient(transport: HttpClient) = agentsBuilder(transport)
        .buildAgentScopedOpenAIAsyncClient(AGENT_NAME)

    private fun agentsBuilder(transport: HttpClient) = AgentsClientBuilder()
        .endpoint(PROJECT_ENDPOINT)
        .credential(TEST_CREDENTIAL)
        .httpClient(transport)
        .allowPreview(true)

    private fun hostedParams() = ResponseCreateParams.builder()
        .input("hold this request")
        .putAdditionalBodyProperty("agent_session_id", JsonValue.from(SESSION_ID))
        .build()

    private fun assertHostedRequest(request: HttpRequest) {
        assertEquals(
            "$PROJECT_ENDPOINT/agents/$AGENT_NAME/endpoint/protocols/openai/responses?api-version=v1",
            request.url.toString(),
        )
        assertTrue(request.bodyAsBinaryData.toString().contains("\"agent_session_id\":\"$SESSION_ID\""))
    }

    private class DelayedHttpClient : HttpClient {
        private val started = CountDownLatch(1)
        private val cancelled = CountDownLatch(1)
        private val request = AtomicReference<HttpRequest>()
        private val sink = AtomicReference<MonoSink<com.azure.core.http.HttpResponse>>()

        override fun send(request: HttpRequest): Mono<com.azure.core.http.HttpResponse> = Mono.create { candidate ->
            this.request.set(request)
            sink.set(candidate)
            candidate.onCancel { cancelled.countDown() }
            started.countDown()
        }

        fun awaitStarted(): Boolean = started.await(START_BOUND_SECONDS, TimeUnit.SECONDS)

        fun awaitCancelled(): Boolean = cancelled.await(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS)

        fun wasCancelledWithinObservationBound(): Boolean =
            cancelled.await(NON_PROPAGATION_OBSERVATION_MS, TimeUnit.MILLISECONDS)

        fun request(): HttpRequest = requireNotNull(request.get())

        fun failOutstanding() {
            sink.getAndSet(null)?.error(IllegalStateException("evidence transport released"))
        }
    }

    private class CancelThenSuccessHttpClient : HttpClient {
        private val invocation = AtomicInteger()
        private val requests = LinkedBlockingQueue<HttpRequest>()
        private val firstCancelled = CountDownLatch(1)

        override fun send(request: HttpRequest): Mono<HttpResponse> {
            requests.put(request)
            return if (invocation.getAndIncrement() == 0) {
                Mono.create { sink -> sink.onCancel { firstCancelled.countDown() } }
            } else {
                Mono.just(JsonHttpResponse(request, SUCCESS_RESPONSE_JSON))
            }
        }

        fun awaitRequest(): HttpRequest = requireNotNull(requests.poll(START_BOUND_SECONDS, TimeUnit.SECONDS)) {
            "Hosted invoke did not reach Azure transport"
        }

        fun awaitFirstCancelled(): Boolean = firstCancelled.await(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS)

        fun sendCount(): Int = invocation.get()

        fun pendingRequestCount(): Int = requests.size
    }

    private class StreamingHeadersHttpClient : HttpClient {
        private val bodyStarted = CountDownLatch(1)
        private val bodyCancelled = CountDownLatch(1)
        private val request = AtomicReference<HttpRequest>()
        private val bodySink = AtomicReference<FluxSink<ByteBuffer>>()

        override fun send(request: HttpRequest): Mono<HttpResponse> {
            this.request.set(request)
            return Mono.just(StreamingResponse(request, bodyStarted, bodyCancelled, bodySink))
        }

        fun awaitBodyStarted(): Boolean = bodyStarted.await(START_BOUND_SECONDS, TimeUnit.SECONDS)

        fun isBodyCancelled(): Boolean = bodyCancelled.count == 0L

        fun completeBody() {
            bodySink.get()?.complete()
        }

        fun request(): HttpRequest = requireNotNull(request.get())
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class JsonHttpResponse(request: HttpRequest, content: String) : HttpResponse(request) {
        private val headers = HttpHeaders().set(HttpHeaderName.CONTENT_TYPE, "application/json")
        private val data = BinaryData.fromString(content)

        override fun getStatusCode(): Int = 200

        override fun getHeaderValue(name: String): String? = headers.getValue(name)

        override fun getHeaders(): HttpHeaders = headers

        override fun getBody(): Flux<ByteBuffer> = data.toFluxByteBuffer()

        override fun getBodyAsByteArray(): Mono<ByteArray> = Mono.just(data.toBytes())

        override fun getBodyAsString(): Mono<String> = Mono.just(data.toString())

        override fun getBodyAsString(charset: Charset): Mono<String> = Mono.just(data.toString())
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class StreamingResponse(
        request: HttpRequest,
        private val bodyStarted: CountDownLatch,
        private val bodyCancelled: CountDownLatch,
        private val bodySink: AtomicReference<FluxSink<ByteBuffer>>,
    ) : HttpResponse(request) {
        private val headers = HttpHeaders().set(HttpHeaderName.CONTENT_TYPE, "text/event-stream")
        private val body = Flux.create<ByteBuffer> { candidate ->
            bodySink.set(candidate)
            candidate.onCancel { bodyCancelled.countDown() }
            bodyStarted.countDown()
        }

        override fun getStatusCode(): Int = 200

        override fun getHeaderValue(name: String): String? = headers.getValue(name)

        override fun getHeaders(): HttpHeaders = headers

        override fun getBody(): Flux<ByteBuffer> = body

        override fun getBodyAsByteArray(): Mono<ByteArray> = Mono.never()

        override fun getBodyAsString(): Mono<String> = Mono.never()

        override fun getBodyAsString(charset: Charset): Mono<String> = Mono.never()
    }

    private companion object {
        const val PROJECT_ENDPOINT = "https://example.invalid"
        const val AGENT_NAME = "hosted-agent"
        const val SESSION_ID = "00000000-0000-0000-0000-000000000101"
        const val START_BOUND_SECONDS = 5L
        const val CANCEL_BOUND_SECONDS = 2L
        const val NON_PROPAGATION_OBSERVATION_MS = 2_000L
        const val SUCCESS_RESPONSE_JSON = """
            {
              "id":"response-101",
              "created_at":0,
              "model":"test-model",
              "object":"response",
              "output":[{
                "type":"message",
                "id":"message-101",
                "role":"assistant",
                "status":"completed",
                "content":[{
                  "type":"output_text",
                  "text":"same binding survived",
                  "annotations":[]
                }]
              }],
              "parallel_tool_calls":false,
              "tool_choice":"auto",
              "tools":[]
            }
        """
        val TEST_CREDENTIAL = TokenCredential {
            Mono.just(AccessToken("test-token", OffsetDateTime.now().plusHours(1)))
        }
    }
}
