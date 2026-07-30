# ACP (Agent Client Protocol) — headless mode

Konductor can run **headless as an [ACP](https://agentclientprotocol.com) agent** over stdin/stdout
instead of drawing the Lanterna TUI. ACP is "LSP for coding agents": a JSON-RPC 2.0 protocol that lets
any ACP client (an editor such as Zed, another tool, or another Konductor instance) drive the agent.

> Unlike the rest of `docs/`, this feature is **partly implemented** — Phases A/B are done and most of Phase C is
> live: persisted load/list, streamed tool calls and hosted logs, and cancellation. Permissions, usage/compaction
> updates, replay-on-load, and a golden protocol test remain. These unscheduled follow-ups live in
> [future.md](../future.md#acp-agent-role-completion).

## Two roles: agent vs. client

ACP is bidirectional, and Konductor's phases split across **two roles** — a distinction worth keeping straight:

- **Agent role (the focus — Phases A–C).** Konductor *receives* ACP over stdin and is driven by a client (an
  editor like Zed, another tool, or another Konductor instance). Being a complete, spec-compliant *agent* here is
  the **primary motivation** for integrating ACP. Phases A/B and most Phase C plumbing are done; permission prompts
  and richer protocol observability remain **core agent-role compliance, not optional polish**.
- **Client role (deferred — Phase D).** The mirror image: a headless Konductor *acts as* the ACP client and
  drives *another* agent. That's **agent orchestration / sub-agents**, detailed in
  [future.md](../future.md#agent-orchestration).

## Run it

```bash
mvn -DskipTests package
java -jar target/konductor-0.2.0.jar acp
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
| `AgentSupport.createSession` | → `KonductorAgentSession` | one session per `session/new`, each with its own provider, cwd-bound context/tools, and `AgentLoop` |
| `AgentSession.prompt` → `Flow<Event>` | real `AgentLoop` turn | maps text, tool calls/results, and hosted logs to updates, then `PromptResponse(END_TURN/CANCELLED)` |
| `StdioTransport` + `Protocol` | `runAcpAgent()` | `runBlocking` stays alive until the transport reaches `CLOSED`, then cancels children so the JVM exits |

The `runTurn`/`AgentEvent` mapping mirrors [architecture.md](architecture.md): text maps to
`agent_message_chunk`, tool activity to `tool_call`/`tool_call_update`, hosted logs to prefixed message chunks, and
completion/cancellation to a stop reason. Plan, usage, and compaction-specific updates are not mapped yet. ACP does not
advertise `/compact` or `/model` commands; those are TUI controls rather than protocol methods.

`ConfigurationAcpSessionRuntimeFactory` is the ACP ownership boundary. It retains operator bootstrap inputs (CLI,
real process environment, user home, and the unresolved config-directory selection) separately from any project
dotenv. For each new or loaded session it first canonicalizes the authoritative session cwd/root and config directory,
rejects a config directory inside that workspace, and only then reads global settings and trust. It conditionally reads
that cwd's project `.env` and `.konductor/settings.json`, then constructs a fresh effective configuration, Foundry
runtime, provider, context, and (when supported) cwd-bound tool registry and `ToolContext`. ACP exposes no
project-discovery protocol fields and never shares another session's containment root, provider binding, closeable
OpenAI client, or server state.

The config directory and strict trust-store behavior are defined in
[configuration.md](configuration.md#workspace-identity-and-trust-store). ACP never prompts and never writes trust.
Unknown, unreadable, malformed, or unsupported trust state behaves as untrusted. In that state ACP does not open either
project configuration file; real process environment, CLI/global inputs, built-ins, and plain-text context remain
eligible. Trusted sessions read both project files. Context discovery uses the same canonical session cwd for every
trust state unless the process was started with `--no-context-files`, which omits only context files.

`session/new` allocates its UUID and header candidate in memory, but does not durably create the session yet. For the
canonical client cwd it must first complete every local preparation step: trust and eligible-source resolution, final
configuration validation, credential/Foundry runtime and provider construction, PromptAgent/Hosted binding-shape
validation, context assembly, and supported cwd-bound tool-runtime construction. Only after those steps succeed does it
write the header and publish the ACP session:

- all sessions: UUID, canonical cwd, creation time, optional name, and the effective model;
- Prompt v1: the effective persisted PromptAgent name, or no field for ephemeral Prompt; or
- Hosted v2: the effective hosted-agent name plus a reserved server session id equal to the local UUID.

The Prompt-v1 versus Hosted-v2 header shape fixes the provider/history kind for that session. Any local preparation or
header-write failure closes the provisional provider/runtime and leaves no header or active ACP session. A pre-session
failure is returned as the `session/new` JSON-RPC request error; there is no session update or out-of-band ACP diagnostic
before a session exists. Remote Hosted session creation is deliberately excluded from local preparation and remains
lazy until the first prompt after the local binding is durably reserved.

Unlike TUI startup `--resume`, which rejects a session from another canonical launch cwd before constructing its
single cwd-bound runtime ([sessions.md](sessions.md#tui-startup-workspace-binding)), `session/load` ignores the caller's
and process launch cwd and uses the header's persisted cwd. UUID, cwd, creation time, name, transcript,
model, PromptAgent name (including an absent name for an ephemeral Prompt session), and Hosted binding are persisted
facts; current project config cannot silently replace them or convert a Prompt transcript into Hosted history (or vice
versa). Loading is explicitly two-phase:

1. **Fresh-source phase.** Canonicalize the persisted cwd and bootstrap config directory; resolve trust; safely read and
   parse only eligible global/project/environment/CLI sources into a partial candidate. Apply source precedence to
   runtime-only values, but do not apply defaults to, require, cross-validate, or construct a provider from the fresh
   model, agent kind, PromptAgent name, or Hosted-agent name. Syntax/type errors in an eligible source still fail.
2. **Persisted-overlay and finalization phase.** Overlay the exact header model, the provider/history kind implied by
   the Prompt-v1 or Hosted-v2 header shape, and the complete persisted Prompt/Hosted binding. An absent v1
   `promptAgentName` explicitly overlays to ephemeral rather than falling back to a newly configured agent. Only then
   apply runtime defaults, perform complete cross-field and required-value validation, create the credential/Foundry
   project runtime and provider, assemble context/tools, and for Hosted reconnect to the exact binding.

Fresh sources still provide endpoint, credential inputs, system prompt override/append, context/environment,
temperature, tool policy/limits, max iterations, compaction settings, Hosted container/runtime inputs, and other
runtime-only values. Process CLI overrides and real environment are reapplied to those fields. A conflicting or absent
fresh model/kind/agent name is not an early error and cannot replace or veto the persisted identity; internally
inconsistent header fields, malformed eligible sources, missing final runtime requirements, or an unusable persisted
binding do fail. These failures are returned as `session/load` JSON-RPC request errors, not as an undefined diagnostic
channel. Final validation here is local schema/required/cross-field validation; ACP has no deployment-catalog protocol
surface, so load does not add a live discovery prerequisite for the persisted Prompt model. PromptAgent resume remains
name-scoped: legacy and newly created versions retain baked base + configured append, and the current session contributes
only context files + environment dynamically. It does not inspect or classify version metadata. A loaded Hosted session
validates its exact persisted binding in the finally resolved Foundry project before load succeeds. This
persisted-versus-refreshed split keeps conversation identity stable while allowing runtime operator policy and trusted
dynamic project context to change or disappear between runs without replacing persisted conversation identity.
Shared provider capabilities disable Hosted compaction, remove local tool declarations/execution, and apply
server-owned-history semantics without an ACP frontend branch. Factory/connection close detaches durable bindings
without deleting them.

## Supported ACP methods

The inventory of JSON-RPC methods the agent implements (the `session/*` family plus `initialize`). The CLI entry
point is `java -jar … acp` (see [Run it](#run-it)); everything else in the ACP surface is either deferred (see
[Status](#status)) or belongs to the client role (Phase D).

| Method | Purpose |
|--------|---------|
| `initialize` | Handshake; advertises the protocol version + capabilities (`loadSession`, `sessionCapabilities.list`). |
| `session/new` | Canonicalize the client `cwd`; fully validate/build the local runtime, provider, binding shape, context, and tools; then persist a cwd/model/agent-bound Konductor UUID. Hosted reserves that UUID but creates the remote session lazily on first prompt. |
| `session/load` | Resume by `sessionId`; parse eligible fresh sources from the persisted cwd into a partial candidate, overlay header model/kind/binding, then finally validate/build and reconnect Hosted's exact server binding. |
| `session/list` | Canonicalize the client `cwd`, enforce the same outside-workspace config-dir check, then list its saved sessions (id, title, `updatedAt`). |
| `session/prompt` | Run one Prompt turn; streams `agent_message_chunk` + `tool_call`/`tool_call_update`, ending with a `stopReason` (`end_turn` or `cancelled`). A second prompt collected for the same session while one is active is rejected, not queued. |
| `session/cancel` | Cancel the sole in-flight turn for a session; the active target remains registered until its job fully unwinds. |

Deferred: `session/request_permission` (permission prompts) and the ACP **client** role (Phase D).

## Status

| Phase | Scope | State |
|-------|-------|-------|
| A | Transport + headless entry + echo bridge, validated end-to-end | **done** |
| B | Real Foundry Responses turn (text → `agent_message_chunk` + `end_turn`); depends on M1 | **done** |
| C | `session/load`+list ↔ `SessionStore`, `tool_call` updates, `session/cancel`; `session/request_permission` (permissions) deferred | **mostly done** |
| D | ACP **client** role — drive another agent (orchestration / sub-agents, see [future.md](../future.md#agent-orchestration)) | deferred |

> Implemented Phase C events cover tools, hosted logs, and cancellation. Plan/usage/compaction updates,
> session-history replay, and permissions remain follow-ups.

### Overlap and cancellation policy

ACP uses the same **reject while busy** behavior as the TUI, whose prompt input is inert during a turn. Each
`KonductorAgentSession` permits one collected `session/prompt` flow at a time; a concurrent prompt fails with
`A turn is already in progress for this session.` It is not queued, because delayed prompts would run against
newer history than the client saw when submitting them. Loading the same local session twice in one ACP connection is
also rejected so two runtimes cannot concurrently mutate one local session. Cross-process concurrent use of one JSONL
session remains unsupported.

`session/cancel` always targets the one active prompt, and a replacement prompt cannot register until cancellation has
completely released the session. For Hosted execution, cancellation closes local invoke/log collection but preserves
the durable binding; provider/runtime/connection close detaches it without deletion. The server may already have
advanced, so the local audit transcript can end at its user entry while a later prompt or loaded runtime continues from
service-authoritative context.

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
Foundry Responses calls). The prompt→event mapping is covered offline by
[`KonductorAgentSessionTest`](../../src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt).

## Dependency notes

- `com.agentclientprotocol:acp-jvm` — Kotlin Multiplatform library; Maven must use the **`-jvm`** artifact.
  It is built with Kotlin, and `pom.xml` pins `kotlin.version` to **2.4.0**.
- Pulls `kotlinx-serialization`, `kotlinx-coroutines`, and `kotlinx-io` transitively.
- `slf4j-simple` is the logging backend (stderr) required by the SDK's `kotlin-logging` binding.

## Related docs

[index.md](../index.md) · [iterations](../iterations/index.md) · [architecture.md](architecture.md) ·
[implementation-roadmap.md](../implementation-roadmap.md) · [future.md](../future.md)
