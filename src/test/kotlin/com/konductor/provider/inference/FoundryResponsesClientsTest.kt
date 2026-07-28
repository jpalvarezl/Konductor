package com.konductor.provider.inference

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import com.konductor.foundry.project.FoundryProjectRuntime
import kotlinx.coroutines.runBlocking
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import kotlin.test.Test

class FoundryResponsesClientsTest {

    // A static, offline credential so the smoke tests are deterministic (no az login / network).
    private val fakeCredential = TokenCredential { _ ->
        Mono.just(AccessToken("fake-token", OffsetDateTime.parse("2099-01-01T00:00:00Z")))
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
    fun `builds the ephemeral Responses client from project composition`() {
        val configuration = configuration()
        val runtime = FoundryProjectRuntime.create(configuration).createProvider(configuration)
        runBlocking { runtime.close() }
    }

    /**
     * M2.5: the PromptAgent-bound Foundry Responses adapter is separate from the ephemeral adapter and is also
     * constructible offline (no network until a turn actually runs).
     */
    @Test
    fun `builds the PromptAgent Foundry Responses adapter from project composition`() {
        val configuration = configuration().copy(promptAgentName = "billing-agent")
        val runtime = FoundryProjectRuntime.create(configuration).createProvider(configuration)
        runBlocking { runtime.close() }
    }
}
