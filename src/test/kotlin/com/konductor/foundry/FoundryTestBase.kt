package com.konductor.foundry

import com.azure.ai.agents.AgentsClient
import com.azure.ai.agents.AgentsClientBuilder
import com.azure.ai.projects.AIProjectClientBuilder
import com.azure.ai.projects.ConnectionsClient
import com.azure.ai.projects.DeploymentsClient
import com.azure.core.http.HttpClient
import com.azure.core.test.TestMode
import com.azure.core.test.TestProxyTestBase
import com.azure.core.test.models.CustomMatcher
import com.azure.core.test.models.TestProxySanitizer
import com.azure.core.test.models.TestProxySanitizerType
import com.azure.core.test.utils.MockTokenCredential
import com.azure.identity.DefaultAzureCredentialBuilder
import com.openai.client.OpenAIClient

/**
 * Minimal Azure Core Test setup shared by recorded Foundry service tests.
 *
 * With `AZURE_TEST_MODE` unset the Azure test framework uses PLAYBACK. RECORD and LIVE use the configured Foundry
 * project and [DefaultAzureCredentialBuilder]; only RECORD writes a local session recording.
 */
abstract class FoundryTestBase : TestProxyTestBase() {
    private var proxyRulesRegistered = false

    protected fun deploymentsClient(): DeploymentsClient =
        projectClientBuilder(RecordedOperation.DEPLOYMENT).buildDeploymentsClient()

    protected fun configuredDeploymentName(): String = configuredValue(REDACTED_DEPLOYMENT, "FOUNDRY_MODEL_NAME")

    protected fun connectionsClient(): ConnectionsClient =
        projectClientBuilder(RecordedOperation.CONNECTION).buildConnectionsClient()

    protected fun configuredConnectionName(): String = configuredValue(REDACTED_CONNECTION, "FOUNDRY_CONNECTION_NAME")

    protected fun promptAgentsClient(): AgentsClient =
        agentsClientBuilder(allowPreview = false).buildAgentsClient()

    protected fun promptAgentOpenAIClient(agentName: String): OpenAIClient =
        agentsClientBuilder(allowPreview = true).buildAgentScopedOpenAIClient(agentName)

    protected fun configuredPromptAgentName(): String = PROMPT_AGENT_NAME

    protected fun configuredPromptAgentModelName(): String = configuredValue(REDACTED_MODEL, "FOUNDRY_MODEL_NAME")

    private fun configuredValue(playbackValue: String, environmentName: String): String =
        if (testMode == TestMode.PLAYBACK) playbackValue else requiredEnvironment(environmentName)

    private fun projectClientBuilder(operation: RecordedOperation): AIProjectClientBuilder {
        registerProxyRulesOnce(operation)
        val builder = AIProjectClientBuilder().httpClient(modeHttpClient())
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

    private fun agentsClientBuilder(allowPreview: Boolean): AgentsClientBuilder {
        registerProxyRulesOnce(RecordedOperation.PROMPT_AGENT)
        val builder = AgentsClientBuilder()
            .httpClient(modeHttpClient())
            .also { if (allowPreview) it.allowPreview(true) }
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

    private fun modeHttpClient(): HttpClient =
        if (testMode == TestMode.PLAYBACK) interceptorManager.playbackClient else HttpClient.createDefault()

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.trim()?.ifBlank { null }) {
            "$name is required when AZURE_TEST_MODE=$testMode"
        }

    private fun registerProxyRulesOnce(operation: RecordedOperation) {
        if (testMode == TestMode.LIVE || proxyRulesRegistered) return
        if (operation == RecordedOperation.PROMPT_AGENT) {
            // The default name sanitizer also rewrites the synthetic agent/tool names needed by playback.
            interceptorManager.removeSanitizers("AZSDK3493")
            interceptorManager.addMatchers(
                CustomMatcher().setExcludedHeaders(
                    listOf(
                        "Accept",
                        "X-Stainless-Arch",
                        "X-Stainless-Lang",
                        "X-Stainless-OS",
                        "X-Stainless-OS-Version",
                        "X-Stainless-Package-Version",
                        "X-Stainless-Runtime",
                        "X-Stainless-Runtime-Version",
                    ),
                ),
            )
        }
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
            RecordedOperation.PROMPT_AGENT -> listOf(
                bodyKey("$..model", REDACTED_MODEL),
                bodyKey("$..principal_id", "REDACTED_ID"),
                bodyKey("$..client_id", "REDACTED_ID"),
                bodyKey("$..blueprint_id", "REDACTED_ID"),
                bodyKey("$..agent_guid", "REDACTED_ID"),
                bodyKey("$..response_id", "REDACTED_ID"),
                header("azureml-served-by-cluster"),
                header("X-Request-ID"),
                header("openai-client-partition-id"),
                header("openai-organization"),
                header("openai-project"),
                header("x-ms-aoai-configured-data-retention-days"),
                header("x-ms-served-model"),
                header("x-ratelimit-abusepenalty-active"),
                header("x-ratelimit-key"),
                header("x-ratelimit-limit-requests"),
                header("x-ratelimit-limit-tokens"),
                header("x-ratelimit-remaining-requests"),
                header("x-ratelimit-remaining-tokens"),
                header("x-ratelimit-renewalperiod-requests"),
                header("x-ratelimit-renewalperiod-tokens"),
                header("x-ratelimit-reset-requests"),
                header("x-ratelimit-reset-tokens"),
            )
        }
        interceptorManager.addSanitizers(sanitizers)
        proxyRulesRegistered = true
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
        PROMPT_AGENT,
    }

    private companion object {
        const val PROMPT_AGENT_NAME = "konductor-prompt-responses-test"
        const val REDACTED_CONNECTION = "REDACTED_CONNECTION"
        const val REDACTED_DEPLOYMENT = "REDACTED_DEPLOYMENT"
        const val REDACTED_MODEL = "REDACTED_MODEL"
        const val PLAYBACK_ENDPOINT = "https://localhost:8080/api/projects/REDACTED_PROJECT"
    }
}
