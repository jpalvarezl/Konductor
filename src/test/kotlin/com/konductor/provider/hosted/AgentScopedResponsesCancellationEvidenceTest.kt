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
import com.konductor.core.models.AgentContext
import com.konductor.core.models.HostedSessionBinding
import com.konductor.core.models.UserEntry
import com.konductor.provider.AgentEvent
import com.konductor.provider.ToolExecutor
import com.konductor.provider.TurnRequest
import com.openai.client.OpenAIClient
import com.openai.core.JsonValue
import com.openai.models.responses.ResponseCreateParams
import com.openai.services.blocking.ResponseService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import java.lang.reflect.Proxy
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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Executable evidence for issue #101 against azure-ai-agents 2.3.0 / openai-java 4.45.0.
 *
 * The Azure adapter's direct future is a valid control: cancelling it cancels the delayed Azure HttpClient publisher
 * subscription. The public agent-scoped Responses unary future and pre-header streaming close do not reach that same
 * subscription within the bounded observation window. Sync streaming close is also checked after headers: it blocks
 * behind openai-java's active BufferedReader and does not cancel the body subscription within that window. These are
 * subscription-level observations at Azure's HttpClient seam, not wire-level socket evidence.
 */
class AgentScopedResponsesCancellationEvidenceTest {
    private val noTools = ToolExecutor { call -> error("unexpected client-side tool '${call.name}'") }

    @Test
    fun `direct Azure adapter future cancellation reaches Azure HttpClient subscription`() {
        val transport = DelayedHttpClient()
        val pipeline = HttpPipelineBuilder().httpClient(transport).build()
        val adapter = HttpClientHelper.mapToOpenAIHttpClient(pipeline)
        val request = com.openai.core.http.HttpRequest.builder()
            .method(com.openai.core.http.HttpMethod.POST)
            .baseUrl("https://example.invalid")
            .addPathSegments("responses")
            .build()

        val future = adapter.executeAsync(request)
        assertTrue(
            transport.awaitReady(),
            "the direct adapter publisher was not subscribed after onCancel registration",
        )

        assertTrue(future.cancel(true))
        assertTrue(
            transport.awaitCancelled(),
            "cancelling HttpClientHelper's direct Mono.toFuture must cancel the Azure HttpClient subscription",
        )
    }

    @Test
    fun `Hosted provider cancellation reaches Azure subscription and retains binding`() = runBlocking {
        val transport = CancelThenSuccessHttpClient()
        val builder = agentsBuilder(transport)
        val boundary = AzureHostedAgentClient(
            builder.buildAgentsClient(),
            builder.buildAgentScopedOpenAIClient(AGENT_NAME),
        )
        val client = ProviderBoundaryHostedClient(boundary)
        val provider = HostedProvider(
            client,
            AGENT_NAME,
            "repo/image:tag",
            sessionPollAttempts = 1,
            sessionPollIntervalMs = 0,
        )
        try {
            provider.activate(HostedSessionBinding(AGENT_NAME, SESSION_ID), hasLocalEntries = true)
            val callerCancellation = CancellationException("caller cancelled Hosted turn")
            val first = async(Dispatchers.Default) { provider.runTurn(turn("first"), noTools).toList() }
            assertHostedRequest(transport.awaitReadyRequest())

            first.cancel(callerCancellation)
            withTimeout(CANCEL_BOUND_MILLIS) { first.join() }
            val observedCancellation = runCatching { first.await() }.exceptionOrNull()
            assertIs<CancellationException>(observedCancellation, "the caller must observe coroutine cancellation")
            assertEquals(callerCancellation.message, observedCancellation.message)
            assertTrue(
                transport.awaitFirstCancelled(),
                "first Hosted invoke did not cancel the Azure HttpClient publisher subscription",
            )
            assertFalse(
                transport.observedAnotherRequestWithinNegativeBound(),
                "the default retry pipeline unexpectedly sent another request after subscription cancellation",
            )
            assertEquals(1, transport.sendCount())
            assertEquals(1, transport.subscriptionCount())

            val events = provider.runTurn(turn("second"), noTools).toList()
            assertHostedRequest(transport.awaitReadyRequest())
            assertEquals("same binding survived", events.filterIsInstance<AgentEvent.TextDelta>().single().text)
            assertIs<AgentEvent.TurnCompleted>(events.last())
            assertEquals(listOf(SESSION_ID, SESSION_ID), client.invokedSessionIds)
            assertEquals(1, client.getSessionCount)
            assertEquals(0, client.createSessionCount)
            assertTrue(client.deletedSessionIds.isEmpty())
            assertEquals(2, transport.sendCount())
            assertEquals(2, transport.subscriptionCount())
        } finally {
            provider.close()
        }
    }

