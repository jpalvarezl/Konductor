package com.konductor.acp

import com.konductor.agent.AgentContextFactory
import com.konductor.agent.ContextFileLoader
import com.konductor.compaction.CompactionSettings
import com.konductor.config.Configuration
import com.konductor.config.ConfigurationException
import com.konductor.config.PersistedConfigurationIdentity
import com.konductor.config.WorkspaceConfigurationLoader
import com.konductor.config.WorkspaceTrustCoordinator
import com.konductor.config.WorkspaceTrustDecision
import com.konductor.config.WorkspaceTrustOutcome
import com.konductor.config.WorkspaceTrustOverride
import com.konductor.config.WorkspaceTrustStore
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.foundry.project.FoundryProjectRuntime
import com.konductor.i18n.AppStrings
import com.konductor.provider.AgentKind
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.tool.createToolRuntime
import com.konductor.workspace.ResolvedConfigDirectory
import com.konductor.workspace.ResolvedWorkspace
import com.konductor.workspace.WorkspaceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Runtime state owned by one logical ACP session. */
internal data class AcpSessionRuntime(
    val providerRuntime: ProviderRuntime,
    val context: AgentContext,
    val toolExecutor: ToolExecutor,
) {
    val provider: AgentProvider get() = providerRuntime.provider
}

/** Complete provisional session/runtime graph, still invisible to ACP and (for new sessions) not durable. */
internal data class PreparedAcpSession(
    val session: Session,
    val runtime: AcpSessionRuntime,
    val compaction: CompactionSettings? = null,
)

/** Process-only inputs retained before any ACP session cwd is known. */
internal data class AcpProcessInputs(
    val configDirectory: ResolvedConfigDirectory,
    val processEnvironment: (String) -> String?,
    val agentKindOverride: AgentKind?,
    val modelOverride: String?,
    val trustOverride: WorkspaceTrustOverride,
    val includeContextFiles: Boolean,
    val resolveToolAllow: (Set<String>?) -> Set<String>?,
    val strings: AppStrings = AppStrings.english(),
)

/**
 * Creates cwd- and provider-bound runtime state for each ACP session, then closes every owned provider when the
 * protocol connection ends. Defaults keep focused tests/source adapters small; production overrides all preparation
 * methods so configuration is independently resolved from each authoritative cwd.
 */
internal interface AcpSessionRuntimeFactory {
    val defaultModelName: String

    fun create(session: Session): AcpSessionRuntime

    fun canonicalizeWorkspace(cwd: Path): Path = WorkspaceResolver().resolve(cwd).canonicalCwd

    fun prepareNew(cwd: Path, allocate: (model: String) -> Session): PreparedAcpSession {
        val session = allocate(defaultModelName)
        return PreparedAcpSession(session, create(session))
    }

    fun prepareLoad(session: Session): PreparedAcpSession = PreparedAcpSession(session, create(session))

    /** Release one runtime after session preparation fails, so its id is not left active in this connection. */
    suspend fun release(sessionId: Uuid) = Unit

    suspend fun close() = Unit
}

/**
 * Production ACP runtime factory. It retains only process inputs and resolves trust, eligible configuration, context,
 * Foundry composition, provider, and cwd-contained tools afresh for every session. Compatibility constructors retain
 * the old fixed-configuration seam for focused provider ownership tests.
 */
