package com.konductor.conversation

import com.konductor.core.models.ToolSpec
import com.konductor.foundry.project.deployment.FoundryDeployment
import com.konductor.foundry.project.deployment.FoundryDeploymentCatalog
import com.konductor.provider.ProviderManagement
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import com.konductor.session.SessionSummary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CommandOptionsTest {
    @Test
    fun loadsSdkFreeModelOptions() = runBlocking {
        val provider = FoundryModelOptionProvider(
            MockOptionDeploymentCatalog(
                listOf(
                    FoundryDeployment("app", "AppDeployment"),
                    FoundryDeployment("deployment-a", "ModelDeployment", modelName = "gpt-a"),
                ),
            ),
        )

        assertEquals(
            listOf(CommandOption("deployment-a", detail = "gpt-a")),
            provider.loadOptions(),
        )
    }

    @Test
    fun loadsRecentSessionsMostRecentFirstWithFullIdentity() = runBlocking {
        val olderId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val newerId = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val older = session(olderId, "Older", "2026-07-29T10:00:00Z", 2)
        val newer = session(newerId, null, "2026-07-30T10:00:00Z", 5)
        val source = mutableListOf(older, newer)
        val provider = RecentSessionOptionProvider(source::toList)

        val options = provider.loadOptions()

        assertEquals(listOf(newerId.toString(), olderId.toString()), options.map(CommandOption::value))
        assertEquals(listOf("(unnamed)", "Older"), options.map(CommandOption::label))
        assertEquals("id 00000000 • 5 entries • updated 2026-07-30T10:00:00Z", options.first().detail)
        assertEquals(listOf(older, newer), source)
    }

    @Test
    fun loadsExactPromptAgentNamesWithoutChangingBinding() = runBlocking {
        val binder = RecordingPromptAgentBinder("billing-agent")
        val lifecycle = MockPromptAgentLifecycle(listOf("billing-agent", "Agent With Spaces"))
        val provider = PromptAgentOptionProvider(ProviderManagement.PromptAgents(binder, lifecycle))

        val options = provider.loadOptions()

        assertEquals(listOf("billing-agent", "Agent With Spaces"), options.map(CommandOption::value))
        assertEquals(listOf("Active PromptAgent", "Available PromptAgent"), options.map(CommandOption::detail))
        assertEquals("billing-agent", binder.activeAgent)
        assertTrue(binder.bindings.isEmpty())
        assertEquals(1, lifecycle.listCalls)
    }

    @Test
    fun propagatesCatalogFailures() = runBlocking {
        val expected = IllegalStateException("offline")
        val provider = FoundryModelOptionProvider(MockOptionDeploymentCatalog(failure = expected))

        val actual = runCatching { provider.loadOptions() }.exceptionOrNull()

        assertEquals(expected::class, actual?.let { it::class })
        assertEquals(expected.message, actual?.message)
    }

    private fun session(id: Uuid, name: String?, updatedAt: String, entries: Int): SessionSummary = SessionSummary(
        id = id,
        name = name,
        createdAt = Instant.parse("2026-07-01T00:00:00Z"),
        updatedAt = Instant.parse(updatedAt),
        entryCount = entries,
    )
}

private class RecordingPromptAgentBinder(
    override var activeAgent: String?,
) : PromptAgentBinder {
    val bindings = mutableListOf<String?>()

    override fun prepareBinding(agentName: String?) = agentName?.trim()?.ifBlank { null }.let { normalized ->
        com.konductor.provider.inference.PreparedPromptAgentBinding(normalized, commitAction = {
            bindings += normalized
            activeAgent = normalized
        })
    }
}

private class MockPromptAgentLifecycle(
    private val names: List<String>,
) : PromptAgentClient {
    var listCalls: Int = 0
        private set

    override suspend fun listAgents(): List<String> {
        listCalls++
        return names
    }

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef = error("Not used by option discovery")
}

private class MockOptionDeploymentCatalog(
    private val deployments: List<FoundryDeployment> = emptyList(),
    private val failure: Exception? = null,
) : FoundryDeploymentCatalog {
    override fun listDeployments(): List<FoundryDeployment> {
        failure?.let { throw it }
        return deployments
    }

    override fun getDeployment(name: String): FoundryDeployment = deployments.single { it.name == name }
}
