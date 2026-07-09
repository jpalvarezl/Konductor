# ACP (Agent Client Protocol) — headless mode

Konductor can run **headless as an [ACP](https://agentclientprotocol.com) agent** over stdin/stdout
instead of drawing the Lanterna TUI. ACP is "LSP for coding agents": a JSON-RPC 2.0 protocol that lets
any ACP client (an editor such as Zed, another tool, or another Konductor instance) drive the agent.

> Unlike the rest of `docs/`, this feature is **partly implemented** — Phase B (real single-turn inference
> via the M1 `AgentLoop`) is live and tested. See the status table below and [burndown.md](../burndown.md) (ACP track).

## Two roles: agent vs. client

ACP is bidirectional, and Konductor's phases split across **two roles** — a distinction worth keeping straight:

- **Agent role (the focus — Phases A–C).** Konductor *receives* ACP over stdin and is driven by a client (an
  editor like Zed, another tool, or another Konductor instance). Being a complete, spec-compliant *agent* here is
  the **primary motivation** for integrating ACP. Phases A/B are done (streamed turns); **Phase C is the rest of
  being a well-behaved agent** — tool-call visibility, permission prompts, and session load/list — i.e. **core
  agent-role compliance, not optional polish**.
- **Client role (deferred — Phase D).** The mirror image: a headless Konductor *acts as* the ACP client and
  drives *another* agent. That's **agent orchestration / sub-agents**, detailed in
  [future.md](../future.md#agent-orchestration).

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
| `AgentSupport.createSession` | → `KonductorAgentSession` | one session per `session/new`, each with its own `AgentLoop` |
| `AgentSession.prompt` → `Flow<Event>` | real `AgentLoop` turn | maps `AgentEvent`s → `SessionUpdate.AgentMessageChunk` (assistant text) then `PromptResponse(END_TURN)` |
| `StdioTransport` + `Protocol` | `runAcpAgent()` | `runBlocking` stays alive until the transport reaches `CLOSED`, then cancels children so the JVM exits |

The `runTurn`/`AgentEvent` mapping mirrors [architecture.md](architecture.md): Konductor's planned
`AgentEvent`s line up with ACP `session/update` variants (text → `agent_message_chunk`, tool calls →
`tool_call`/`tool_call_update`, plan → `plan`, usage → `usage_update`, completion → stop reason).

## Status

| Phase | Scope | State |
|-------|-------|-------|
| A | Transport + headless entry + echo bridge, validated end-to-end | **done** |
| B | Real `AgentLoop`/provider single-turn inference (text → `agent_message_chunk` + `end_turn`); depends on M1 | **done** |
| C | `session/load`+list ↔ `SessionStore`, `tool_call` updates, `session/cancel`; `session/request_permission` (permissions) deferred | **mostly done** |
| D | ACP **client** role — drive another agent (orchestration / sub-agents, see [future.md](../future.md#agent-orchestration)) | deferred |

> Phase B covers M1's scope: assistant **text** + stop reason. `tool_call`/`plan`/`usage` `session/update`s and
> `session/cancel` wiring ride on later milestones (M2+), matching the events the loop emits.

## Validating manually

Pipe a minimal client handshake into the agent and inspect the streamed responses:

```
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{}}}
{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":".","mcpServers":[]}}
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"<sessionId from the session/new result>","prompt":[{"type":"text","text":"hello"}]}}
```

Phase C keys ACP `SessionId` to the Konductor session UUID, so substitute the `sessionId` returned by the
`session/new` result into the `session/prompt` call (it is no longer a fixed literal).

Expected: an `initialize` result, a `session/new` result with `sessionId`, a `session/update` notification
carrying an `agent_message_chunk` (the model's answer), then a `session/prompt` result with
`stopReason: "end_turn"`. Requires `FOUNDRY_PROJECT_ENDPOINT` + `FOUNDRY_MODEL_NAME` and `az login` (real
inference). The prompt→event mapping is covered offline by
[`KonductorAgentSessionTest`](../../src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt).

## Dependency notes

- `com.agentclientprotocol:acp-jvm` — Kotlin Multiplatform library; Maven must use the **`-jvm`** artifact.
  It is built with Kotlin, and `pom.xml` pins `kotlin.version` to **2.4.0**.
- Pulls `kotlinx-serialization`, `kotlinx-coroutines`, and `kotlinx-io` transitively.
- `slf4j-simple` is the logging backend (stderr) required by the SDK's `kotlin-logging` binding.

## Related docs

[index.md](../index.md) · [architecture.md](architecture.md) · [implementation-roadmap.md](../implementation-roadmap.md) ·
[burndown.md](../burndown.md) · [future.md](../future.md)
