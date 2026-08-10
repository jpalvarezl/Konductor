package com.konductor.config

import com.konductor.provider.AgentKind
import com.konductor.workspace.BoundedWorkspaceFileReader
import com.konductor.workspace.ResolvedConfigDirectory
import com.konductor.workspace.ResolvedWorkspace
import com.konductor.workspace.WorkspaceResolver
import com.konductor.workspace.readAttributesIfPresent
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/** Eligible configuration inputs read only after config containment and workspace trust have been resolved. */
data class LoadedConfigurationSources(
    val candidate: ConfigurationCandidate,
    val environment: EnvFile.Sources,
)

/**
 * Performs the bounded, strict-decoded reads shared by TUI and ACP configuration assembly. Project files are not even
 * probed when [projectSourcesTrusted] is false; the trust coordinator owns the separate presence-only prompt probe.
 */
class WorkspaceConfigurationLoader(
    private val workspaceResolver: WorkspaceResolver = WorkspaceResolver(),
    private val fileReader: BoundedWorkspaceFileReader = BoundedWorkspaceFileReader(),
) {
    fun load(
        workspace: ResolvedWorkspace,
        configDirectory: ResolvedConfigDirectory,
        processEnvironment: (String) -> String?,
        projectSourcesTrusted: Boolean,
        agentKindOverride: AgentKind? = null,
        modelOverride: String? = null,
    ): LoadedConfigurationSources {
        val canonicalConfig = workspaceResolver.requireConfigOutsideWorkspace(configDirectory, workspace)
        val globalSettings = readOptional(canonicalConfig.resolve(SETTINGS_FILE), canonicalConfig)
        val projectDotenv = if (projectSourcesTrusted) {
            readOptional(workspace.canonicalCwd.resolve(DOTENV_FILE), workspace.canonicalRoot)
        } else {
            null
        }
        val projectSettings = if (projectSourcesTrusted) {
            readOptional(
                workspace.canonicalCwd.resolve(PROJECT_CONFIG_DIRECTORY).resolve(SETTINGS_FILE),
                workspace.canonicalRoot,
            )
        } else {
            null
        }
        val environment = projectDotenv?.let {
            EnvFile.trustedProjectSources(normalizeNewlines(it.content), processEnvironment)
        } ?: EnvFile.processSources(processEnvironment)
        val candidate = Configuration.parseSources(
            ConfigurationSources(
                processEnvironment = environment.processLookup(),
                trustedProjectEnvironment = environment.projectLookup(),
                globalSettings = globalSettings,
                trustedProjectSettings = projectSettings,
                agentKindOverride = agentKindOverride,
                modelOverride = modelOverride,
            ),
        )
        return LoadedConfigurationSources(candidate, environment)
    }

    private fun readOptional(selectedEntry: Path, allowedRoot: Path): ConfigurationDocument? {
        if (readAttributesIfPresent(selectedEntry) == null) return null
        val file = fileReader.read(selectedEntry, allowedRoot, MAX_PROJECT_FILE_BYTES)
        return ConfigurationDocument(file.canonicalPath, decode(file.bytes, file.canonicalPath).removePrefix(UTF8_BOM))
    }

    private fun decode(bytes: ByteArray, path: Path): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw ConfigurationException("Configuration file is not valid UTF-8 at $path.", error)
    }

    companion object {
        const val MAX_PROJECT_FILE_BYTES: Int = 256 * 1024
        private const val SETTINGS_FILE = "settings.json"
        private const val PROJECT_CONFIG_DIRECTORY = ".konductor"
        private const val DOTENV_FILE = ".env"
        private const val UTF8_BOM = "\uFEFF"

        private fun normalizeNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')
    }
}
