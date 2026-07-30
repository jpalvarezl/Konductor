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

ACP startup is deliberately noninteractive and has no launch workspace. Before the transport opens, bootstrap may use
only CLI flags, the real process environment (`System.getenv`, with no launch-cwd `.env` overlay), and global settings.
It never reads launch-cwd project settings. Process startup resolves effective agent kind before applying model rules.
Prompt mode must find a non-blank process-default model among those sources; it never lists deployments, auto-selects,
or opens the TUI bootstrap selector. For Prompt only, the bootstrap value retains the CLI, process-environment, and
global model layers separately so a later `session/new` trusted-project model can outrank global but not
explicit/process input. A missing Prompt process default is a sanitized configuration error on stderr and a non-zero
exit with no protocol, provider, or session. A configured PromptAgent still requires that default in this slice.

Hosted mode bypasses client model resolution and inventory and requires no process-default model. Once process startup
resolves Hosted kind, explicit `--model` is a localized CLI conflict reported before the transport opens. Ambient model
environment/global values are tolerated but ignored. A Hosted `session/new` also ignores a permitted trusted-project
model, does not run Prompt model-source precedence or provenance resolution, and persists canonical `hosted`. Loaded
valid Hosted bindings accept and preserve either that placeholder or a legacy non-blank model value as opaque
compatibility metadata; they never use it as a target or silently rewrite it.

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

`ConfigurationAcpSessionRuntimeFactory` is the ACP ownership boundary. Main gives it the process-scoped
`FoundryProjectRuntime` only after safe process bootstrap has produced the process-scoped endpoint, credential, and
agent kind. For Prompt, bootstrap also supplies the separated model-source layers required by `session/new`; unlike the
TUI, Prompt ACP cannot wait for a future request cwd to supply the only process default. Hosted supplies no model-source
precedence state. A project file from the process launch cwd is never read, retained, or applied to an ACP session.
Per-session settings cannot replace process-scoped endpoint/credential or agent kind.

For `session/new`, the factory first validates the request cwd, resolves its #26 identity/trust, and reads only permitted
project settings. For Prompt only, it then finalizes a non-blank effective model using `--model` > real process
`FOUNDRY_MODEL_NAME` > trusted per-session `provider.model` > global `provider.model`. Keeping Prompt source provenance
is required: a flattened global process default must not incorrectly suppress the higher-precedence trusted project
value. Prompt resolution returns `value` plus one process-local tag: `CLI`, `PROCESS_ENVIRONMENT`,
`TRUSTED_SESSION_PROJECT`, or `GLOBAL`. Only `value` configures the Prompt provider/context and is persisted unchanged
in the new Prompt header; the tag is not persisted. For Hosted, the explicit CLI source has already been rejected at
process startup, all ambient model sources are ignored, and finalization supplies canonical `hosted` to the shared
non-null configuration shape and new Hosted header without producing a provenance tag.

Workspace/trust/settings resolution, effective-configuration finalization, and request-local runtime preparation all
precede header commit. If `session/new` returns an error, the factory closes any prepared runtime and leaves no session
visible to `session/list` or loadable by `session/load`; an implementation may stage/atomically commit or remove a
just-created file, but may not leak an orphan. Hosted remote creation stays lazy until first prompt, so an errored
`session/new` also leaves no remote service resource.

The factory asks shared project composition for a fresh session `ProviderRuntime`, environment preamble, and (when
supported) cwd-bound tool registry and `ToolContext`. Deployment/connection catalogs remain typed composition services
and are not ACP fields. Sessions cannot reuse another workspace's containment root, trust result, project settings,
provider binding, closeable OpenAI client, or server session state.

For `session/load`, strict header decoding derives persisted kind before workspace trust or project settings. Prompt v1
is Prompt and only a valid complete Hosted v2 binding is Hosted. If that kind differs from the process-scoped effective
agent kind, Prompt-over-Hosted and Hosted-over-Prompt both return a sanitized method-level JSON-RPC error. There is no
provider switch, migration, fallback, or rewrite; discovery, project merge, provider/runtime creation, service calls,
and writes remain untouched. A kind-matched session then resolves trust from its persisted cwd and rebuilds there,
never from process launch cwd or a caller-supplied replacement.

For a kind-matched Prompt load, persisted non-blank `modelName` is authoritative and no process/global/project source is
a fallback. A missing/blank model or otherwise corrupt header returns a sanitized method-level JSON-RPC error while the
transport and other sessions remain usable, with no discovery, runtime, mutation, or canonicalization. Kind-matched
Hosted loads apply the same corrupt-model rejection while accepting and preserving any non-blank compatibility value
described above.

ACP passes the configured compaction settings unchanged to `AgentLoop`; shared provider capabilities disable Hosted
compaction, remove local tool declarations/execution, and apply server-owned-history request semantics without an ACP
`AgentKind` branch. `session/new` reserves a Hosted v2 binding and leaves remote creation lazy until the first prompt;
`session/load` reconnects and validates the exact persisted binding before returning. Factory/connection close detaches
durable bindings without deleting them.

## Supported ACP methods

The inventory of JSON-RPC methods the agent implements (the `session/*` family plus `initialize`). The CLI entry
point is `java -jar … acp` (see [Run it](#run-it)); everything else in the ACP surface is either deferred (see
[Status](#status)) or belongs to the client role (Phase D).

| Method | Purpose |
|--------|---------|
| `initialize` | Handshake; advertises the protocol version + capabilities (`loadSession`, `sessionCapabilities.list`). |
| `session/new` | Resolve cwd trust/settings and prepare runtime before committing a header; Prompt finalizes Prompt-only model provenance, while Hosted ignores ambient model sources and writes canonical `hosted`. Returns a persisted Konductor UUID with no local/remote orphan on failure; Hosted reserves that UUID as its lazy server binding. |
| `session/load` | Resume by `sessionId` from its persisted cwd; cross-kind or corrupt/missing/blank model metadata is a method-level protocol error with no migration/fallback/discovery/rewrite. Matching Hosted reconnects and validates its exact server binding before success. |
| `session/list` | List saved sessions for the client `cwd` (id, title, `updatedAt`). |
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
