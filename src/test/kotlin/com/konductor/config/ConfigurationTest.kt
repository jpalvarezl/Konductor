package com.konductor.config

import com.konductor.compaction.CompactionSettings
import com.konductor.provider.AgentKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationTest {

    private val endpoint = "https://r.ai.azure.com/api/projects/p"

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    /** Write `<dir>/.konductor/settings.json`. */
    private fun writeSettings(dir: Path, json: String) {
        val configDir = dir.resolve(".konductor").also { it.createDirectories() }
        configDir.resolve("settings.json").writeText(json)
    }

    @Test
    fun `loads endpoint and model from environment with defaults`(@TempDir cwd: Path, @TempDir home: Path) {
        val cfg = Configuration.load(
            env = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_MODEL_NAME to "gpt-5-mini",
            ),
            cwd = cwd,
            homeDir = home,
        )

        assertEquals(endpoint, cfg.projectEndpoint)
        assertEquals("gpt-5-mini", cfg.model)
        assertEquals(AgentKind.Prompt, cfg.agentKind)
        assertNull(cfg.temperature)
        assertNull(cfg.toolAllow)
    }

    @Test
    fun `missing endpoint throws`(@TempDir cwd: Path, @TempDir home: Path) {
        assertFailsWith<ConfigurationException> {
            Configuration.load(env = env(Configuration.ENV_MODEL_NAME to "m"), cwd = cwd, homeDir = home)
        }
    }

    @Test
    fun `missing model throws when absent from env and settings`(@TempDir cwd: Path, @TempDir home: Path) {
        assertFailsWith<ConfigurationException> {
            Configuration.load(
                env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
                cwd = cwd,
                homeDir = home,
            )
        }
    }

    @Test
    fun `hosted kind does not require a model and reads hosted env vars`(@TempDir cwd: Path, @TempDir home: Path) {
        val cfg = Configuration.load(
            env = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_HOSTED_AGENT_NAME to "agent-a",
                Configuration.ENV_AGENT_CONTAINER_IMAGE to "repo/image:tag",
            ),
            cwd = cwd,
            homeDir = home,
            agentKindOverride = AgentKind.Hosted,
        )

        assertEquals(AgentKind.Hosted, cfg.agentKind)
        assertEquals("hosted", cfg.model)
        assertEquals("agent-a", cfg.hostedAgentName)
        assertEquals("repo/image:tag", cfg.hostedAgentContainerImage)
    }

    @Test
    fun `maxToolIterations defaults to 30 and is read from settings`(@TempDir cwd: Path, @TempDir home: Path) {
        val defaults = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint, Configuration.ENV_MODEL_NAME to "m"),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals(Configuration.DEFAULT_MAX_TOOL_ITERATIONS, defaults.maxToolIterations)

        writeSettings(cwd, """{ "provider": { "maxToolIterations": 7 } }""")
        val overridden = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint, Configuration.ENV_MODEL_NAME to "m"),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals(7, overridden.maxToolIterations)
    }

    @Test
    fun `blank environment values are treated as absent`(@TempDir cwd: Path, @TempDir home: Path) {
        assertFailsWith<ConfigurationException> {
            Configuration.load(
                env = env(
                    Configuration.ENV_PROJECT_ENDPOINT to "   ",
                    Configuration.ENV_MODEL_NAME to "m",
                ),
                cwd = cwd,
                homeDir = home,
            )
        }
    }

    @Test
    fun `project settings override global, and env overrides both for model`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(home, """{ "provider": { "model": "global-model", "temperature": 0.1 } }""")
        writeSettings(cwd, """{ "provider": { "model": "project-model" } }""")

        // No model env: project wins for model; temperature is inherited from global.
        val fromProject = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals("project-model", fromProject.model)
        assertEquals(0.1, fromProject.temperature)

        // Model env present: it overrides both settings files.
        val fromEnv = Configuration.load(
            env = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_MODEL_NAME to "env-model",
            ),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals("env-model", fromEnv.model)
    }

    @Test
    fun `agentKind is parsed case-insensitively from settings`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(cwd, """{ "provider": { "model": "m", "agentKind": "hosted" } }""")

        val cfg = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals(AgentKind.Hosted, cfg.agentKind)
    }

    @Test
    fun `agent name and hosted image are read from settings and env wins`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(
            cwd,
            """
            {
              "provider": {
                "agentKind": "hosted",
                "hostedAgentName": "settings-agent",
                "hostedAgentContainerImage": "settings/image:tag"
              }
            }
            """.trimIndent(),
        )

        val cfg = Configuration.load(
            env = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_HOSTED_AGENT_NAME to "env-agent",
                Configuration.ENV_AGENT_CONTAINER_IMAGE to "env/image:tag",
            ),
            cwd = cwd,
            homeDir = home,
        )

        assertEquals("env-agent", cfg.hostedAgentName)
        assertEquals("env/image:tag", cfg.hostedAgentContainerImage)
    }

    @Test
    fun `blank agent names and image in settings are treated as absent`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(
            cwd,
            """
            {
              "provider": {
                "model": "m",
                "promptAgentName": "",
                "hostedAgentName": "   ",
                "hostedAgentContainerImage": ""
              }
            }
            """.trimIndent(),
        )

        val cfg = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
            cwd = cwd,
            homeDir = home,
        )

        assertNull(cfg.promptAgentName)
        assertNull(cfg.hostedAgentName)
        assertNull(cfg.hostedAgentContainerImage)
    }

    @Test
    fun `invalid agentKind throws`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(cwd, """{ "provider": { "model": "m", "agentKind": "bogus" } }""")

        assertFailsWith<ConfigurationException> {
            Configuration.load(
                env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
                cwd = cwd,
                homeDir = home,
            )
        }
    }

    @Test
    fun `unknown settings keys are ignored and tool allow-list is read`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(
            cwd,
            """
            {
              "provider": { "model": "m" },
              "tools": { "allow": ["read", "ls"], "maxOutputBytes": 16384 },
              "compaction": { "enabled": true, "reserveTokens": 16384 },
              "systemPromptAppend": "extra instructions"
            }
            """.trimIndent(),
        )

        val cfg = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals(setOf("read", "ls"), cfg.toolAllow)
        assertEquals("extra instructions", cfg.systemPromptAppend)
    }

    @Test
    fun `compaction settings default and are read from settings`(@TempDir cwd: Path, @TempDir home: Path) {
        val defaults = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint, Configuration.ENV_MODEL_NAME to "m"),
            cwd = cwd,
            homeDir = home,
        )
        assertTrue(defaults.compaction.enabled)
        assertEquals(CompactionSettings.DEFAULT_RESERVE_TOKENS, defaults.compaction.reserveTokens)
        assertEquals(CompactionSettings.DEFAULT_KEEP_RECENT_TOKENS, defaults.compaction.keepRecentTokens)
        assertEquals(CompactionSettings.DEFAULT_CONTEXT_WINDOW, defaults.compaction.contextWindow)

        writeSettings(
            cwd,
            """
            {
              "compaction": { "enabled": false, "reserveTokens": 999, "keepRecentTokens": 1234, "contextWindow": 55000 }
            }
            """.trimIndent(),
        )
        val overridden = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint, Configuration.ENV_MODEL_NAME to "m"),
            cwd = cwd,
            homeDir = home,
        )
        assertFalse(overridden.compaction.enabled)
        assertEquals(999, overridden.compaction.reserveTokens)
        assertEquals(1234, overridden.compaction.keepRecentTokens)
        assertEquals(55_000, overridden.compaction.contextWindow)
    }

    @Test
    fun `compaction reserve tokens are clamped below context window`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(
            cwd,
            """
            {
              "compaction": { "reserveTokens": 100, "contextWindow": 100 }
            }
            """.trimIndent(),
        )

        val cfg = Configuration.load(
            env = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint, Configuration.ENV_MODEL_NAME to "m"),
            cwd = cwd,
            homeDir = home,
        )

        assertTrue(cfg.compaction.reserveTokens < cfg.compaction.contextWindow)
    }

    @Test
    fun `malformed settings file throws`(@TempDir cwd: Path, @TempDir home: Path) {
        writeSettings(cwd, "{ this is not json }")

        assertFailsWith<ConfigurationException> {
            Configuration.load(
                env = env(
                    Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                    Configuration.ENV_MODEL_NAME to "m",
                ),
                cwd = cwd,
                homeDir = home,
            )
        }
    }

    @Test
    fun `source parsing is separate from defaults validation and credential construction`(@TempDir cwd: Path) {
        var credentialCalls = 0
        val candidate = Configuration.parseSources(
            ConfigurationSources(
                processEnvironment = env(Configuration.ENV_PROJECT_ENDPOINT to endpoint),
                globalSettings = ConfigurationDocument(cwd.resolve("settings.json"), "{}"),
            ),
        )

        assertEquals(0, credentialCalls)
        assertFailsWith<ConfigurationException> {
            Configuration.resolveCandidate(candidate) {
                credentialCalls += 1
                error("credential must not be created before validation")
            }
        }
        assertEquals(0, credentialCalls)
    }

    @Test
    fun `only supplied eligible settings documents are parsed`(@TempDir cwd: Path) {
        val process = env(
            Configuration.ENV_PROJECT_ENDPOINT to endpoint,
            Configuration.ENV_MODEL_NAME to "process-model",
        )
        val candidate = Configuration.parseSources(ConfigurationSources(processEnvironment = process))
        assertEquals("process-model", Configuration.resolveCandidate(candidate).model)

        assertFailsWith<ConfigurationException> {
            Configuration.parseSources(
                ConfigurationSources(
                    processEnvironment = process,
                    trustedProjectSettings = ConfigurationDocument(cwd.resolve("settings.json"), "{ malformed"),
                ),
            )
        }
    }

    @Test
    fun `eligible source loader excludes untrusted dotenv and project settings`(
        @TempDir cwd: Path,
        @TempDir home: Path,
    ) {
        writeSettings(cwd, "{ malformed project json")

        val cfg = Configuration.loadEligibleSources(
            processEnv = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_MODEL_NAME to "process-model",
            ),
            trustedProjectEnv = env(Configuration.ENV_MODEL_NAME to "project-model"),
            projectSourcesEligible = false,
            cwd = cwd,
            homeDir = home,
        )

        assertEquals("process-model", cfg.model)
    }

    @Test
    fun `eligible source loader rejects an empty application config path`(@TempDir cwd: Path, @TempDir home: Path) {
        assertFailsWith<ConfigurationException> {
            Configuration.loadEligibleSources(
                processEnv = env(
                    Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                    Configuration.ENV_MODEL_NAME to "model",
                ),
                projectSourcesEligible = false,
                configDirOverride = Path.of(""),
                cwd = cwd,
                homeDir = home,
            )
        }
    }

    @Test
    fun `eligible source loader uses only process config dir and explicit override wins`(
        @TempDir cwd: Path,
        @TempDir home: Path,
    ) {
        val processConfig = home.resolve("process-config").createDirectories()
        processConfig.resolve("settings.json").writeText("""{ "provider": { "model": "process-global" } }""")
        val explicitConfig = home.resolve("explicit-config").createDirectories()
        explicitConfig.resolve("settings.json").writeText("""{ "provider": { "model": "explicit-global" } }""")

        val cfg = Configuration.loadEligibleSources(
            processEnv = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_CONFIG_DIR to processConfig.toString(),
            ),
            trustedProjectEnv = env(Configuration.ENV_CONFIG_DIR to cwd.toString()),
            projectSourcesEligible = true,
            configDirOverride = Path.of("explicit-config"),
            cwd = cwd,
            homeDir = home,
        )

        assertEquals("explicit-global", cfg.model)
    }

    @Test
    fun `KONDUCTOR_CONFIG_DIR overrides the global settings location`(
        @TempDir cwd: Path,
        @TempDir home: Path,
        @TempDir customConfigDir: Path,
    ) {
        // With KONDUCTOR_CONFIG_DIR set, global settings live directly under it (not under a nested .konductor).
        customConfigDir.resolve("settings.json").writeText("""{ "provider": { "model": "custom-model" } }""")

        val cfg = Configuration.load(
            env = env(
                Configuration.ENV_PROJECT_ENDPOINT to endpoint,
                Configuration.ENV_CONFIG_DIR to customConfigDir.toString(),
            ),
            cwd = cwd,
            homeDir = home,
        )
        assertEquals("custom-model", cfg.model)
    }
}
