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

/** Decoded settings content plus its source path; callers own eligibility and bounded-read policy. */
data class ConfigurationDocument(
    val path: Path,
    val content: String,
)

/**
 * Eligible, still-distinct configuration sources. Supplying a project source is the caller's assertion that workspace
 * trust has already made it eligible. Project values never participate in config-directory bootstrap.
 */
class ConfigurationSources(
    val processEnvironment: (String) -> String? = System::getenv,
    val trustedProjectEnvironment: (String) -> String? = { null },
    val globalSettings: ConfigurationDocument? = null,
    val trustedProjectSettings: ConfigurationDocument? = null,
    val agentKindOverride: AgentKind? = null,
    val modelOverride: String? = null,
)

/**
 * Parsed eligible sources before defaults, required-field checks, credential construction, or persisted-session
 * overlays. This is intentionally a separate phase so ACP load can overlay authoritative header identity later.
 */
class ConfigurationCandidate internal constructor(
    internal val processEnvironment: (String) -> String?,
    internal val trustedProjectEnvironment: (String) -> String?,
    internal val globalSettings: SettingsFile?,
    internal val trustedProjectSettings: SettingsFile?,
    internal val agentKindOverride: AgentKind?,
    internal val modelOverride: String?,
)

/** Persisted session identity that supersedes every fresh model/kind/agent source during load. */
data class PersistedConfigurationIdentity(
    val model: String,
    val agentKind: AgentKind,
    val promptAgentName: String? = null,
    val hostedAgentName: String? = null,
)

