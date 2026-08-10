package com.konductor

import com.azure.core.credential.AccessToken
import com.azure.core.credential.TokenCredential
import com.konductor.config.BootstrapConfig
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationException
import com.konductor.config.ConfigurationSources
import com.konductor.conversation.CommandOption
import com.konductor.core.models.SessionHeader
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentKind
import com.konductor.tui.StartupModelSelectorResult
import reactor.core.publisher.Mono
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FoundryModelBootstrapTest {
    private val strings = AppStrings.english()
    private val credential = TokenCredential {
        Mono.just(AccessToken("token", OffsetDateTime.now().plusHours(1)))
    }

    @Test
    fun `persisted Prompt model is authoritative and cross-kind selection fails before bootstrap`() {
        val candidate = Configuration.parseSources(
            ConfigurationSources(
                processEnvironment = mapOf(
                    Configuration.ENV_PROJECT_ENDPOINT to "https://example.test",
                    Configuration.ENV_MODEL_NAME to "ambient",
                )::get,
            ),
        )
        val header = SessionHeader(
            Uuid.random(),
            null,
            Path.of("workspace").toAbsolutePath(),
            "persisted",
            Clock.System.now(),
        )

        val persisted = resolveTuiBootstrapConfiguration(
            candidate,
            InitialSessionSelection.Existing(header),
            strings,
        )
        assertEquals("persisted", persisted.model)

        val hostedCandidate = Configuration.parseSources(
            ConfigurationSources(
                processEnvironment = mapOf(Configuration.ENV_PROJECT_ENDPOINT to "https://example.test")::get,
                agentKindOverride = AgentKind.Hosted,
            ),
        )
        assertFailsWith<ConfigurationException> {
            resolveTuiBootstrapConfiguration(
                hostedCandidate,
                InitialSessionSelection.Existing(header),
                strings,
            )
        }
    }

    @Test
    fun `resolved Prompt and Hosted bootstrap never touch deployment discovery`() {
        listOf(
            bootstrap(model = "configured"),
            bootstrap(model = null, kind = AgentKind.Hosted),
        ).forEach { bootstrap ->
            var loads = 0
            var selections = 0

            val result = resolveFoundryModelBootstrap(
                bootstrap,
                strings,
                loadModelOptions = { loads++; error("must not load") },
                selectModel = { selections++; error("must not select") },
            )

            val ready = assertIs<FoundryModelBootstrapResult.Ready>(result)
            assertEquals(bootstrap.model, ready.configuration.model)
            assertEquals(0, loads)
            assertEquals(0, selections)
        }
    }

    @Test
    fun `zero deployments returns actionable failure without selector`() {
        var selections = 0

        val result = resolveFoundryModelBootstrap(
            bootstrap(),
            strings,
            loadModelOptions = { emptyList() },
            selectModel = { selections++; StartupModelSelectorResult.Cancelled },
        )

        assertEquals(strings.startupModelNoDeployments, assertIs<FoundryModelBootstrapResult.Failure>(result).message)
        assertEquals(0, selections)
    }

    @Test
    fun `one deployment auto-selects exact value and adds presentation notice`() {
        val result = resolveFoundryModelBootstrap(
            bootstrap(),
            strings,
            loadModelOptions = { listOf(CommandOption("deployment-a", detail = "gpt")) },
            selectModel = { error("one deployment must not open selector") },
        )

        val ready = assertIs<FoundryModelBootstrapResult.Ready>(result)
        assertEquals("deployment-a", ready.configuration.model)
        assertEquals(listOf(strings.startupModelAutoSelected("deployment-a")), ready.startupSystemMessages)
    }

    @Test
    fun `many deployments return exact selector value or clean cancellation`() {
        val options = listOf(CommandOption("deployment-a"), CommandOption("deployment-b"))
        val selected = resolveFoundryModelBootstrap(
            bootstrap(),
            strings,
            loadModelOptions = { options },
            selectModel = {
                assertEquals(options, it)
                StartupModelSelectorResult.Selected("deployment-b")
            },
        )
        assertEquals("deployment-b", assertIs<FoundryModelBootstrapResult.Ready>(selected).configuration.model)

        val cancelled = resolveFoundryModelBootstrap(
            bootstrap(),
            strings,
            loadModelOptions = { options },
            selectModel = { StartupModelSelectorResult.Cancelled },
        )
        assertIs<FoundryModelBootstrapResult.Cancelled>(cancelled)
    }

    @Test
    fun `discovery failure is sanitized and does not invoke selector`() {
        val secret = "credential=super-secret response-body"
        val result = resolveFoundryModelBootstrap(
            bootstrap(),
            strings,
            loadModelOptions = { error(secret) },
            selectModel = { error("must not select") },
        )

        val message = assertIs<FoundryModelBootstrapResult.Failure>(result).message
        assertEquals(strings.startupModelDiscoveryFailed, message)
        assertTrue(!message.contains(secret))
    }

    private fun bootstrap(
        model: String? = null,
        kind: AgentKind = AgentKind.Prompt,
    ): BootstrapConfig {
        val values = buildMap {
            put(Configuration.ENV_PROJECT_ENDPOINT, "https://example.test")
            model?.let { put(Configuration.ENV_MODEL_NAME, it) }
        }
        return Configuration.resolveBootstrapCandidate(
            Configuration.parseSources(
                ConfigurationSources(
                    processEnvironment = values::get,
                    agentKindOverride = kind,
                ),
            ),
            credentialFactory = { credential },
        )
    }
}
