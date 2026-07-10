package com.konductor.acp

import com.konductor.agent.AgentContextFactory
import com.konductor.config.Configuration
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.provider.AgentProvider
import com.konductor.provider.ProviderFactory
import com.konductor.provider.ToolExecutor
import com.konductor.tool.BuiltinTools
import com.konductor.tool.RegistryToolExecutor
import com.konductor.tool.ToolContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** Runtime state owned by one logical ACP session. */
internal data class AcpSessionRuntime(
    val provider: AgentProvider,
    val context: AgentContext,
    val toolExecutor: ToolExecutor,
)

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
 * inference/binding state, while Hosted sessions must never share a server-side session handle.
 */
internal class ConfigurationAcpSessionRuntimeFactory(
    private val configuration: Configuration,
    private val toolAllow: Set<String>?,
    private val providerFactory: (Configuration) -> AgentProvider = ProviderFactory::create,
) : AcpSessionRuntimeFactory {
    override val defaultModelName: String = configuration.model

    private val providers = ConcurrentHashMap<Uuid, AgentProvider>()

    override fun create(session: Session): AcpSessionRuntime {
        val runtime = synchronized(providers) {
            check(!providers.containsKey(session.id)) {
                "ACP session '${session.id}' is already active in this connection."
            }
            val sessionConfiguration = configuration.copy(
                model = session.modelName,
                promptAgentName = session.promptAgentName ?: configuration.promptAgentName,
            )
            val registry = BuiltinTools.registry(toolAllow)
            val context = AgentContextFactory.build(
                sessionConfiguration,
                cwd = session.cwd,
                tools = registry.enabled().map { it.spec },
            )
            val provider = providerFactory(sessionConfiguration)
            providers[session.id] = provider
            AcpSessionRuntime(
                provider = provider,
                context = context,
                toolExecutor = RegistryToolExecutor(registry, ToolContext(session.cwd)),
            )
        }
        return runtime
    }

    override suspend fun close() {
        val ownedProviders = synchronized(providers) {
            providers.values.toList().also { providers.clear() }
        }
        var failure: Throwable? = null
        for (provider in ownedProviders) {
            try {
                provider.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        failure?.let { throw it }
    }
}
