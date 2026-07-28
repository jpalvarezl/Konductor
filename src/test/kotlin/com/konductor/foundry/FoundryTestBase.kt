package com.konductor.foundry

import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.ai.projects.DeploymentsClient
import com.azure.core.http.HttpClient
import com.azure.core.test.TestMode
import com.azure.core.test.TestProxyTestBase
import com.azure.core.test.models.TestProxySanitizer
import com.azure.core.test.models.TestProxySanitizerType
import com.azure.core.test.utils.MockTokenCredential
import com.azure.identity.DefaultAzureCredentialBuilder

/**
 * Minimal Azure Core Test setup shared by recorded Foundry project tests.
 *
 * With `AZURE_TEST_MODE` unset the Azure test framework uses PLAYBACK. RECORD and LIVE use the configured Foundry
 * project and [DefaultAzureCredentialBuilder]; only RECORD writes a local session recording.
 */
abstract class FoundryTestBase : TestProxyTestBase() {
    private var sanitizersRegistered = false

    protected fun deploymentsClient(): DeploymentsClient = projectClientBuilder().buildDeploymentsClient()

    protected fun configuredDeploymentName(): String =
        if (testMode == TestMode.PLAYBACK) REDACTED_DEPLOYMENT else requiredEnvironment("FOUNDRY_MODEL_NAME")

    private fun projectClientBuilder(): AIProjectClientBuilder {
        registerSanitizersOnce()
        val builder = AIProjectClientBuilder().httpClient(
            if (testMode == TestMode.PLAYBACK) interceptorManager.playbackClient else HttpClient.createDefault(),
        )
        return when (testMode) {
            TestMode.PLAYBACK -> builder
                .endpoint(PLAYBACK_ENDPOINT)
                .credential(MockTokenCredential())
            TestMode.RECORD -> builder
                .addPolicy(interceptorManager.recordPolicy)
                .endpoint(requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT"))
                .credential(DefaultAzureCredentialBuilder().build())
            TestMode.LIVE -> builder
                .endpoint(requiredEnvironment("FOUNDRY_PROJECT_ENDPOINT"))
                .credential(DefaultAzureCredentialBuilder().build())
        }
    }

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.trim()?.ifBlank { null }) {
            "$name is required when AZURE_TEST_MODE=$testMode"
        }

    private fun registerSanitizersOnce() {
        if (testMode == TestMode.LIVE || sanitizersRegistered) return
        interceptorManager.addSanitizers(
            listOf(
                url("(?<=/api/projects/)[^/?]+", "REDACTED_PROJECT"),
                url("(?<=/deployments/)[^/?]+", REDACTED_DEPLOYMENT),
                bodyKey("$..name", REDACTED_DEPLOYMENT),
                bodyKey("$..modelName", "REDACTED_MODEL"),
                bodyKey("$..modelVersion", "REDACTED_VERSION"),
                bodyKey("$..modelPublisher", "REDACTED_PUBLISHER"),
                bodyKey("$..connectionName", "REDACTED_CONNECTION"),
                // azure-core-test interpolates these strings directly into admin JSON, so JSON-escape quotes and '\\s'.
                bodyRegex("""\"capacity\"\\s*:\\s*[0-9]+""", """\"capacity\": 0"""),
                header("Date"),
                header("User-Agent"),
                header("apim-request-id"),
                header("x-ms-region"),
            ),
        )
        sanitizersRegistered = true
    }

    private fun url(regex: String, replacement: String): TestProxySanitizer =
        TestProxySanitizer(regex, replacement, TestProxySanitizerType.URL)

    private fun bodyKey(key: String, replacement: String): TestProxySanitizer =
        TestProxySanitizer(key, null, replacement, TestProxySanitizerType.BODY_KEY)

    private fun bodyRegex(regex: String, replacement: String): TestProxySanitizer =
        TestProxySanitizer(regex, replacement, TestProxySanitizerType.BODY_REGEX)

    private fun header(name: String): TestProxySanitizer =
        TestProxySanitizer(name, ".+", "REDACTED", TestProxySanitizerType.HEADER)

    private companion object {
        const val REDACTED_DEPLOYMENT = "REDACTED_DEPLOYMENT"
        const val PLAYBACK_ENDPOINT = "https://localhost:8080/api/projects/REDACTED_PROJECT"
    }
}
