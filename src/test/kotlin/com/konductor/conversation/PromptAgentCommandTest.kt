package com.konductor.conversation

import com.konductor.core.AppState
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import com.konductor.provider.inference.PromptAgentBinder
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromptAgentCommandTest {
    private val context = AgentContext(
        systemPrompt = "base",
        tools = emptyList(),
        modelName = "gpt-x",
    )

    private fun command(state: AppState, fake: FakePromptAgent, cwd: Path = Path.of("").toAbsolutePath()) =
        PromptAgentCommand(state, context, fake, fake, fake::record, cwd)

    private fun lastSystem(state: AppState): String =
        state.messages.last { it.role == MessageRole.System }.content

    @Test
    fun `bare agent shows ephemeral when unbound`() {
        val state = AppState()
        command(state, FakePromptAgent()).handle("/agent")
        assertTrue(lastSystem(state).contains("ephemeral"))
    }

    @Test
    fun `use switches the agent (via the binder) and updates the active name`() {
        val state = AppState()
        val fake = FakePromptAgent()
        command(state, fake).handle("/agent use billing")
        assertEquals("billing", fake.activeAgent)
        assertEquals("billing", state.activeAgentName)
        assertEquals(listOf<String?>("billing"), fake.bindCalls)
    }

    @Test
    fun `create mints a version from the current context and switches to it`() {
        val state = AppState()
        val fake = FakePromptAgent(onCreate = { PromptAgentRef(it, "7") })
        command(state, fake).handle("/agent create billing")
        // Baked from the current context: name + model + instructions (the context's system prompt).
        assertEquals("billing", fake.createdName)
        assertEquals("gpt-x", fake.createdModel)
        assertEquals("base", fake.createdInstructions)
        assertEquals("billing", state.activeAgentName)
        assertEquals(listOf<String?>("billing"), fake.bindCalls)
        assertTrue(lastSystem(state).contains("version 7"))
    }

    @Test
    fun `create without a name derives a cwd-based default`() {
        val state = AppState()
        command(state, FakePromptAgent(), cwd = Path.of("home", "My Project")).handle("/agent create")
        assertEquals("konductor-my-project", state.activeAgentName)
    }

    @Test
    fun `list marks the active agent`() {
        val state = AppState()
        val fake = FakePromptAgent(names = listOf("alpha", "beta"))
        fake.bindAgent("beta")
        command(state, fake).handle("/agent list")
        val msg = lastSystem(state)
        assertTrue(msg.contains("alpha"))
        assertTrue(msg.contains("* beta"))
    }

    @Test
    fun `the agent prefix and subcommand are case-insensitive while the name keeps its case`() {
        val state = AppState()
        val fake = FakePromptAgent()
        command(state, fake).handle("/AGENT Use Billing")
        assertEquals("Billing", fake.activeAgent)
        assertEquals("Billing", state.activeAgentName)
    }

    @Test
    fun `unknown subcommand reports usage`() {
        val state = AppState()
        command(state, FakePromptAgent()).handle("/agent frobnicate")
        assertTrue(lastSystem(state).contains("Unknown /agent subcommand"))
    }

    @Test
    fun `an SDK failure surfaces as a system line and does not switch`() {
        val state = AppState()
        command(state, FakePromptAgent(onCreate = { throw IllegalStateException("nope") })).handle("/agent create x")
        assertTrue(lastSystem(state).contains("/agent failed"))
        assertNull(state.activeAgentName)
    }

    @Test
    fun `use persists the agent to the session`() {
        val state = AppState()
        val fake = FakePromptAgent()
        command(state, fake).handle("/agent use billing")
        assertEquals(listOf<String?>("billing"), fake.recorded)
    }

    @Test
    fun `a fresh session adopts and records the currently-bound agent`() {
        val state = AppState()
        val fake = FakePromptAgent()
        fake.bindAgent("cfg") // e.g. bound from KONDUCTOR_PROMPT_AGENT_NAME at startup
        command(state, fake).onFreshSession()
        assertEquals("cfg", state.activeAgentName)
        assertEquals(listOf<String?>("cfg"), fake.recorded)
    }

    @Test
    fun `a resumed session restores its saved agent when it still exists`() {
        val state = AppState()
        val fake = FakePromptAgent(names = listOf("billing"))
        command(state, fake).onResumedSession("billing")
        assertEquals("billing", fake.activeAgent)
        assertEquals("billing", state.activeAgentName)
    }

    @Test
    fun `a resumed session falls back to ephemeral when its agent is gone`() {
        val state = AppState()
        val fake = FakePromptAgent(names = emptyList()) // 'billing' was deleted server-side (agents are volatile)
        command(state, fake).onResumedSession("billing")
        assertNull(fake.activeAgent)
        assertNull(state.activeAgentName)
        assertTrue(lastSystem(state).contains("no longer available"))
    }

    @Test
    fun `resuming an ephemeral session unbinds any active agent`() {
        val state = AppState()
        val fake = FakePromptAgent()
        fake.bindAgent("cfg")
        command(state, fake).onResumedSession(null)
        assertNull(fake.activeAgent)
        assertNull(state.activeAgentName)
    }
}

/** Combined fake implementing both seams the command depends on: the live [PromptAgentBinder] + [PromptAgentClient]. */
private class FakePromptAgent(
    private val names: List<String> = emptyList(),
    private val onCreate: (String) -> PromptAgentRef = { PromptAgentRef(it, "1") },
) : PromptAgentBinder, PromptAgentClient {
    override var activeAgent: String? = null
        private set
    val bindCalls: MutableList<String?> = mutableListOf()
    val recorded: MutableList<String?> = mutableListOf()

    fun record(agentName: String?) {
        recorded += agentName
    }

    var createdName: String? = null
        private set
    var createdModel: String? = null
        private set
    var createdInstructions: String? = null
        private set

    override fun bindAgent(agentName: String?) {
        bindCalls += agentName
        activeAgent = agentName
    }

    override suspend fun listAgents(): List<String> = names

    override suspend fun createAgentVersion(
        name: String,
        model: String,
        instructions: String,
        tools: List<ToolSpec>,
    ): PromptAgentRef {
        createdName = name
        createdModel = model
        createdInstructions = instructions
        return onCreate(name)
    }
}

