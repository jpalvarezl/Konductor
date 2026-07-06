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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicLong

/**
 * Headless [Agent Client Protocol](https://agentclientprotocol.com) entry point.
 *
 * Runs Konductor as an ACP *agent* over stdin/stdout (JSON-RPC 2.0) instead of the Lanterna TUI, so any
 * ACP client — an editor such as Zed, another tool, or (later) another Konductor instance — can drive it.
 *
 * `runBlocking` stays alive until the transport's read/write coroutines finish, i.e. until stdin reaches
 * EOF or the client disconnects.
 *
 * NOTE: stdout is the JSON-RPC channel in this mode. Nothing on this path may print to stdout; diagnostic
 * logging must go to stderr or a file.
 */
fun runAcpAgent(): Unit = runBlocking {
    @Suppress("DEPRECATION")
    val transport = StdioTransport(
        parentScope = this,
        ioDispatcher = Dispatchers.IO,
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    val protocol = Protocol(this, transport)
    Agent(protocol, KonductorAgentSupport())
    protocol.start()

    // Stay alive until the client disconnects (stdin EOF closes the transport). Once closed, cancel the
    // lingering protocol coroutines so runBlocking can return and the JVM exits cleanly.
    transport.state.first { it == Transport.State.CLOSED }
    coroutineContext.cancelChildren()
}

/**
 * Wires ACP requests to Konductor. For now every session is an [EchoAgentSession]; Phase B replaces this
 * with the real AgentLoop/provider once single-turn inference lands (see docs/burndown.md — ACP track).
 */
private class KonductorAgentSupport : AgentSupport {
    private val sessionCounter = AtomicLong(0)

    override suspend fun initialize(clientInfo: ClientInfo): AgentInfo =
        AgentInfo(
            protocolVersion = LATEST_PROTOCOL_VERSION,
            capabilities = AgentCapabilities(),
        )

    override suspend fun createSession(sessionParameters: SessionCreationParameters): AgentSession =
        EchoAgentSession(SessionId("konductor-${sessionCounter.incrementAndGet()}"))
}

/**
 * Placeholder session: streams the user's text back as a single agent message chunk, then ends the turn.
 * This is the Phase A bridge that stands in for the not-yet-built agent loop.
 */
internal class EchoAgentSession(override val sessionId: SessionId) : AgentSession {
    override suspend fun prompt(content: List<ContentBlock>, _meta: JsonElement?): Flow<Event> = flow {
        val userText = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        emit(
            Event.SessionUpdateEvent(
                SessionUpdate.AgentMessageChunk(ContentBlock.Text("Echo: $userText")),
            ),
        )
        emit(Event.PromptResponseEvent(PromptResponse(StopReason.END_TURN)))
    }
}
