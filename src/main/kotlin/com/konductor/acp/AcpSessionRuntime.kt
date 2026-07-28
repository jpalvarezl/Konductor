package com.konductor.acp

import com.konductor.agent.AgentContextFactory
import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.foundry.project.FoundryProjectRuntime
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderRuntime
import com.konductor.provider.ToolExecutor
import com.konductor.tool.createToolRuntime
import kotlinx.coroutines.CancellationException
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

/**
 * Creates cwd- and provider-bound runtime state for each ACP session, then closes every owned provider when the
 * protocol connection ends.
 */
internal interface AcpSessionRuntimeFactory {
    val defaultModelName: String

    fun create(session: Session): AcpSessionRuntime

    suspend fun close() = Unit
}

/**
 * Production ACP runtime factory. Providers are deliberately session-scoped: Prompt sessions get independent
 * Responses/binding state, while Hosted sessions must never share a server-side session handle.
 */
internal class ConfigurationAcpSessionRuntimeFactory private constructor(
    private val configuration: Configuration,
    private val toolAllow: Set<String>?,
    private val providerFactory: AcpProviderRuntimeFactory,
) : AcpSessionRuntimeFactory {
    constructor(
        configuration: Configuration,
        toolAllow: Set<String>?,
        foundryProject: FoundryProjectRuntime,
    ) : this(configuration, toolAllow, AcpProviderRuntimeFactory(foundryProject::createProvider))

    /** Focused test constructor; production receives the shared [FoundryProjectRuntime] composition boundary. */
    internal constructor(
        configuration: Configuration,
        toolAllow: Set<String>?,
        providerFactory: (Configuration) -> ProviderRuntime,
    ) : this(configuration, toolAllow, AcpProviderRuntimeFactory(providerFactory))

    override val defaultModelName: String = configuration.model

    private val providers = ConcurrentHashMap<Uuid, ProviderRuntime>()

    override fun create(session: Session): AcpSessionRuntime {
        val runtime = synchronized(providers) {
            check(!providers.containsKey(session.id)) {
                "ACP session '${session.id}' is already active in this connection."
            }
            val sessionConfiguration = configuration.copy(
                model = session.modelName,
                promptAgentName = session.promptAgentName ?: configuration.promptAgentName,
            )
            val providerRuntime = providerFactory.create(sessionConfiguration)
            val tools = providerRuntime.capabilities.createToolRuntime(toolAllow, session.cwd)
            val context = AgentContextFactory.build(sessionConfiguration, cwd = session.cwd, tools = tools.specs)
            providers[session.id] = providerRuntime
            AcpSessionRuntime(
                providerRuntime = providerRuntime,
                context = context,
                toolExecutor = tools.executor,
            )
        }
        return runtime
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
}

private fun interface AcpProviderRuntimeFactory {
    fun create(configuration: Configuration): ProviderRuntime
}
