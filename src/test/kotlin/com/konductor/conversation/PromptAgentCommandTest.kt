package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptAgentCommandTest {
    private val context = AgentContext(
        baseSystemPrompt = "base",
        tools = emptyList(),
        modelName = "gpt-x",
        dynamicPreamble = "env",
    )

    private fun lastSystem(state: AppState): String =
        state.messages.last { it.role == MessageRole.System }.content

    @Test
    fun `bare agent shows ephemeral when unbound`() {
        val state = AppState()
        PromptAgentCommand(state, context, FakePromptAgentClient()).handle("/agent")
        assertTrue(lastSystem(state).contains("ephemeral"))
    }

    @Test
    fun `use binds the agent and updates the active name`() {
        val state = AppState()
        val client = FakePromptAgentClient()
        PromptAgentCommand(state, context, client).handle("/agent use billing")
        assertEquals("billing", client.activeAgentName)
        assertEquals("billing", state.activeAgentName)
        assertEquals(listOf<String?>("billing"), client.bindCalls)
    }

    @Test
    fun `create mints a version from the current context and switches to it`() {
        val state = AppState()
        val client = FakePromptAgentClient(onCreate = { PromptAgentRef(it, "7") })
        PromptAgentCommand(state, context, client).handle("/agent create billing")
        assertEquals(context, client.createdContext) // baked from the current AgentContext
        assertEquals("billing", state.activeAgentName)
        assertEquals(listOf<String?>("billing"), client.bindCalls)
        assertTrue(lastSystem(state).contains("version 7"))
    }

    @Test
    fun `create without a name derives a cwd-based default`() {
        val state = AppState()
        val client = FakePromptAgentClient(onCreate = { PromptAgentRef(it, "1") })
        PromptAgentCommand(state, context, client, cwd = Path.of("home", "My Project")).handle("/agent create")
        assertEquals("konductor-my-project", state.activeAgentName)
    }

    @Test
    fun `list marks the active agent`() {
        val state = AppState()
        val client = FakePromptAgentClient(names = listOf("alpha", "beta"))
        client.bindAgent("beta")
        PromptAgentCommand(state, context, client).handle("/agent list")
        val msg = lastSystem(state)
        assertTrue(msg.contains("alpha"))
        assertTrue(msg.contains("* beta"))
    }

    @Test
    fun `unknown subcommand reports usage`() {
        val state = AppState()
        PromptAgentCommand(state, context, FakePromptAgentClient()).handle("/agent frobnicate")
        assertTrue(lastSystem(state).contains("Unknown /agent subcommand"))
    }

    @Test
    fun `an SDK failure surfaces as a system line and does not switch`() {
        val state = AppState()
        val client = FakePromptAgentClient(onCreate = { throw IllegalStateException("nope") })
        PromptAgentCommand(state, context, client).handle("/agent create x")
        assertTrue(lastSystem(state).contains("/agent failed"))
        assertNull(state.activeAgentName)
    }
}

private class FakePromptAgentClient(
    private val names: List<String> = emptyList(),
    private val onCreate: (String) -> PromptAgentRef = { PromptAgentRef(it, "1") },
) : PromptAgentClient {
    override var activeAgentName: String? = null
        private set

    val bindCalls: MutableList<String?> = mutableListOf()
    var createdContext: AgentContext? = null
        private set

    override fun bindAgent(agentName: String?) {
        bindCalls += agentName
        activeAgentName = agentName
    }

    override suspend fun listAgentNames(): List<String> = names

    override suspend fun createAgentVersion(agentName: String, context: AgentContext): PromptAgentRef {
        createdContext = context
        return onCreate(agentName)
    }
}
