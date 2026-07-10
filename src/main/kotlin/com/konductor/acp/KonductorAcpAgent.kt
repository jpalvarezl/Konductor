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
import com.agentclientprotocol.model.SessionCapabilities
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionInfo
import com.agentclientprotocol.model.SessionListCapabilities
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.agentclientprotocol.transport.Transport
import com.konductor.agent.AgentLoop
import com.konductor.agent.TurnAlreadyInProgressException
import com.konductor.compaction.CompactionSettings
import com.konductor.core.models.AgentContext
import com.konductor.core.models.Session
import com.konductor.provider.AgentEvent
import com.konductor.provider.AgentProvider
import com.konductor.provider.ToolExecutor
import com.konductor.session.SessionStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

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
    store: SessionStore,
    // Same auto-compaction settings as the TUI (Main threads Configuration.compaction). ACP sessions now persist
    // via [store] (keyed by the client cwd), so compaction runs over the persisted transcript. Defaults to the
    // enabled config default for any direct caller.
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
    Agent(protocol, KonductorAgentSupport(provider, context, toolExecutor, store, compaction))
    protocol.start()

    // Stay alive until the client disconnects (stdin EOF closes the transport). Once closed, cancel the
    // lingering protocol coroutines so runBlocking can return and the JVM exits cleanly.
    transport.state.first { it == Transport.State.CLOSED }
    coroutineContext.cancelChildren()
}

/**
 * Wires ACP requests to the real Konductor agent loop, backed by a persistent [store]. `session/new` creates a
 * fresh session under the client-provided cwd; `session/load` resumes a persisted one; `session/list` enumerates
 * them. Each session gets its own [AgentLoop] over the shared inference stack ([provider] + [context]), so
 * transcripts stay independent and survive restarts. The Konductor session UUID is used directly as the ACP
 * [SessionId], giving a 1:1 mapping for load/list.
 */
internal class KonductorAgentSupport(
    private val provider: AgentProvider,
    private val context: AgentContext,
    private val toolExecutor: ToolExecutor,
    private val store: SessionStore,
    private val compaction: CompactionSettings,
) : AgentSupport {

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo =
        AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            // Advertise both session/load (legacy top-level flag) and session/list (via sessionCapabilities.list)
            // so a spec-compliant client that gates on capabilities actually offers resume + listing.
            capabilities = AgentCapabilities(
                loadSession = true,
                sessionCapabilities = SessionCapabilities(list = SessionListCapabilities()),
            ),
        )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        agentSessionFor(store.create(Path.of(sessionParameters.cwd), context.modelName, name = null))

    override suspend fun loadSession(sessionId: SessionId, sessionParameters: SessionCreationParameters): AgentSession {
        // ACP SessionId maps 1:1 to a Konductor session UUID. Validate up front so a non-UUID (or a legacy id)
        // yields an actionable JSON-RPC error rather than a low-signal Uuid.parse failure.
        val id = runCatching { Uuid.parse(sessionId.value) }.getOrElse {
            throw IllegalArgumentException(
                "Cannot load ACP session '${sessionId.value}': expected a Konductor session id (a UUID).",
            )
        }
        return agentSessionFor(store.load(id))
    }

    override suspend fun listSessions(
        cwd: String?,
        _additionalDirectories: List<String>?,
        _meta: JsonElement?,
    ): Sequence<SessionInfo> {
        val dir = cwd ?: return emptySequence()
        return store.listForCwd(Path.of(dir)).asSequence().map { summary ->
            SessionInfo(
                sessionId = SessionId(summary.id.toString()),
                cwd = dir,
                title = summary.name ?: "(unnamed)",
                updatedAt = summary.updatedAt.toString(),
            )
        }
    }

    private fun agentSessionFor(session: Session): KonductorAgentSession =
        KonductorAgentSession(
            sessionId = SessionId(session.id.toString()),
            agentLoop = AgentLoop(provider, toolExecutor, context, store, session, compaction),
        )
}

/**
 * ACP session backed by the real [AgentLoop]. `session/prompt` runs one Prompt turn and streams its
 * [AgentEvent]s onto ACP `session/update`s: assistant [AgentEvent.TextDelta]s become `agent_message_chunk`s
 * (token-by-token); tool activity becomes `tool_call` (started) + `tool_call_update` (completed) so the client
 * can see the agent read/edit files; failures surface as a chunk; and every turn ends with an `end_turn` stop
 * reason. `TurnCompleted` only emits a chunk as a fallback when the turn produced no deltas.
 *
 * ACP follows the TUI's reject policy for overlap: a second collected prompt fails with
 * [TurnAlreadyInProgressException] while the active prompt remains the sole cancellation target.
 */