internal class ConfigurationAcpSessionRuntimeFactory private constructor(
    private val mode: Mode,
    private val workspaceResolver: WorkspaceResolver = WorkspaceResolver(),
    private val configurationLoader: WorkspaceConfigurationLoader = WorkspaceConfigurationLoader(),
    private val contextLoader: ContextFileLoader = ContextFileLoader(),
) : AcpSessionRuntimeFactory {
    constructor(inputs: AcpProcessInputs) : this(
        Mode.PerSession(inputs) { configuration ->
            FoundryProjectRuntime.create(configuration).createProvider(configuration)
        },
    )

    internal constructor(
        inputs: AcpProcessInputs,
        providerFactory: (Configuration) -> ProviderRuntime,
    ) : this(Mode.PerSession(inputs, AcpProviderRuntimeFactory(providerFactory)))

    constructor(
        configuration: Configuration,
        toolAllow: Set<String>?,
        foundryProject: FoundryProjectRuntime,
    ) : this(Mode.Fixed(configuration, toolAllow, AcpProviderRuntimeFactory(foundryProject::createProvider)))

    /** Focused test constructor; production uses [AcpProcessInputs]. */
    internal constructor(
        configuration: Configuration,
        toolAllow: Set<String>?,
        providerFactory: (Configuration) -> ProviderRuntime,
    ) : this(Mode.Fixed(configuration, toolAllow, AcpProviderRuntimeFactory(providerFactory)))

    override val defaultModelName: String
        get() = (mode as? Mode.Fixed)?.configuration?.model
            ?: throw IllegalStateException("ACP model is resolved from each session workspace.")

    private val providers = ConcurrentHashMap<Uuid, ProviderRuntime>()

    override fun canonicalizeWorkspace(cwd: Path): Path {
        val workspace = workspaceResolver.resolve(cwd)
        (mode as? Mode.PerSession)?.let {
            workspaceResolver.requireConfigOutsideWorkspace(it.inputs.configDirectory, workspace)
        }
        return workspace.canonicalCwd
    }

    override fun prepareNew(
        cwd: Path,
        allocate: (model: String) -> Session,
    ): PreparedAcpSession = when (val selected = mode) {
        is Mode.Fixed -> super.prepareNew(cwd, allocate)
        is Mode.PerSession -> {
            val workspace = resolveWorkspace(cwd, selected.inputs)
            val loaded = loadSources(workspace, selected.inputs)
            val configuration = Configuration.resolveCandidate(loaded.candidate)
            if (configuration.agentKind == AgentKind.Hosted && selected.inputs.modelOverride != null) {
                throw ConfigurationException(selected.inputs.strings.cliHostedModelConflict)
            }
            val session = allocate(configuration.model)
            prepareConfigured(session, workspace, configuration, selected.inputs)
        }
    }

    override fun prepareLoad(session: Session): PreparedAcpSession = when (val selected = mode) {
        is Mode.Fixed -> {
            val configuration = selected.configuration.copy(
                model = session.modelName,
                agentKind = if (session.hostedBinding == null) AgentKind.Prompt else AgentKind.Hosted,
                promptAgentName = session.promptAgentName,
                hostedAgentName = session.hostedBinding?.agentName ?: selected.configuration.hostedAgentName,
            )
            prepareFixed(session, configuration, selected)
        }
        is Mode.PerSession -> {
            val workspace = resolveWorkspace(session.cwd, selected.inputs)
            require(workspace.canonicalCwd == session.cwd) {
                "Persisted session cwd is not canonical: ${session.cwd} (canonical ${workspace.canonicalCwd})."
            }
            val identity = persistedIdentity(session)
            val loaded = loadSources(workspace, selected.inputs)
            val configuration = Configuration.resolveCandidate(loaded.candidate, identity)
            prepareConfigured(session, workspace, configuration, selected.inputs)
        }
    }

    override fun create(session: Session): AcpSessionRuntime = when (val selected = mode) {
        is Mode.Fixed -> prepareFixed(session, selected.configuration.copy(model = session.modelName), selected).runtime
        is Mode.PerSession -> prepareLoad(session).runtime
    }

    private fun resolveWorkspace(cwd: Path, inputs: AcpProcessInputs): ResolvedWorkspace =
        workspaceResolver.resolve(cwd).also {
            workspaceResolver.requireConfigOutsideWorkspace(inputs.configDirectory, it)
        }

    private fun loadSources(
        workspace: ResolvedWorkspace,
        inputs: AcpProcessInputs,
    ) = WorkspaceTrustCoordinator(
        WorkspaceTrustStore(inputs.configDirectory.canonicalPath, workspace.canonicalRoot),
    ).resolveNonInteractive(inputs.trustOverride).let { outcome ->
        val trusted = when (outcome) {
            is WorkspaceTrustOutcome.Saved -> outcome.decision == WorkspaceTrustDecision.Trusted
            is WorkspaceTrustOutcome.SessionOnly -> outcome.decision == WorkspaceTrustDecision.Trusted
            is WorkspaceTrustOutcome.Override -> outcome.decision == WorkspaceTrustDecision.Trusted
            is WorkspaceTrustOutcome.Error -> throw ConfigurationException(
                if (inputs.trustOverride == WorkspaceTrustOverride.Approve) {
                    inputs.strings.trustApproveError(outcome.snapshot.storePath.toString(), outcome.snapshot.problem)
                } else {
                    inputs.strings.trustStoreError(outcome.snapshot.storePath.toString(), outcome.snapshot.problem)
                },
                outcome.snapshot.cause,
            )
            is WorkspaceTrustOutcome.ChoiceRequired -> error("ACP trust resolution must never request a choice")
        }
        configurationLoader.load(
            workspace = workspace,
            configDirectory = inputs.configDirectory,
            processEnvironment = inputs.processEnvironment,
            projectSourcesTrusted = trusted,
            agentKindOverride = inputs.agentKindOverride,
            modelOverride = inputs.modelOverride,
        )
    }

    private fun persistedIdentity(session: Session): PersistedConfigurationIdentity {
        require(session.modelName.isNotBlank()) { "Persisted session model is missing or blank." }
        val hosted = session.hostedBinding
        return if (hosted == null) {
            PersistedConfigurationIdentity(
                model = session.modelName,
                agentKind = AgentKind.Prompt,
                promptAgentName = session.promptAgentName,
            )
        } else {
            require(session.promptAgentName == null) { "Hosted session cannot also carry a PromptAgent binding." }
            require(hosted.agentName.isNotBlank() && hosted.sessionId == session.id.toString()) {
                "Persisted Hosted session binding is inconsistent for '${session.id}'."
            }
            PersistedConfigurationIdentity(
                model = session.modelName,
                agentKind = AgentKind.Hosted,
                hostedAgentName = hosted.agentName,
            )
        }
    }

    private fun prepareConfigured(
        session: Session,
        workspace: ResolvedWorkspace,
        configuration: Configuration,
        inputs: AcpProcessInputs,
    ): PreparedAcpSession {
        val records = contextLoader.load(
            workspace,
            inputs.configDirectory.canonicalPath,
            includeContextFiles = inputs.includeContextFiles,
        )
        val providerFactory = (mode as Mode.PerSession).providerFactory
        return createOwnedRuntime(
            session,
            configuration,
            inputs.resolveToolAllow(configuration.toolAllow),
            records,
            providerFactory::create,
        )
    }

    private fun prepareFixed(session: Session, configuration: Configuration, fixed: Mode.Fixed): PreparedAcpSession =
        createOwnedRuntime(session, configuration, fixed.toolAllow, emptyList(), fixed.providerFactory::create)

    private fun createOwnedRuntime(
        session: Session,
        configuration: Configuration,
        toolAllow: Set<String>?,
        contextFiles: List<com.konductor.agent.ContextFileRecord>,
        providerFactory: (Configuration) -> ProviderRuntime,
    ): PreparedAcpSession {
        var createdProvider: ProviderRuntime? = null
        try {
            return synchronized(providers) {
                check(!providers.containsKey(session.id)) {
                    "ACP session '${session.id}' is already active in this connection."
                }
                val providerRuntime = providerFactory(configuration).also { createdProvider = it }
                try {
                    val tools = providerRuntime.capabilities.createToolRuntime(toolAllow, session.cwd)
                    val context = AgentContextFactory.build(
                        configuration,
                        cwd = session.cwd,
                        tools = tools.specs,
                        contextFiles = contextFiles,
                    )
                    providers[session.id] = providerRuntime
                    PreparedAcpSession(
                        session,
                        AcpSessionRuntime(providerRuntime, context, tools.executor),
                        configuration.compaction,
                    )
                } catch (failure: Throwable) {
                    providers.remove(session.id, providerRuntime)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            val providerRuntime = createdProvider ?: throw failure
            try {
                runBlocking { providerRuntime.close() }
            } catch (closeFailure: Throwable) {
                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override suspend fun release(sessionId: Uuid) {
        val runtime = synchronized(providers) { providers.remove(sessionId) } ?: return
        runtime.close()
    }

    override suspend fun close() {
        val ownedProviders = synchronized(providers) {
            providers.values.toList().also { providers.clear() }
        }
        var failure: Throwable? = null
        for (runtime in ownedProviders) {
            try {
                runtime.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }

    private sealed interface Mode {
        data class Fixed(
            val configuration: Configuration,
            val toolAllow: Set<String>?,
            val providerFactory: AcpProviderRuntimeFactory,
        ) : Mode

        data class PerSession(
            val inputs: AcpProcessInputs,
            val providerFactory: AcpProviderRuntimeFactory,
        ) : Mode
    }
}

private fun interface AcpProviderRuntimeFactory {
    fun create(configuration: Configuration): ProviderRuntime
}
