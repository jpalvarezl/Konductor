package com.konductor.provider.inference

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.Configuration
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import kotlin.test.Test

class AzureInferenceClientTest {

    // A static, offline credential so the smoke test is deterministic (no `az login` / network).
    private val fakeCredential = TokenCredential { _ ->
        Mono.just(AccessToken("fake-token", OffsetDateTime.now().plusHours(1)))
    }

    /**
     * M0 smoke test: the Foundry Responses client is constructible from a resolved [Configuration]
     * (endpoint + credential) without runtime errors. The live endpoint + `az login` path is
     * exercised manually (docs/spec/providers.md).
     */
    @Test
    fun `builds the Responses client from configuration`() {
        val configuration = Configuration(
            projectEndpoint = "https://smoke.ai.azure.com/api/projects/p",
            tokenCredential = fakeCredential,
            model = "gpt-5",
        )

        assertDoesNotThrow { AzureInferenceClient(configuration) }
    }
}