internal class KonductorAgentSession(
    override val sessionId: SessionId,
    private val agentLoop: AgentLoop,
) : AgentSession {
    // The in-flight turn's Job so `session/cancel` can stop it. AtomicReference because prompt() and cancel() may
    // run on different coroutines.
    private val activeTurn = AtomicReference<Job?>(null)

    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = channelFlow {
        val userText = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

        var streamedAny = false
        // Run the turn as a cancelable child job. cancel() cancels it; the CancellationException propagates through
        // the AgentLoop/PromptProvider flows (which are exception-transparent) and stops the inference.
        val turn = launch(start = CoroutineStart.LAZY) {
            agentLoop.runTurn(userText).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> {
                        streamedAny = true
                        send(messageChunk(event.text))
                    }
                    is AgentEvent.Failed -> send(messageChunk("⚠ ${event.error.message ?: event.error::class.simpleName}"))
                    // Fallback: only if nothing streamed (otherwise the deltas already covered it).
                    is AgentEvent.TurnCompleted ->
                        if (!streamedAny && event.assistant.text.isNotEmpty()) send(messageChunk(event.assistant.text))
                    is AgentEvent.ToolCallStarted -> send(
                        Event.SessionUpdateEvent(
                            SessionUpdate.ToolCall(
                                toolCallId = ToolCallId(event.call.callId),
                                // Stub title = tool name; swaps to the richer tool/ToolRendering summary at consolidation.
                                title = event.call.name,
                                kind = toolKind(event.call.name),
                                status = ToolCallStatus.IN_PROGRESS,
                            ),
                        ),
                    )
                    is AgentEvent.ToolCallCompleted -> send(
                        Event.SessionUpdateEvent(
                            SessionUpdate.ToolCallUpdate(
                                toolCallId = ToolCallId(event.call.callId),
                                title = event.call.name,
                                kind = toolKind(event.call.name),
                                status = if (event.result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED,
                                content = listOf(ToolCallContent.Content(ContentBlock.Text(event.result.output))),
                            ),
                        ),
                    )
                    // Hosted-session container logs stream to the client as their own log-prefixed message
                    // chunks (mirroring the TUI's 📋 lines), so an ACP client sees the agent's progress too.
                    is AgentEvent.LogFrame -> send(messageChunk("📋 ${event.line}"))
                    // UsageReported / Compacted have no ACP channel yet (Phase C follow-ups).
                    else -> Unit
                }
            }
        }
        // Register the job BEFORE starting it, so session/cancel cannot miss it. Reject overlap instead of
        // replacing the reference: otherwise cancel could target the queued/rejected prompt rather than the
        // turn currently mutating this session.
        if (!activeTurn.compareAndSet(null, turn)) {
            turn.cancel()
            throw TurnAlreadyInProgressException()
        }
        try {
            turn.start()
            turn.join()
        } finally {
            activeTurn.compareAndSet(turn, null)
        }

        // A cancelled turn ends with CANCELLED (its in-turn work was interrupted); otherwise END_TURN.
        val stop = if (turn.isCancelled) StopReason.CANCELLED else StopReason.END_TURN
        send(Event.PromptResponseEvent(PromptResponse(stop)))
    }

    /** `session/cancel`: stop the in-flight turn, if any. */
    override suspend fun cancel() {
        // The owning prompt clears the reference only after its job has fully unwound. Keeping it registered
        // closes the window where another prompt could start while cancellation is still releasing session state.
        activeTurn.get()?.cancel()
    }

    private fun messageChunk(text: String): Event =
        Event.SessionUpdateEvent(SessionUpdate.AgentMessageChunk(ContentBlock.Text(text)))

    private fun toolKind(name: String): ToolKind = when (name) {
        "read" -> ToolKind.READ
        "write", "edit" -> ToolKind.EDIT
        "ls", "find", "grep" -> ToolKind.SEARCH
        "bash" -> ToolKind.EXECUTE
        else -> ToolKind.OTHER
    }
}
