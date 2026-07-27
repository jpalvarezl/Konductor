package com.konductor.foundry.project.connection

import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.azure.core.http.HttpClient
import com.azure.core.http.HttpHeaders
import com.azure.core.http.HttpRequest
import com.azure.core.http.HttpResponse
import com.azure.core.util.Context
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class FoundryConnectionCatalogTest {
    @Test
    fun `lists an empty project without exposing SDK models`() {
        val httpClient = RecordingHttpClient { """{"value":[]}""" }
        val catalog: FoundryConnectionCatalog = catalog(httpClient)

        assertTrue(catalog.listConnections().isEmpty())
        assertTrue(FoundryConnection::class.java.declaredFields.none { it.type.name.startsWith("com.azure.") })
        assertEquals(1, httpClient.requests.size)
        assertTrue(httpClient.requests.single().url.path.endsWith("/connections"))
        assertCredentialSafeRequests(httpClient)
    }

    @Test
    fun `maps multiple connections including defaults metadata and credential type`() {
        val httpClient = RecordingHttpClient {
            """
            {
              "value": [
                {
                  "name": "models",
                  "id": "/connections/models",
                  "type": "AzureOpenAI",
                  "target": "https://models.example",
                  "isDefault": true,
                  "credentials": {"type": "ApiKey", "key": "must-not-escape"},
                  "metadata": {"region": "eastus", "purpose": "chat"}
                },
                {
                  "name": "search",
                  "id": "/connections/search",
                  "type": "CognitiveSearch",
                  "target": "https://search.example",
                  "isDefault": false,
                  "credentials": {"type": "AAD"},
                  "metadata": {}
                }
              ]
            }
            """.trimIndent()
        }
        val catalog: FoundryConnectionCatalog = catalog(httpClient)

        val connections = catalog.listConnections()

        assertEquals(
            listOf(
                FoundryConnection(
                    name = "models",
                    id = "/connections/models",
                    type = "AzureOpenAI",
                    target = "https://models.example",
                    isDefault = true,
                    metadata = mapOf("region" to "eastus", "purpose" to "chat"),
                    credentialType = "ApiKey",
                ),
                FoundryConnection(
                    name = "search",
                    id = "/connections/search",
                    type = "CognitiveSearch",
                    target = "https://search.example",
                    isDefault = false,
                    metadata = emptyMap(),
                    credentialType = "AAD",
                ),
            ),
            connections,
        )
        assertFalse(connections.joinToString().contains("must-not-escape"))
        assertCredentialSafeRequests(httpClient)
    }

    @Test
    fun `named lookup explicitly uses the no-credentials operation`() {
        val httpClient = RecordingHttpClient {
            """
            {
              "name": "search",
              "id": "/connections/search",
              "type": "CognitiveSearch",
              "target": "https://search.example",
              "isDefault": true,
              "credentials": {"type": "ApiKey", "key": "must-not-escape"},
              "metadata": {"index": "docs"}
            }
            """.trimIndent()
        }
        val catalog: FoundryConnectionCatalog = catalog(httpClient)

        val connection = catalog.getConnection("search")

        assertEquals("search", connection.name)
        assertEquals("ApiKey", connection.credentialType)
        assertEquals(mapOf("index" to "docs"), connection.metadata)
        assertFalse(connection.toString().contains("must-not-escape"))
        assertEquals(1, httpClient.requests.size)
        assertTrue(httpClient.requests.single().url.path.endsWith("/connections/search"))
        assertCredentialSafeRequests(httpClient)
    }

    private fun catalog(httpClient: RecordingHttpClient): FoundryConnectionCatalog {
        val credential = TokenCredential {
            Mono.just(AccessToken("test-token", OffsetDateTime.now().plusHours(1)))
        }
        val connectionsClient = AIProjectClientBuilder()
            .endpoint("https://example.test/api/projects/test-project")
            .credential(credential)
            .httpClient(httpClient)
            .buildConnectionsClient()
        return AzureFoundryConnectionCatalog(connectionsClient)
    }

    private fun assertCredentialSafeRequests(httpClient: RecordingHttpClient) {
        assertTrue(httpClient.requests.all { it.httpMethod.name == "GET" })
        assertTrue(httpClient.requests.none { it.url.path.contains("getConnectionWithCredentials") })
        assertTrue(
            httpClient.requests.none { it.url.toString().contains("includeCredentials=true", ignoreCase = true) },
        )
    }
}

private class RecordingHttpClient(
    private val responseBody: (HttpRequest) -> String,
) : HttpClient {
    val requests = mutableListOf<HttpRequest>()

    override fun send(request: HttpRequest): Mono<HttpResponse> {
        requests += request
        return Mono.just(StaticHttpResponse(request, responseBody(request)))
    }

    override fun send(request: HttpRequest, context: Context): Mono<HttpResponse> = send(request)
}

private class StaticHttpResponse(
    request: HttpRequest,
    body: String,
) : HttpResponse(request) {
    private val bytes = body.toByteArray(StandardCharsets.UTF_8)
    private val responseHeaders = HttpHeaders(mapOf("Content-Type" to "application/json"))

    override fun getStatusCode(): Int = 200

    override fun getHeaderValue(name: String): String? = responseHeaders.getValue(name)

    override fun getHeaders(): HttpHeaders = responseHeaders

    override fun getBody(): Flux<ByteBuffer> = Flux.just(ByteBuffer.wrap(bytes))

    override fun getBodyAsByteArray(): Mono<ByteArray> = Mono.just(bytes.copyOf())

    override fun getBodyAsString(): Mono<String> = Mono.just(String(bytes, StandardCharsets.UTF_8))

    override fun getBodyAsString(charset: Charset): Mono<String> = Mono.just(String(bytes, charset))
}