/** Effective, validated Konductor configuration for a run. */
data class Configuration(
    val projectEndpoint: String,
    val tokenCredential: TokenCredential,
    val model: String,
    val agentKind: AgentKind = AgentKind.Prompt,
    /** Persisted **PromptAgent** name (M2.5, opt-in); null keeps the ephemeral Prompt path. */
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
        const val ENV_PROMPT_AGENT_NAME: String = "KONDUCTOR_PROMPT_AGENT_NAME"
        const val ENV_HOSTED_AGENT_NAME: String = "KONDUCTOR_HOSTED_AGENT_NAME"
        const val ENV_CONFIG_DIR: String = "KONDUCTOR_CONFIG_DIR"

        /** Default cap on tool-call rounds per turn; override with `provider.maxToolIterations` in settings. */
        const val DEFAULT_MAX_TOOL_ITERATIONS: Int = 30

        private const val SETTINGS_FILE_NAME: String = "settings.json"
        private const val PROJECT_CONFIG_DIR_NAME: String = ".konductor"
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse only the documents that the caller has declared eligible. No defaults or credentials are created. */
        fun parseSources(sources: ConfigurationSources): ConfigurationCandidate = ConfigurationCandidate(
            processEnvironment = sources.processEnvironment,
            trustedProjectEnvironment = sources.trustedProjectEnvironment,
            globalSettings = parseSettings(sources.globalSettings),
            trustedProjectSettings = parseSettings(sources.trustedProjectSettings),
            agentKindOverride = sources.agentKindOverride,
            modelOverride = sources.modelOverride,
        )

        /**
         * Apply precedence, defaults, required-field validation, and credential construction to a parsed candidate.
         * A later phase may add an authoritative persisted-session overlay before calling this method.
         */
        fun resolveCandidate(
            candidate: ConfigurationCandidate,
            persistedIdentity: PersistedConfigurationIdentity? = null,
            credentialFactory: () -> TokenCredential = { DefaultAzureCredentialBuilder().build() },
        ): Configuration {
            val processValue: (String) -> String? = { name ->
                candidate.processEnvironment(name)?.trim()?.ifBlank { null }
            }
            val projectValue: (String) -> String? = { name ->
                candidate.trustedProjectEnvironment(name)?.trim()?.ifBlank { null }
            }
            val readEnv: (String) -> String? = { name -> processValue(name) ?: projectValue(name) }
            val global = candidate.globalSettings
            val project = candidate.trustedProjectSettings

            fun <T> pick(select: (SettingsFile) -> T?): T? = project?.let(select) ?: global?.let(select)
            fun pickName(select: (SettingsFile) -> String?): String? = pick(select)?.trim()?.ifBlank { null }

            val projectEndpoint = readEnv(ENV_PROJECT_ENDPOINT)
                ?: throw ConfigurationException(
                    "Missing required $ENV_PROJECT_ENDPOINT " +
                        "(Foundry project endpoint, e.g. https://<resource>.ai.azure.com/api/projects/<project>).",
                )
            val agentKind = persistedIdentity?.agentKind ?: resolveAgentKind(candidate)
            val modelCandidate = when {
                persistedIdentity != null -> persistedIdentity.model.takeIf { it.isNotBlank() }
                agentKind == AgentKind.Hosted -> "hosted"
                else -> candidate.modelOverride?.trim()?.ifBlank { null }
                    ?: readEnv(ENV_MODEL_NAME)
                    ?: pickName { it.provider?.model }
            }
            val model = modelCandidate ?: throw ConfigurationException(
                if (persistedIdentity != null) {
                    "Persisted session model is missing or blank."
                } else {
                    "Missing required model: set $ENV_MODEL_NAME or provider.model in $SETTINGS_FILE_NAME."
                },
            )
            val maxToolIterations = (pick { it.provider?.maxToolIterations } ?: DEFAULT_MAX_TOOL_ITERATIONS)
                .coerceAtLeast(1)
            val compaction = CompactionSettings().let { defaults ->
                val contextWindow = (pick { it.compaction?.contextWindow } ?: defaults.contextWindow).coerceAtLeast(1)
                CompactionSettings(
                    enabled = pick { it.compaction?.enabled } ?: defaults.enabled,
                    reserveTokens = (pick { it.compaction?.reserveTokens } ?: defaults.reserveTokens)
                        .coerceIn(0, contextWindow - 1),
                    keepRecentTokens = (pick { it.compaction?.keepRecentTokens } ?: defaults.keepRecentTokens)
                        .coerceAtLeast(1),
                    contextWindow = contextWindow,
                )
            }

            val promptAgentName = if (persistedIdentity != null) {
                persistedIdentity.promptAgentName.takeIf { persistedIdentity.agentKind == AgentKind.Prompt }
            } else {
                readEnv(ENV_PROMPT_AGENT_NAME) ?: pickName { it.provider?.promptAgentName }
            }
            val hostedAgentName = if (persistedIdentity != null) {
                persistedIdentity.hostedAgentName.takeIf { persistedIdentity.agentKind == AgentKind.Hosted }
            } else {
                readEnv(ENV_HOSTED_AGENT_NAME) ?: pickName { it.provider?.hostedAgentName }
            }
            val hostedAgentContainerImage = readEnv(ENV_AGENT_CONTAINER_IMAGE)
                ?: pickName { it.provider?.hostedAgentContainerImage }
            val temperature = pick { it.provider?.temperature }
            val toolAllow = pick { it.tools?.allow }
            val systemPromptOverride = pick { it.systemPromptOverride }
            val systemPromptAppend = pick { it.systemPromptAppend }
            val credential = credentialFactory()

            return Configuration(
                projectEndpoint = projectEndpoint,
                tokenCredential = credential,
                model = model,
                agentKind = agentKind,
                promptAgentName = promptAgentName,
                hostedAgentName = hostedAgentName,
                hostedAgentContainerImage = hostedAgentContainerImage,
                temperature = temperature,
                toolAllow = toolAllow,
                maxToolIterations = maxToolIterations,
                compaction = compaction,
                systemPromptOverride = systemPromptOverride,
                systemPromptAppend = systemPromptAppend,
            )
        }

        /**
         * Compatibility wrapper preserving the current eager production composition until Phase 3. The supplied [env]
         * may still be the legacy dotenv overlay; new startup code must use [parseSources] with distinct sources.
         */
        fun load(
            env: (String) -> String? = System::getenv,
            cwd: Path = Path.of("").toAbsolutePath(),
            homeDir: Path = Path.of(System.getProperty("user.home")),
            agentKindOverride: AgentKind? = null,
            modelOverride: String? = null,
        ): Configuration {
            val configDir = env(ENV_CONFIG_DIR)?.trim()?.ifBlank { null }?.let(Path::of)
                ?: homeDir.resolve(PROJECT_CONFIG_DIR_NAME)
            return resolveCandidate(
                parseSources(
                    ConfigurationSources(
                        processEnvironment = env,
                        globalSettings = readSettingsDocument(configDir.resolve(SETTINGS_FILE_NAME)),
                        trustedProjectSettings = readSettingsDocument(
                            cwd.resolve(PROJECT_CONFIG_DIR_NAME).resolve(SETTINGS_FILE_NAME),
                        ),
                        agentKindOverride = agentKindOverride,
                        modelOverride = modelOverride,
                    ),
                ),
            )
        }

        /**
         * Additive compatibility wrapper for source eligibility. Config-directory selection uses only explicit input,
         * then real process environment, then home; project environment can never redirect it. Phase 3 will pass
         * bounded [ConfigurationDocument] values directly to [parseSources].
         */
        fun loadEligibleSources(
            processEnv: (String) -> String? = System::getenv,
            trustedProjectEnv: (String) -> String? = { null },
            projectSourcesEligible: Boolean,
            cwd: Path = Path.of("").toAbsolutePath(),
            homeDir: Path = Path.of(System.getProperty("user.home")),
            configDirOverride: Path? = null,
            agentKindOverride: AgentKind? = null,
            modelOverride: String? = null,
        ): Configuration {
            if (configDirOverride != null && configDirOverride.toString().isBlank()) {
                throw ConfigurationException("Config-directory override must contain a path.")
            }
            val canonicalHomeBase = homeDir.toAbsolutePath().normalize()
            val processConfig = processEnv(ENV_CONFIG_DIR)?.trim()?.ifBlank { null }?.let(Path::of)
            val selected = configDirOverride ?: processConfig
            val configDir = selected?.let { if (it.isAbsolute) it else canonicalHomeBase.resolve(it) }
                ?: canonicalHomeBase.resolve(PROJECT_CONFIG_DIR_NAME)
            val sources = ConfigurationSources(
                processEnvironment = processEnv,
                trustedProjectEnvironment = if (projectSourcesEligible) trustedProjectEnv else ({ null }),
                globalSettings = readSettingsDocument(configDir.resolve(SETTINGS_FILE_NAME)),
                trustedProjectSettings = if (projectSourcesEligible) {
                    readSettingsDocument(cwd.resolve(PROJECT_CONFIG_DIR_NAME).resolve(SETTINGS_FILE_NAME))
                } else {
                    null
                },
                agentKindOverride = agentKindOverride,
                modelOverride = modelOverride,
            )
            return resolveCandidate(parseSources(sources))
        }

        /** Resolve only fresh provider kind, without requiring a model or constructing credentials. */
        fun resolveAgentKind(candidate: ConfigurationCandidate): AgentKind {
            val project = candidate.trustedProjectSettings
            val global = candidate.globalSettings
            val raw = project?.provider?.agentKind ?: global?.provider?.agentKind
            return candidate.agentKindOverride ?: raw?.let(::parseAgentKind) ?: AgentKind.Prompt
        }

        private fun readSettingsDocument(path: Path): ConfigurationDocument? {
            if (!path.exists()) return null
            return ConfigurationDocument(path, path.readText())
        }

        private fun parseSettings(document: ConfigurationDocument?): SettingsFile? {
            document ?: return null
            if (document.content.isBlank()) return null
            return try {
                json.decodeFromString<SettingsFile>(document.content)
            } catch (e: SerializationException) {
                throw ConfigurationException("Invalid settings file at ${document.path}: ${e.message}", e)
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

/** On-disk `settings.json` schema. Unknown fields remain tolerated for compatibility. */
@Serializable
internal data class SettingsFile(
    val provider: ProviderSettings? = null,
    val tools: ToolSettings? = null,
    val compaction: CompactionSettingsFile? = null,
    val systemPromptOverride: String? = null,
    val systemPromptAppend: String? = null,
)

@Serializable
internal data class ProviderSettings(
    val agentKind: String? = null,
    val model: String? = null,
    val promptAgentName: String? = null,
    val hostedAgentName: String? = null,
    val hostedAgentContainerImage: String? = null,
    val temperature: Double? = null,
    val maxToolIterations: Int? = null,
)

@Serializable
internal data class ToolSettings(
    val allow: Set<String>? = null,
)

@Serializable
internal data class CompactionSettingsFile(
    val enabled: Boolean? = null,
    val reserveTokens: Int? = null,
    val keepRecentTokens: Int? = null,
    val contextWindow: Int? = null,
)