    @Test
    fun `active invoke failure is propagated as the same instance`() = runBlocking {
        val activeFailure = IllegalStateException("active Responses failure")
        val builder = agentsBuilder(NoRequestHttpClient)
        val boundary = AzureHostedAgentClient(
            builder.buildAgentsClient(),
            failingOpenAIClient(activeFailure),
        )
        try {
            val observedFailure = runCatching {
                boundary.invoke(AGENT_NAME, SESSION_ID, "fail")
            }.exceptionOrNull()

            assertSame(activeFailure, observedFailure)
        } finally {
            boundary.close()
        }
    }

    @Test
    fun `public agent scoped unary future cancellation does not reach Azure subscription within bound`() {
        val transport = DelayedHttpClient()
        val client = agentScopedClient(transport)
        try {
            val future = client.responses().create(hostedParams())
            assertTrue(transport.awaitReady(), "the public Responses publisher was not ready for cancellation")
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
    fun `public agent scoped pre header close does not reach Azure subscription within bound`() {
        val transport = DelayedHttpClient()
        val client = agentScopedClient(transport)
        try {
            val stream = client.responses().createStreaming(hostedParams())
            assertTrue(transport.awaitReady(), "the public streaming publisher was not ready for cancellation")
            assertHostedRequest(transport.request())

            stream.close()
            assertFalse(
                transport.wasCancelledWithinObservationBound(),
                "openai-java streaming close unexpectedly cancelled the pre-header Azure HttpClient subscription; " +
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

    private fun turn(text: String) = TurnRequest(
        AgentContext("server-owned", emptyList(), "hosted"),
        listOf(UserEntry(Uuid.random(), null, Clock.System.now(), text)),
    )

    private fun failingOpenAIClient(failure: RuntimeException): OpenAIClient {
        val responseService = Proxy.newProxyInstance(
            ResponseService::class.java.classLoader,
            arrayOf(ResponseService::class.java),
        ) { _, method, _ ->
            if (method.name == "create") throw failure
            error("unexpected ResponseService method '${method.name}'")
        } as ResponseService
        return Proxy.newProxyInstance(
            OpenAIClient::class.java.classLoader,
            arrayOf(OpenAIClient::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "responses" -> responseService
                "close" -> null
                else -> error("unexpected OpenAIClient method '${method.name}'")
            }
        } as OpenAIClient
    }

    private fun assertHostedRequest(request: HttpRequest) {
        assertEquals(
            "$PROJECT_ENDPOINT/agents/$AGENT_NAME/endpoint/protocols/openai/responses?api-version=v1",
            request.url.toString(),
        )
        assertTrue(request.bodyAsBinaryData.toString().contains("\"agent_session_id\":\"$SESSION_ID\""))
    }

    private class DelayedHttpClient : HttpClient {
        private val ready = CountDownLatch(1)
        private val cancelled = CountDownLatch(1)
        private val request = AtomicReference<HttpRequest>()
        private val sink = AtomicReference<MonoSink<com.azure.core.http.HttpResponse>>()

        override fun send(request: HttpRequest): Mono<com.azure.core.http.HttpResponse> = Mono.create { candidate ->
            candidate.onCancel { cancelled.countDown() }
            this.request.set(request)
            sink.set(candidate)
            // Readiness is deliberately last: callers may cancel only after Mono subscription and onCancel setup.
            ready.countDown()
        }

        fun awaitReady(): Boolean = ready.await(START_BOUND_SECONDS, TimeUnit.SECONDS)

        fun awaitCancelled(): Boolean = cancelled.await(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS)

        fun wasCancelledWithinObservationBound(): Boolean =
            cancelled.await(NON_PROPAGATION_OBSERVATION_MS, TimeUnit.MILLISECONDS)

        fun request(): HttpRequest = requireNotNull(request.get())

        fun failOutstanding() {
            sink.getAndSet(null)?.error(IllegalStateException("evidence publisher released"))
        }
    }

    private class CancelThenSuccessHttpClient : HttpClient {
        private val sends = AtomicInteger()
        private val subscriptions = AtomicInteger()
        private val requests = LinkedBlockingQueue<HttpRequest>()
        private val firstCancelled = CountDownLatch(1)

        override fun send(request: HttpRequest): Mono<HttpResponse> {
            val sendIndex = sends.getAndIncrement()
            return Mono.create { sink ->
                subscriptions.incrementAndGet()
                if (sendIndex == 0) {
                    sink.onCancel { firstCancelled.countDown() }
                }
                // Readiness is deliberately last: callers may cancel only after subscription and onCancel setup.
                requests.put(request)
                if (sendIndex > 0) sink.success(JsonHttpResponse(request, SUCCESS_RESPONSE_JSON))
            }
        }

        fun awaitReadyRequest(): HttpRequest = requireNotNull(requests.poll(START_BOUND_SECONDS, TimeUnit.SECONDS)) {
            "Hosted invoke did not subscribe to the Azure HttpClient publisher"
        }

        fun awaitFirstCancelled(): Boolean = firstCancelled.await(CANCEL_BOUND_SECONDS, TimeUnit.SECONDS)

        fun observedAnotherRequestWithinNegativeBound(): Boolean =
            requests.poll(NON_PROPAGATION_OBSERVATION_MS, TimeUnit.MILLISECONDS) != null

        fun sendCount(): Int = sends.get()

        fun subscriptionCount(): Int = subscriptions.get()
    }

    private class ProviderBoundaryHostedClient(
        private val delegate: AzureHostedAgentClient,
    ) : HostedAgentClient {
        val invokedSessionIds = mutableListOf<String>()
        val deletedSessionIds = mutableListOf<String>()
        var getSessionCount = 0
        var createSessionCount = 0

        override suspend fun selectOrCreateAgentVersion(
            agentName: String,
            containerImage: String,
        ): HostedAgentVersion = error("provider must reuse the active durable binding")

        override suspend fun configureResponsesEndpoint(agentName: String, version: String) =
            error("provider must not configure an endpoint while reusing a durable binding")

        override suspend fun createSession(agentName: String, version: String): HostedAgentSession {
            createSessionCount++
            error("provider must not create an ephemeral session")
        }

        override suspend fun createSession(
            agentName: String,
            version: String,
            sessionId: String,
        ): HostedAgentSession {
            createSessionCount++
            error("provider must not replace a durable session")
        }

        override suspend fun getSession(agentName: String, sessionId: String): HostedAgentSession {
            getSessionCount++
            return HostedAgentSession(sessionId, HOSTED_VERSION, HostedAgentSessionStatus.Active)
        }

        override suspend fun invoke(agentName: String, sessionId: String, input: String): HostedAgentResponse {
            invokedSessionIds += sessionId
            return delegate.invoke(agentName, sessionId, input)
        }

        override fun streamSessionLogs(agentName: String, version: String, sessionId: String): Flow<String> =
            emptyFlow()

        override suspend fun deleteSession(agentName: String, sessionId: String) {
            deletedSessionIds += sessionId
        }

        override suspend fun close() = delegate.close()
    }

    private object NoRequestHttpClient : HttpClient {
        override fun send(request: HttpRequest): Mono<HttpResponse> =
            Mono.error(AssertionError("active failure test must not send an HTTP request"))
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
        const val HOSTED_VERSION = "version-101"
        const val START_BOUND_SECONDS = 5L
        const val CANCEL_BOUND_SECONDS = 2L
        const val CANCEL_BOUND_MILLIS = CANCEL_BOUND_SECONDS * 1_000L
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
