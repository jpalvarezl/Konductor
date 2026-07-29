package com.konductor.foundry

import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.ai.projects.ConnectionsClient
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

    protected fun deploymentsClient(): DeploymentsClient =
        projectClientBuilder(RecordedOperation.DEPLOYMENT).buildDeploymentsClient()

    protected fun configuredDeploymentName(): String = configuredValue(REDACTED_DEPLOYMENT, "FOUNDRY_MODEL_NAME")

    protected fun connectionsClient(): ConnectionsClient =
        projectClientBuilder(RecordedOperation.CONNECTION).buildConnectionsClient()

    protected fun configuredConnectionName(): String = configuredValue(REDACTED_CONNECTION, "FOUNDRY_CONNECTION_NAME")

    private fun configuredValue(playbackValue: String, environmentName: String): String =
        if (testMode == TestMode.PLAYBACK) playbackValue else requiredEnvironment(environmentName)

    private fun projectClientBuilder(operation: RecordedOperation): AIProjectClientBuilder {
        registerSanitizersOnce(operation)
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

    private fun registerSanitizersOnce(operation: RecordedOperation) {
        if (testMode == TestMode.LIVE || sanitizersRegistered) return
        val sanitizers = mutableListOf(
            url("(?<=/api/projects/)[^/?]+", "REDACTED_PROJECT"),
            header("Date"),
            header("User-Agent"),
            header("apim-request-id"),
            header("x-ms-region"),
        )
        sanitizers += when (operation) {
            RecordedOperation.DEPLOYMENT -> listOf(
                url("(?<=/deployments/)[^/?]+", REDACTED_DEPLOYMENT),
                bodyKey("$..name", REDACTED_DEPLOYMENT),
                bodyKey("$..modelName", "REDACTED_MODEL"),
                bodyKey("$..modelVersion", "REDACTED_VERSION"),
                bodyKey("$..modelPublisher", "REDACTED_PUBLISHER"),
                bodyKey("$..connectionName", REDACTED_CONNECTION),
                // azure-core-test interpolates these strings directly into admin JSON, so JSON-escape quotes and '\\s'.
                bodyRegex("""\"capacity\"\\s*:\\s*[0-9]+""", """\"capacity\": 0"""),
            )
            RecordedOperation.CONNECTION -> listOf(
                url("(?<=/connections/)[^/?]+", REDACTED_CONNECTION),
                bodyKey("$..name", REDACTED_CONNECTION),
                bodyKey("$..ResourceId", "REDACTED_RESOURCE_ID"),
                header("azureml-served-by-cluster"),
            )
        }
        interceptorManager.addSanitizers(sanitizers)
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

    private enum class RecordedOperation {
        DEPLOYMENT,
        CONNECTION,
    }

    private companion object {
        const val REDACTED_CONNECTION = "REDACTED_CONNECTION"
        const val REDACTED_DEPLOYMENT = "REDACTED_DEPLOYMENT"
        const val PLAYBACK_ENDPOINT = "https://localhost:8080/api/projects/REDACTED_PROJECT"
    }
}
