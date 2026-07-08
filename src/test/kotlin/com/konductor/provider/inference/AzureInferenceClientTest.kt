package com.konductor.provider.inference

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import kotlin.test.Test

class AzureInferenceClientTest {

    // A static, offline credential so the smoke tests are deterministic (no az login / network).
    private val fakeCredential = TokenCredential { _ ->
        Mono.just(AccessToken("fake-token", OffsetDateTime.now().plusHours(1)))
    }

    private fun configuration() = Configuration(
        projectEndpoint = "https://smoke.ai.azure.com/api/projects/p",
        tokenCredential = fakeCredential,
        model = "gpt-5",
    )

    /**
     * M0 smoke test: the ephemeral Foundry Responses client is constructible from a resolved Configuration without
     * runtime errors. The live endpoint + az login path is exercised manually.
     */
    @Test
    fun `builds the ephemeral Responses client from configuration`() {
        assertDoesNotThrow { AzureInferenceClient(configuration()) }
    }

    /**
     * M2.5: the agent-scoped inference client is a separate impl (not a branch of the ephemeral one), also
     * constructible offline (no network until a turn actually runs).
     */
    @Test
    fun `builds the agent-scoped inference client for a prompt agent`() {
        assertDoesNotThrow { AzurePromptAgentInferenceClient(configuration(), "billing-agent") }
    }
}
