package com.konductor.conversation

import com.konductor.agent.PromptAgentBindingResult
import com.konductor.core.AppState
import com.konductor.core.MessageRole
import com.konductor.core.models.AgentContext
import com.konductor.core.models.ToolSpec
import com.konductor.provider.inference.PromptAgentClient
import com.konductor.provider.inference.PromptAgentRef
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PromptAgentCommandTest {
    private val context = AgentContext(
        systemPrompt = "base\n\nappend\n\ncontext\n\nenvironment",
        tools = emptyList(),
        modelName = "gpt-x",
        baseSystemPrompt = "base\n\nappend",
        dynamicPreamble = "context\n\nenvironment",
    )

    private fun command(state: AppState, fake: MockPromptAgent, cwd: Path = Path.of("").toAbsolutePath()) =
        PromptAgentCommand(state, { context }, { fake.activeAgent }, fake::adopt, fake, cwd)

    private fun lastSystem(state: AppState): String =
        state.messages.last { it.role == MessageRole.System }.content

    private fun execute(command: PromptAgentCommand, input: String) {
        val invocation = requireNotNull(CommandInvocation.parse(input))
        val action = assertIs<CommandAction.Background>(command.execute(invocation))
        runBlocking { action.run(StateApplier { mutation -> mutation() }) }
    }

    @Test
    fun `bare agent shows ephemeral when unbound`() {
        val state = AppState()
        execute(command(state, MockPromptAgent()), "/agent")
        assertTrue(lastSystem(state).contains("ephemeral"))
    }

    @Test
    fun `use reports and displays only the coordinated committed normalized name`() {
        val state = AppState()
        val fake = MockPromptAgent()

        execute(command(state, fake), "/agent use   Billing  ")

        assertEquals("Billing", fake.activeAgent)
        assertEquals("Billing", state.activeAgentName)
        assertEquals(listOf<String?>("Billing"), fake.adoptCalls)
        assertTrue(lastSystem(state).contains("Billing"))
    }

    @Test
    fun `use rejection retains provider and displayed status`() {
        val state = AppState(activeAgentName = "old")
        val fake = MockPromptAgent(initialAgent = "old", adoptionFailure = IllegalStateException("disk rejected"))

        execute(command(state, fake), "/agent use candidate")

        assertEquals("old", fake.activeAgent)
        assertEquals("old", state.activeAgentName)
        assertTrue(lastSystem(state).contains("current local binding is unchanged"))
        assertTrue(lastSystem(state).contains("/agent use candidate"))
    }

    @Test
    fun `same-name use succeeds without status mutation`() {
        val state = AppState(activeAgentName = "billing")
        val fake = MockPromptAgent(initialAgent = "billing")

        execute(command(state, fake), "/agent use billing")

        assertEquals("billing", state.activeAgentName)
        assertEquals(listOf<String?>("billing"), fake.adoptCalls)
        assertTrue(lastSystem(state).contains("Switched"))
    }

    @Test
    fun `create mints stable context then adopts by returned name`() {
        val state = AppState()
        val fake = MockPromptAgent(onCreate = { PromptAgentRef(it, "7") })

        execute(command(state, fake), "/agent create billing")

        assertEquals("billing", fake.createdName)
        assertEquals("gpt-x", fake.createdModel)
        assertEquals("base\n\nappend", fake.createdInstructions)
        assertEquals("billing", state.activeAgentName)
        assertEquals(listOf<String?>("billing"), fake.adoptCalls)
        assertTrue(lastSystem(state).contains("version 7"))
        assertTrue(lastSystem(state).contains("not pinned"))
    }

    @Test
    fun `ambiguous create failure leaves local binding and gives reconciliation guidance`() {
        val state = AppState(activeAgentName = "old")
        val fake = MockPromptAgent(
            initialAgent = "old",
            onCreate = { throw IllegalStateException("connection reset") },
        )

        execute(command(state, fake), "/agent create billing")

        assertEquals("old", fake.activeAgent)
        assertEquals("old", state.activeAgentName)
        assertTrue(fake.adoptCalls.isEmpty())
        assertTrue(lastSystem(state).contains("may exist"))
        assertTrue(lastSystem(state).contains("/agent list"))
        assertTrue(lastSystem(state).contains("/agent use billing"))
    }

    @Test
    fun `created version whose local adoption fails is not reported adopted`() {
        val state = AppState(activeAgentName = "old")
        val fake = MockPromptAgent(
            initialAgent = "old",
            adoptionFailure = IllegalStateException("metadata rejected"),
            onCreate = { PromptAgentRef(it, "9") },
        )

        execute(command(state, fake), "/agent create billing")

        assertEquals("old", fake.activeAgent)
        assertEquals("old", state.activeAgentName)
        assertTrue(lastSystem(state).contains("version 9"))
        assertTrue(lastSystem(state).contains("not adopted locally"))
        assertTrue(lastSystem(state).contains("/agent use billing"))

        fake.adoptionFailure = null
        execute(command(state, fake), "/agent use billing")
        assertEquals("billing", fake.activeAgent)
        assertEquals("billing", state.activeAgentName)
    }

    @Test
    fun `create without a name derives a cwd-based default`() {
        val state = AppState()
        execute(command(state, MockPromptAgent(), cwd = Path.of("home", "My Project")), "/agent create")
        assertEquals("konductor-my-project", state.activeAgentName)
    }

    @Test
    fun `list marks the active agent without adopting`() {
        val state = AppState()
        val fake = MockPromptAgent(initialAgent = "beta", names = listOf("alpha", "beta"))
        execute(command(state, fake), "/agent list")
        val msg = lastSystem(state)
        assertTrue(msg.contains("alpha"))
        assertTrue(msg.contains("* beta"))
        assertTrue(fake.adoptCalls.isEmpty())
    }

    @Test
    fun `unknown subcommand reports usage`() {
        val state = AppState()
        execute(command(state, MockPromptAgent()), "/agent frobnicate")
        assertTrue(lastSystem(state).contains("Unknown /agent subcommand"))
    }
}

private class MockPromptAgent(
    initialAgent: String? = null,
    private val names: List<String> = emptyList(),
    var adoptionFailure: RuntimeException? = null,
    private val onCreate: (String) -> PromptAgentRef = { PromptAgentRef(it, "1") },
) : PromptAgentClient {
    var activeAgent: String? = initialAgent
        private set
    val adoptCalls: MutableList<String?> = mutableListOf()

    var createdName: String? = null
        private set
    var createdModel: String? = null
        private set
    var createdInstructions: String? = null
        private set

    fun adopt(agentName: String?): PromptAgentBindingResult {
        val normalized = agentName?.trim()?.ifBlank { null }
        adoptCalls += normalized
        adoptionFailure?.let { throw it }
        val changed = activeAgent != normalized
        activeAgent = normalized
        return PromptAgentBindingResult(normalized, changed)
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
