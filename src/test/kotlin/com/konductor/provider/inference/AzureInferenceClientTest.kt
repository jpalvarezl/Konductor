package com.konductor.provider.inference

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import kotlin.test.Test

class AzureInferenceClientTest {

    // A static, offline credential so the smoke tests are deterministic (no `az login` / network).
    private val fakeCredential = TokenCredential { _ ->
        Mono.just(AccessToken("fake-token", OffsetDateTime.now().plusHours(1)))
    }

    private fun configuration(promptAgentName: String? = null) = Configuration(
        projectEndpoint = "https://smoke.ai.azure.com/api/projects/p",
        tokenCredential = fakeCredential,
        model = "gpt-5",
        promptAgentName = promptAgentName,
    )

    /**
     * M0 smoke test: the ephemeral Foundry Responses client is constructible from a resolved [Configuration]
     * (endpoint + credential) without runtime errors. The live endpoint + `az login` path is exercised manually.
     */
    @Test
    fun `builds the ephemeral Responses client from configuration`() {
        assertDoesNotThrow { AzureInferenceClient(configuration()) }
    }

    /**
     * M2.5: with a prompt agent set (via config or the explicit param the hot-swap factory uses), it builds
     * against the agent-scoped Responses surface instead — also constructible offline (no network until a turn).
     */
    @Test
    fun `builds an agent-scoped Responses client when a prompt agent is set`() {
        assertDoesNotThrow { AzureInferenceClient(configuration(promptAgentName = "billing-agent")) }
        assertDoesNotThrow { AzureInferenceClient(configuration(), promptAgentName = "billing-agent") }
    }
}
