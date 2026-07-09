package com.konductor.acp

import com.agentclientprotocol.agent.Agent
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.client.ClientInfo
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.LATEST_PROTOCOL_VERSION
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import com.konductor.agent.AgentLoop
import com.konductor.compaction.CompactionSettings
import com.konductor.core.models.AgentContext
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * Headless [Agent Client Protocol](https://agentclientprotocol.com) entry point.
 *
 * Runs Konductor as an ACP *agent* over stdin/stdout (JSON-RPC 2.0) instead of the Lanterna TUI, so any
 * ACP client — an editor such as Zed, another tool, or (later) another Konductor instance — can drive it.
 * It reuses the same agent stack as the TUI (real single-turn Prompt inference via [AgentLoop], M1), just
 * with an ACP frontend instead of the terminal one — which also makes it the scriptable way to exercise
 * Konductor end-to-end as a separate process.
 *
 * `runBlocking` stays alive until the transport's read/write coroutines finish, i.e. until stdin reaches
 * EOF or the client disconnects. The caller owns the shared [AgentProvider] lifecycle (closes it afterwards).
 *
 * NOTE: stdout is the JSON-RPC channel in this mode. Nothing on this path may print to stdout; diagnostic
 * logging must go to stderr or a file.
 */
fun runAcpAgent(
    provider: AgentProvider,
    context: AgentContext,
    toolExecutor: ToolExecutor,
    // Same auto-compaction settings as the TUI (Main threads Configuration.compaction). ACP sessions are
    // ephemeral (NoOpSessionStore), so compaction runs purely in-memory — the marker insertion + reconstruction
    // still keep a long headless session under the context window; the summary event is just not surfaced over
    // ACP yet (Phase C, like tool_call). Defaults to the enabled config default for any direct caller.
    compaction: CompactionSettings = CompactionSettings(),
): Unit = runBlocking {
    // Adapt stdin/stdout to the transport's Flow-based (non-deprecated) contract: a cold flow of incoming
    // NDJSON lines, and a per-line writer that owns newline framing + flushing. Both run on Dispatchers.IO.
    val input: Flow<String> = flow {
        System.`in`.bufferedReader().useLines { lines -> lines.forEach { emit(it) } }
    }.flowOn(Dispatchers.IO)
    val output: suspend (String) -> Unit = { line ->
        withContext(Dispatchers.IO) {
            System.out.write((line + "\n").toByteArray())
            System.out.flush()
        }
    }
    val transport = StdioTransport(
        parentScope = this,
        ioDispatcher = Dispatchers.IO,
        input = input,
        output = output,
    )
    val protocol = Protocol(this, transport)
    Agent(protocol, KonductorAgentSupport(provider, context, toolExecutor, compaction))
    protocol.start()

    // Stay alive until the client disconnects (stdin EOF closes the transport). Once closed, cancel the
    // lingering protocol coroutines so runBlocking can return and the JVM exits cleanly.
    transport.state.first { it == Transport.State.CLOSED }
    coroutineContext.cancelChildren()
}

/**
 * Wires ACP requests to the real Konductor agent loop. Every `session/new` mints a fresh [AgentLoop] over a
 * shared inference stack ([provider] + [context]), so each ACP session keeps an independent transcript.
 */
private class KonductorAgentSupport(
    private val provider: AgentProvider,
    private val context: AgentContext,
    private val toolExecutor: ToolExecutor,
    private val compaction: CompactionSettings,
) : AgentSupport {
    private val sessionCounter = AtomicLong(0)

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo =
        AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(),
        )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        KonductorAgentSession(
            sessionId = SessionId("konductor-${sessionCounter.incrementAndGet()}"),
            agentLoop = AgentLoop(provider, toolExecutor, context, compaction = compaction),
        )
}

/**
 * ACP session backed by the real [AgentLoop]. `session/prompt` runs one Prompt turn and streams its
 * [AgentEvent]s onto ACP `session/update`s: each assistant [AgentEvent.TextDelta] becomes an
 * `agent_message_chunk` (token-by-token), failures surface as a chunk, and every turn ends with an
 * `end_turn` stop reason. `TurnCompleted` only emits a chunk as a fallback when the turn produced no deltas.
 */
internal class KonductorAgentSession(
    override val sessionId: SessionId,
    private val agentLoop: AgentLoop,
) : AgentSession {
    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val userText = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

        var streamedAny = false
        agentLoop.runTurn(userText).collect { event ->
            val chunk = when (event) {
                is AgentEvent.TextDelta -> event.text
                is AgentEvent.Failed -> "⚠ ${event.error.message ?: event.error::class.simpleName}"
                // Fallback: emit the full answer only if nothing streamed (otherwise deltas already covered it).
                is AgentEvent.TurnCompleted -> event.assistant.text.takeUnless { streamedAny }
                // Tools DO execute (the real executor is wired in), but ACP tool_call/tool_call_update
                // session updates are deferred to ACP Phase C; hosted logs to M5; usage has no ACP channel yet.
                else -> null
            }
            if (chunk != null) {
                streamedAny = true
                emit(Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(chunk))))
            }
        }

        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    }
}
