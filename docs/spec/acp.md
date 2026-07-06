# ACP (Agent Client Protocol) — headless mode

Konductor can run **headless as an [ACP](https://agentclientprotocol.com) agent** over stdin/stdout
instead of drawing the Lanterna TUI. ACP is "LSP for coding agents": a JSON-RPC 2.0 protocol that lets
any ACP client (an editor such as Zed, another tool, or another Konductor instance) drive the agent.

> Unlike the rest of `docs/`, this feature is **partly implemented** — Phase A (an echo bridge) is live
> and tested. See the status table below and [burndown.md](../burndown.md) (ACP track).

## Run it

```bash
mvn -DskipTests package
java -jar target/konductor-0.1.0-SNAPSHOT.jar acp
```

The process speaks newline-delimited JSON-RPC on stdin/stdout and exits when the client disconnects
(stdin EOF). An ACP client (e.g. Zed) is configured to launch that `java -jar … acp` command as its
agent. For a dev run without packaging: `mvn -q exec:java -Dexec.args="acp"`.

**stdout is the protocol channel.** Nothing on the headless path may print to stdout. The ACP SDK logs
through kotlin-logging → SLF4J; we ship `slf4j-simple`, which writes to **stderr** by default, keeping
stdout clean.

## How it maps onto Konductor

The [ACP Kotlin SDK](https://github.com/agentclientprotocol/acp-kotlin-sdk) (`com.agentclientprotocol:acp-jvm`)
provides the JSON-RPC runtime and the `StdioTransport`. We implement two small seams in
[`com.konductor.acp`](../../src/main/kotlin/com/konductor/acp/KonductorAcpAgent.kt):

| ACP concept | Konductor | Notes |
|-------------|-----------|-------|
| `AgentSupport.initialize` | `KonductorAgentSupport` | advertises `AgentCapabilities` + protocol version |
| `AgentSupport.createSession` | → `EchoAgentSession` | one session per `session/new` |
| `AgentSession.prompt` → `Flow<Event>` | echo bridge | emits `SessionUpdate.AgentMessageChunk` then `PromptResponse(END_TURN)` |
| `StdioTransport` + `Protocol` | `runAcpAgent()` | `runBlocking` stays alive until the transport reaches `CLOSED`, then cancels children so the JVM exits |

The `runTurn`/`AgentEvent` mapping mirrors [architecture.md](architecture.md): Konductor's planned
`AgentEvent`s line up with ACP `session/update` variants (text → `agent_message_chunk`, tool calls →
`tool_call`/`tool_call_update`, plan → `plan`, usage → `usage_update`, completion → stop reason).

## Status

| Phase | Scope | State |
|-------|-------|-------|
| A | Transport + headless entry + echo bridge, validated end-to-end | **done** |
| B | Replace the echo bridge with the real `AgentLoop`/provider (depends on M1) | pending |
| C | `session/load`/list/resume ↔ `SessionStore`; `tool_call` + `session/request_permission` (M2/M3) | pending |
| D | ACP **client** role — drive another agent (instance-to-instance / sub-agents) | deferred |

## Validating manually

Pipe a minimal client handshake into the agent and inspect the streamed responses:

```
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":".","mcpServers":[]}}
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"konductor-1","prompt":[{"type":"text","text":"hello"}]}}
```

Expected: an `initialize` result, a `session/new` result with `sessionId`, a `session/update` notification
carrying an `agent_message_chunk` (`"Echo: hello"`), then a `session/prompt` result with
`stopReason: "end_turn"`. The prompt→event mapping is covered by
[`EchoAgentSessionTest`](../../src/test/kotlin/com/konductor/acp/EchoAgentSessionTest.kt).

## Dependency notes

- `com.agentclientprotocol:acp-jvm` — Kotlin Multiplatform library; Maven must use the **`-jvm`** artifact.
  It is built with **Kotlin 2.2.20**, which is why `pom.xml` pins `kotlin.version` to 2.2.x.
- Pulls `kotlinx-serialization`, `kotlinx-coroutines`, and `kotlinx-io` transitively.
- `slf4j-simple` is the logging backend (stderr) required by the SDK's `kotlin-logging` binding.

## Related docs

[index.md](../index.md) · [architecture.md](architecture.md) · [implementation-roadmap.md](../implementation-roadmap.md) ·
[burndown.md](../burndown.md) · [future.md](../future.md)
