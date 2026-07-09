package com.konductor.config

import com.azure.core.credential.TokenCredential
import com.azure.identity.DefaultAzureCredentialBuilder
import com.konductor.compaction.CompactionSettings
import com.konductor.provider.AgentKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Effective, resolved Konductor configuration for a run.
 *
 * Build it with [Configuration.load], which merges environment variables and `settings.json` files
 * using the precedence documented in `docs/spec/configuration.md`:
 *
 * ```
 * environment variables  >  project settings.json  >  global settings.json  >  built-in defaults
 * ```
 *
 * (CLI flags sit above environment variables in the spec, but there is no CLI layer yet.)
 * Compaction settings are intentionally omitted until the compaction feature lands (M4).
 */
data class Configuration(
    val projectEndpoint: String,
    val tokenCredential: TokenCredential,
    val model: String,
    val agentKind: AgentKind = AgentKind.Prompt,
    /** Persisted **PromptAgent** name (M2.5, opt-in) — reserved; the ephemeral Prompt path ignores it today. */
    val promptAgentName: String? = null,
    /** Named hosted agent to deploy/select (required by the Hosted provider). */
    val hostedAgentName: String? = null,
    val hostedAgentContainerImage: String? = null,
    val temperature: Double? = null,
    val toolAllow: Set<String>? = null,
    /** Max tool-call rounds per turn before the loop stops itself (guards against a non-converging model). */
    val maxToolIterations: Int = DEFAULT_MAX_TOOL_ITERATIONS,
    /** Client-side compaction tunables for the Prompt provider (M4, docs/spec/compaction.md). */
    val compaction: CompactionSettings = CompactionSettings(),
    val systemPromptOverride: String? = null,
    val systemPromptAppend: String? = null,
) {
    companion object {
        const val ENV_PROJECT_ENDPOINT: String = "FOUNDRY_PROJECT_ENDPOINT"
        const val ENV_MODEL_NAME: String = "FOUNDRY_MODEL_NAME"
        const val ENV_AGENT_CONTAINER_IMAGE: String = "FOUNDRY_AGENT_CONTAINER_IMAGE"
        // Two distinct agent-name knobs: the persisted PromptAgent (M2.5) and the hosted agent (M5) are
        // different features on different projects, so they must not share one env var.
        const val ENV_PROMPT_AGENT_NAME: String = "KONDUCTOR_PROMPT_AGENT_NAME"
        const val ENV_HOSTED_AGENT_NAME: String = "KONDUCTOR_HOSTED_AGENT_NAME"
        const val ENV_CONFIG_DIR: String = "KONDUCTOR_CONFIG_DIR"

        /** Default cap on tool-call rounds per turn; override with `provider.maxToolIterations` in settings. */
        const val DEFAULT_MAX_TOOL_ITERATIONS: Int = 30

        private const val SETTINGS_FILE_NAME: String = "settings.json"
        private const val PROJECT_CONFIG_DIR_NAME: String = ".konductor"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Resolve the effective [Configuration] from the environment and `settings.json` files.
         *
         * Global settings are read from `$KONDUCTOR_CONFIG_DIR/settings.json` (default
         * `~/.konductor/settings.json`); project settings from `<cwd>/.konductor/settings.json`.
         * Project values override global ones, and environment variables override both.
         *
         * [env], [cwd], and [homeDir] are injectable so the loader can be unit-tested without touching
         * the real environment or home directory.
         *
         * @throws ConfigurationException if a required value is missing or a settings file is malformed.
         */
        fun load(
            env: (String) -> String? = System::getenv,
            cwd: Path = Path.of("").toAbsolutePath(),
            homeDir: Path = Path.of(System.getProperty("user.home")),
            agentKindOverride: AgentKind? = null,
            modelOverride: String? = null,
        ): Configuration {
            // Treat blank/whitespace-only environment values as absent.
            val readEnv: (String) -> String? = { name -> env(name)?.trim()?.ifBlank { null } }

            val configDir = readEnv(ENV_CONFIG_DIR)?.let(Path::of) ?: homeDir.resolve(PROJECT_CONFIG_DIR_NAME)
            val global = readSettings(configDir.resolve(SETTINGS_FILE_NAME))
            val project = readSettings(cwd.resolve(PROJECT_CONFIG_DIR_NAME).resolve(SETTINGS_FILE_NAME))

            // Per-field precedence for settings: project wins over global. Environment overrides are
            // applied explicitly on the fields that have an env var (endpoint, model).
            fun <T> pick(select: (SettingsFile) -> T?): T? = project?.let(select) ?: global?.let(select)

            // Blank/whitespace settings strings are treated as absent, matching readEnv for env values —
            // e.g. a `promptAgentName: ""` must fall back to ephemeral, not bind an empty agent name.
            fun pickName(select: (SettingsFile) -> String?): String? = pick(select)?.trim()?.ifBlank { null }

            val projectEndpoint = readEnv(ENV_PROJECT_ENDPOINT)
                ?: throw ConfigurationException(
                    "Missing required $ENV_PROJECT_ENDPOINT " +
                        "(Foundry project endpoint, e.g. https://<resource>.ai.azure.com/api/projects/<project>).",
                )

            val agentKind = agentKindOverride
                ?: pick { it.provider?.agentKind }?.let(::parseAgentKind)
                ?: AgentKind.Prompt

            val model = modelOverride?.trim()?.ifBlank { null }
                ?: readEnv(ENV_MODEL_NAME)
                ?: pickName { it.provider?.model }
                ?: if (agentKind == AgentKind.Hosted) "hosted" else null
                ?: throw ConfigurationException(
                    "Missing required model: set $ENV_MODEL_NAME or provider.model in $SETTINGS_FILE_NAME.",
                )

            val promptAgentName = readEnv(ENV_PROMPT_AGENT_NAME) ?: pickName { it.provider?.promptAgentName }
            val hostedAgentName = readEnv(ENV_HOSTED_AGENT_NAME) ?: pickName { it.provider?.hostedAgentName }
            val hostedAgentContainerImage = readEnv(ENV_AGENT_CONTAINER_IMAGE)
                ?: pickName { it.provider?.hostedAgentContainerImage }
            val maxToolIterations = (pick { it.provider?.maxToolIterations } ?: DEFAULT_MAX_TOOL_ITERATIONS)
                .coerceAtLeast(1)

            val compaction = CompactionSettings().let { defaults ->
                val contextWindow = (pick { it.compaction?.contextWindow } ?: defaults.contextWindow)
                    .coerceAtLeast(1)
                val reserveTokens = (pick { it.compaction?.reserveTokens } ?: defaults.reserveTokens)
                    .coerceIn(0, contextWindow - 1)
                CompactionSettings(
                    enabled = pick { it.compaction?.enabled } ?: defaults.enabled,
                    reserveTokens = reserveTokens,
                    keepRecentTokens = (pick { it.compaction?.keepRecentTokens } ?: defaults.keepRecentTokens)
                        .coerceAtLeast(1),
                    contextWindow = contextWindow,
                )
            }

            return Configuration(
                projectEndpoint = projectEndpoint,
                tokenCredential = DefaultAzureCredentialBuilder().build(),
                model = model,
                agentKind = agentKind,
                promptAgentName = promptAgentName,
                hostedAgentName = hostedAgentName,
                hostedAgentContainerImage = hostedAgentContainerImage,
                temperature = pick { it.provider?.temperature },
                toolAllow = pick { it.tools?.allow },
                maxToolIterations = maxToolIterations,
                compaction = compaction,
                systemPromptOverride = pick { it.systemPromptOverride },
                systemPromptAppend = pick { it.systemPromptAppend },
            )
        }

        private fun readSettings(path: Path): SettingsFile? {
            if (!path.exists()) return null
            val text = path.readText()
            if (text.isBlank()) return null
            return try {
                json.decodeFromString<SettingsFile>(text)
            } catch (e: SerializationException) {
                throw ConfigurationException("Invalid settings file at $path: ${e.message}", e)
            }
        }

        private fun parseAgentKind(raw: String): AgentKind =
            AgentKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ConfigurationException(
                    "Unknown agentKind '$raw'; expected one of " +
                        AgentKind.entries.joinToString { it.name.lowercase() } + ".",
                )
    }
}

/** Thrown when configuration cannot be resolved: a required value is missing or a settings file is malformed. */
class ConfigurationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * On-disk `settings.json` schema (subset). Unknown keys are ignored, so `tools.maxOutputBytes` and other
 * not-yet-modeled fields are tolerated. All fields are optional.
 */
@Serializable
private data class SettingsFile(
    val provider: ProviderSettings? = null,
    val tools: ToolSettings? = null,
    val compaction: CompactionSettingsFile? = null,
    val systemPromptOverride: String? = null,
    val systemPromptAppend: String? = null,
)

@Serializable
private data class ProviderSettings(
    val agentKind: String? = null,
    val model: String? = null,
    val promptAgentName: String? = null,
    val hostedAgentName: String? = null,
    val hostedAgentContainerImage: String? = null,
    val temperature: Double? = null,
    val maxToolIterations: Int? = null,
)

@Serializable
private data class ToolSettings(
    val allow: Set<String>? = null,
)

/** `compaction` block of `settings.json`; maps onto [CompactionSettings] with per-field defaults applied. */
@Serializable
private data class CompactionSettingsFile(
    val enabled: Boolean? = null,
    val reserveTokens: Int? = null,
    val keepRecentTokens: Int? = null,
    val contextWindow: Int? = null,
)
