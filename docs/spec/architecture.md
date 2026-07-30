# Architecture

This is the **keystone** document. It defines the layers, the domain model, and the core abstractions
(`AgentProvider`, `AgentEvent`, `AgentContext`, `Session`) that every other doc builds on. Use
[index.md](../index.md) to route to the right spec or delivery packet.

> **Code blocks are illustrative design sketches, not exact committed implementation.** Much of this architecture
> now exists in `src/`, but names and mechanics can differ. Use the issue-linked
> [delivery packet registry](../iterations/index.md) to locate scoped implementation context and verify behavior in
> source/tests.

## Goals & non-goals

**Goals**

- Dog-food `azure-ai-agents` / `azure-ai-projects` (v2) by building a genuinely useful local coding agent.
- Treat Azure AI Foundry as the explicit and only service platform; deepen project, agent, response, memory, tool, and
  evaluation integration instead of designing for backend portability.
- A clean **provider seam** so different Foundry **agent kinds** plug in behind one interface — starting with
  **Prompt** and **Hosted**.
- **Client-owned** conversation history with **client-side compaction** for the Prompt provider.
- Keep the existing Lanterna TUI; replace only the `ConversationController` seam.
- Keep the agent loop **frontend-agnostic**: one core powers both the interactive TUI and a **headless** ACP
  frontend ([acp.md](acp.md)).

**Non-goals:** alternate inference vendors, a generic backend/provider registry, or raw Azure SDK models in TUI/ACP,
persistence, and pure orchestration code. Deferred Foundry/product surfaces—Workflow/External agent kinds,
server-side Conversations and Memory Stores, session branching, server-side tools, MCP, sub-agents, and
themes/packages—are tracked in [future.md](../future.md).

## System layers

```
┌───────────────────────────────┐   ┌───────────────────────────────┐
│ Interactive frontend — TUI    │   │ Headless frontend — ACP       │   two frontends,
│ Lanterna: transcript · status │   │ JSON-RPC over stdio; an ACP   │   one shared core
│ bar · composer                │   │ client (e.g. Zed) drives it   │
└───────────────┬───────────────┘   └───────────────┬───────────────┘
    AgentEvent ▲ │ input /              AgentEvent ▲ │ session/prompt ·
    (rendered)   ▼ slash-commands       (→ updates)  ▼ session/cancel
┌────────────────┴───────────────────────────────────┴──────────────┐
│ Agent loop  (coroutines)  — orchestrates one turn; frontend-agnostic│
│   · assembles TurnRequest from Session + AgentContext              │
│   · asks the provider to run the turn; relays AgentEvents          │
│   · executes tool calls via ToolExecutor                           │
│   · persists entries; triggers compaction when needed              │
└────────────────▲───────────────────────────────────┬──────────────┘
   AgentEvent     │                                   │ TurnRequest + ToolExecutor
                  │                                   ▼
┌─────────────────┴──────────────────────────────────────────────────┐
│ FoundryProjectRuntime (typed catalogs + provider composition)      │
│   └─ ProviderRuntime (capabilities + optional management)          │
│      └─ AgentProvider (loop-ownership seam)                         │
│         ├─ PromptProvider — owns client loop; app-domain types      │
│         │  └─ FoundryResponsesClient — one Foundry Responses call  │
│         └─ HostedProvider — server-owned loop (container)          │
└─────────────────▲──────────────────────────────────┬───────────────┘
                  │                                   │ HTTPS
┌─────────────────┴──────────────────────────────────▼───────────────┐
│ Azure SDKs: azure-ai-agents · azure-ai-projects                    │
│   Foundry Responses adapters · Hosted SDK seams                    │
│   OpenAI client · Agents/Sessions                                  │
└────────────────────────────────────────────────────────────────────┘

Cross-cutting services: SessionStore (JSONL) · WorkspaceResolver/ContextFileLoader · WorkspaceTrustCoordinator ·
Config · Compactor · ToolRegistry · ContextWindowTracker · AppStrings
```

Each layer depends only on the layer below and on the domain model. **Frontends** turn user/client input into
agent-loop submissions and render the resulting `AgentEvent`s; they never talk to the SDK directly, and neither the
agent loop nor any provider touches a frontend (Lanterna or ACP). The TUI's project-discovery routes are explicit
siblings to the SDK-free deployment/connection catalogs composed by `FoundryProjectRuntime`; model mutation still goes
through `AgentLoop`. ACP has no corresponding discovery protocol surface. Because the agent loop is
**frontend-agnostic**, the interactive TUI and the **headless** ACP frontend share one core.

`FoundryProjectRuntime` is the composition boundary for one resolved project endpoint and credential, not another
orchestration or backend abstraction. It owns the finite construction policy for typed deployment/connection catalogs
plus providers. Main owns one for the cwd-bound TUI; after trust and eligible-source parsing, an unresolved Prompt TUI
may construct its catalog side before final model/provider construction. ACP instead constructs a session-owned project
runtime after resolving that session's authoritative cwd and configuration; it must not reuse launch-cwd project
composition across workspaces. On load, fresh eligible sources are parsed to a partial candidate, persisted header
model/kind/binding is overlaid, and only the resulting final configuration is validated and passed to provider
construction. Azure SDK client/model types remain inside the Foundry composition and adapters.

### Startup composition

Process/application bootstrap first resolves `--config-dir` > real-process `KONDUCTOR_CONFIG_DIR` > default, captures
one-run trust/context controls, and retains real process environment separately from project dotenv. The TUI then:

1. canonicalizes its launch cwd and uses header-only inspection to resolve `--resume`/`--continue` against that exact
   cwd before trust-gated project configuration, credentials, project runtime, provider, context, or tools exist;
2. resolves trust and eligible configuration for that cwd, then rejects a selected session whose strict persisted kind
   differs from the effective TUI kind;
3. for a kind-matched resumed Prompt, uses its persisted non-blank model; for a new unresolved Prompt, queries
   `ModelDeployment` inventory and completes the zero/one/many pre-provider selection flow; Hosted bypasses discovery
   and uses the canonical `hosted` placeholder for a new header; and
4. finalizes the non-blank configuration and validates/builds provider, binding, path-bearing context, and tools; a
   fresh Prompt candidate already contains the same effective normalized PromptAgent name as its provisional runtime,
   then one `SessionStore.persistNew` commit publishes the complete session/runtime/status graph.

The selector admits at most the first 2,000 canonical options and visibly reports truncation while active. Cancellation,
zero inventory, and discovery failure create no provider/session or selection state. A later local-validation failure
closes any provisional runtime and publishes no session. Terminal zero/failure guidance is emitted after terminal
restoration. `--continue` does not skip an opposite-kind newest session, and this slice performs no kind migration.

ACP intentionally differs. `session/new` allocates a candidate, resolves the requested canonical cwd's trust and
eligible sources, then finalizes Prompt model precedence as CLI > real process environment > trusted cwd dotenv >
trusted project settings > global settings. An explicit process-level Hosted selection plus `--model` fails before
transport; session-cwd-selected Hosted rejects explicit `--model` during `session/new`, ignores ambient model sources,
and uses canonical `hosted`. Complete runtime/provider/binding/context/tool validation precedes one `persistNew`;
Hosted remote creation remains lazy, and no
failure path creates then deletes a header. `session/load` first obtains the authoritative persisted cwd, parses
eligible fresh sources without defaulting or validating identity fields, overlays exact persisted model/kind/binding,
and only then validates and builds. It does not compare persisted kind with a process-scoped kind or discover/fallback
a missing model. See [configuration.md](configuration.md#startup-model-resolution),
[sessions.md](sessions.md#tui-startup-workspace-binding), and [acp.md](acp.md#how-it-maps-onto-konductor).

### Frontends: interactive TUI and headless ACP

Konductor drives the same agent loop through two interchangeable frontends:

- **Interactive TUI** (default) — the Lanterna full-screen app (transcript · status bar · composer); see
  [tui.md](tui.md).
- **Headless mode** — no TUI. Konductor speaks the [Agent Client Protocol](https://agentclientprotocol.com) (ACP)
  as an *agent* over stdin/stdout (JSON-RPC), so an external ACP client — an editor such as Zed, another tool, or
  another Konductor instance — drives it. Selected with the `acp` argument; design + status in [acp.md](acp.md).

Both submit input to the agent loop and render its `AgentEvent` stream (the TUI to the screen; the headless
frontend as ACP `session/update` notifications plus a stop reason). Keeping the loop and provider layers
**frontend-agnostic** is what makes this possible.

### Localized presentation

Human-facing TUI and CLI copy is a frontend concern. `i18n/AppStrings` loads a JVM `ResourceBundle` and exposes semantic
formatting methods to the interactive frontend; core domain types retain stable identities rather than localized display
labels. `MessageRole`, command names, tool names and schemas, persisted values, model prompts, raw tool results, hosted
logs, and ACP protocol fields do not vary with locale.

This boundary prevents translated presentation text from changing model behavior, persistence compatibility, scripts,
or protocol interoperability. Low-level exceptions remain diagnostic input; the frontend localizes its explanatory
wrapper. Provider progress that needs presentation crosses the boundary as structured data (for example,
`AgentEvent.Retrying`) rather than preformatted prose. ACP output remains protocol-stable until client-locale negotiation
is specified.

## Core domain model

The conversation is an ordered list of **entries**. Entries are the unit of persistence
([sessions.md](sessions.md)) and the input to compaction ([compaction.md](compaction.md)).

```kotlin
sealed interface Entry {
    val id: String            // ULID/UUID
    val parentId: String?     // previous entry; enables a linear (later: branched) history
    val timestamp: Instant
}

data class UserEntry(override val id: String, override val parentId: String?,
                     override val timestamp: Instant, val text: String) : Entry

data class AssistantEntry(override val id: String, override val parentId: String?,
                          override val timestamp: Instant,
                          val text: String,
                          val toolCalls: List<ToolCall> = emptyList(),
                          val usage: Usage? = null) : Entry

data class ToolCallEntry(override val id: String, override val parentId: String?,
                         override val timestamp: Instant, val call: ToolCall) : Entry

data class ToolResultEntry(override val id: String, override val parentId: String?,
                           override val timestamp: Instant,
                           val result: ToolResult) : Entry

data class CompactionEntry(override val id: String, override val parentId: String?,
                           override val timestamp: Instant,
                           val summary: String, val firstKeptEntryId: String,
                           val tokensBefore: Int) : Entry
```

```kotlin
data class ToolCall(val callId: String, val name: String, val argumentsJson: String)
data class ToolResult(val callId: String, val output: String, val isError: Boolean = false, val truncatedBytes: Int = 0)
data class Usage(val inputTokens: Int, val outputTokens: Int, val totalTokens: Int)
```

A `Session` is the entries plus metadata; see [sessions.md](sessions.md) for the on-disk JSONL schema.

```kotlin
data class Session(
    val id: String,
    var name: String?,
    val cwd: Path,
    val model: String,
    val entries: MutableList<Entry> = mutableListOf(),
)
```

## AgentContext (the preamble)

Everything the model sees *before* the transcript. The agent-context layer
([agent-context.md](agent-context.md)) loads ordered `(canonical display path, normalized content)` records, renders one
shared path-bearing context block, and combines it with the stable prompt, environment, and tool registry. Context
loading is independent of project-configuration trust.

```kotlin
data class AgentContext(
    val systemPrompt: String,          // stable prompt + rendered path-bearing context block + environment
    val tools: List<ToolSpec>,         // tool name + JSON-schema parameters
    val model: String,
    val temperature: Double? = null,
)

data class ToolSpec(val name: String, val description: String, val parametersSchema: JsonObject)
```

## The `AgentProvider` seam

The central abstraction. A provider **runs one turn to completion**, emitting a stream of `AgentEvent`s and
delegating tool execution back to the harness through a `ToolExecutor`. This single shape accommodates **both**
execution models:

- **Prompt** — the provider internally loops: call `FoundryResponsesClient` → if the response contains function
  calls, invoke `ToolExecutor` for each, feed the outputs back, call again — until the model returns a final answer.
- **Hosted** — the provider opens the server session's response/log stream and relays it; the container owns the
  loop. (If a hosted protocol surfaces client-side tool calls, it uses the same `ToolExecutor`.)

```kotlin
interface AgentProvider {
    val kind: AgentKind                                  // Prompt | Hosted identity
    val capabilities: ProviderCapabilities               // behavioral enforcement contract
    fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent>
    suspend fun close()
}

data class TurnRequest(
    val context: AgentContext,
    val history: List<Entry>, // full Prompt history, or only the current Hosted user entry
)

fun interface ToolExecutor { suspend fun execute(call: ToolCall): ToolResult }

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent           // streamed assistant text
    data class Retrying(val reason: String?, val retryAttempt: Int,
                        val maxRetries: Int, val delayMs: Long) : AgentEvent
    data class ToolCallStarted(val call: ToolCall) : AgentEvent
    data class ToolCallCompleted(val call: ToolCall, val result: ToolResult) : AgentEvent
    data class LogFrame(val line: String) : AgentEvent            // Hosted session logs
    data class UsageReported(val usage: Usage) : AgentEvent
    data class TurnCompleted(val assistant: AssistantEntry) : AgentEvent
    data class Failed(val error: Throwable) : AgentEvent
}
```

Why loop ownership lives *inside* the provider: the two SDKs drive their loops differently (client-side re-request
vs. server stream). Putting the loop behind `runTurn` keeps the agent-loop layer and the TUI identical for both kinds.
Prompt delegates tool execution to cwd-scoped local tools; Hosted declares that local tools are unavailable because its
container owns the tool surface. See [providers.md](providers.md) and [hosted-agents.md](hosted-agents.md).

### Provider capabilities and runtime

`ProviderFactory` returns a `ProviderRuntime`: the provider, its explicit `ProviderCapabilities`, a sealed optional
management surface, and the Hosted-only session lifecycle implemented by the Hosted provider. Capabilities cover client
compaction, client model switching, local tools, PromptAgent management, and `Client` versus `Server` session-history
ownership. `AgentKind` remains the configuration/factory discriminator and provider identity; TUI and ACP do not infer
behavior from it. `AgentLoop` reserves and activates persisted Hosted bindings through that lifecycle before recording a
turn; `TurnRequest` cannot adopt an arbitrary server-session id.

`AgentLoop` is the shared enforcement boundary. It disables auto-compaction when unsupported, returns typed outcomes
for manual compaction/model switching, strips local tool declarations and uses a rejecting executor when local tools
are unavailable, and sends only the current user entry when server history is authoritative. Local Hosted JSONL entries
remain an activity transcript, not model-owned history. A bound PromptAgent is represented by the sealed
`ProviderManagement.PromptAgents` surface and causes the core model-switch operation to return a fixed-agent outcome.
Its provider seam prepares an unpublished name/delegate holder rather than mutating immediately. `AgentLoop` is the
sole production coordinator that persists the normalized name, commits that holder, commits the live session, and
returns the committed name for frontend status. This PromptAgent-specific operation does not extend Hosted lifecycle
or create a generic provider transaction. Frontends only map committed outcomes to presentation copy.

### Two axes, two seams

Both seams are **Foundry-specific**. `AgentProvider` abstracts the **loop-ownership axis** within Foundry—who drives
the tool loop (`Prompt` in the client versus `Hosted` in the server container). `FoundryResponsesClient` is the
narrower SDK/test seam beneath the Foundry Prompt path: one invocation represents one Responses call. It is not an
extension point for another service vendor. Hosted relays a Foundry server stream and makes no local Responses call.

The authoritative seam and application-domain boundary types are
[`FoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/FoundryResponsesClient.kt),
[`FoundryResponsesRequest`](../../src/main/kotlin/com/konductor/provider/inference/FoundryResponsesRequest.kt),
[`FoundryResponsesResult`](../../src/main/kotlin/com/konductor/provider/inference/FoundryResponsesResult.kt), and
[`FoundryResponsesEvent`](../../src/main/kotlin/com/konductor/provider/inference/FoundryResponsesEvent.kt).
`FoundryResponsesClient.respondStreaming` must emit text deltas as they arrive, may report transient retries only
before model output, and must end successfully with exactly one `FoundryResponsesEvent.Completed` carrying the
aggregated text, tool calls, and usage. Failures after output are surfaced without replaying the partial response.
The two Prompt adapters alone map the exact structured Foundry/openai-java overflow failure to the SDK-free
`PromptContextOverflowException`; that type is not a generic retry signal, and only that application type is inspected
above the seam.
`FoundryResponsesClient.respond` is a direct adapter helper; `PromptProvider.runTurn` uses the streaming operation.

The Prompt path has separate
[`EphemeralFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt)
and
[`PromptAgentFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt)
adapters because their accepted Foundry request shapes differ. Both keep Responses/Agents types (`com.openai.*` /
`com.azure.ai.*`) inside `provider/inference` and share SDK-bound functions in
[`ResponsesMapping.kt`](../../src/main/kotlin/com/konductor/provider/inference/ResponsesMapping.kt).
[`PromptProvider`](../../src/main/kotlin/com/konductor/provider/PromptProvider.kt) owns the loop but speaks these
application-domain types, so
[`MockFoundryResponsesClient`](../../src/test/kotlin/com/konductor/provider/inference/MockFoundryResponsesClient.kt)
can exercise it deterministically. The seam exists for SDK containment, lifecycle, preview churn, and testability—not
backend swappability. Identity and credential types remain Foundry composition concerns, currently owned by
`Configuration`; the migration is specified in [I055](../iterations/I055-foundry-first-platform-alignment.md).

## Turn lifecycle (Prompt provider)

```
User submits text
  └─ Agent loop: append UserEntry → persist
     └─ ContextWindowTracker: if over threshold → Compactor.compact()  (see compaction.md)
        └─ Build TurnRequest(context, history)
           ├─ provider.runTurn(request, toolExecutor) : Flow<AgentEvent>
           │  └─ collect responsesClient.respondStreaming(FoundryResponsesRequest(...))
           │     ├─ TextDelta → emit AgentEvent.TextDelta immediately
           │     ├─ Retrying  → emit AgentEvent.Retrying (transient failure before model output)
           │     ├─ adapter throws typed overflow → provider emits Failed → AgentLoop applies safe recovery
           │     └─ Completed(result) [required terminal event]
           │        ├─ usage → emit UsageReported
           │        └─ result has toolCalls?
           │           ├─ yes → emit ToolCallStarted → toolExecutor.execute() → emit ToolCallCompleted
           │           │        → append ToolCall/ToolResult entries → start another streaming call
           │           └─ no  → emit TurnCompleted
           └─ Agent loop: append AssistantEntry → persist → render
```

A cancellation or terminal stream failure unwinds collection; the provider's exception-transparent `catch` emits
`AgentEvent.Failed` for non-cancellation failures. Transient retries are only safe before any text or completed output;
a failure after output is surfaced without replaying the partial response.

`AgentLoop` withholds only the first main attempt's typed Prompt overflow when capabilities declare client compaction
and client-owned history and the attempt has emitted no text, usage/completed output, tool call/result, or possible tool
side effect. It compacts and durably commits once, rebuilds the transcript, and invokes the provider once more without
recording the user again. Retry success rejoins normal event folding. No compactable history, compaction failure, retry
failure, unsafe output/activity, or a consumed recovery budget ends the turn; cancellation propagates and wins over all
recovery failures. See [compaction.md](compaction.md#reactive-context-overflow-recovery) for exact event, persistence,
proactive-interaction, and failure-precedence rules.

The `input` sent each turn—including the recovery retry—is the **reconstructed transcript** (post-compaction), never
`previousResponseId`; the one accepted `UserEntry` is part of that transcript. See
[providers.md](providers.md#request-shape).

## Threading & concurrency

> **Implementation target (I081):** the TUI runs agent turns and local background commands as distinct active
> submissions, applies `AppState` mutations under a render lock, and gives commands an atomic cancellation/commit
> boundary. ACP independently owns a cancelable turn job. Steering/follow-up/command queues are not implemented. Each
> `AgentLoop` is single-flight; overlapping collection is rejected rather than queued.

- The Lanterna input read loop runs on the main thread (`TuiApp.eventLoop`). Agent turns and local background commands
  execute in the TUI-owned coroutine scope.
- There is no UI update queue. `StateApplier` folds accepted background events/results into `AppState` under the render
  lock and marks the screen dirty; the event-loop tick renders the resulting state. Background code does not mutate
  presentation state outside that boundary.
- **Per-session single-flight:** one `AgentLoop` owns one mutable `Session`, so only one turn, compaction, live-session
  switch, or PromptAgent binding operation may commit at a time. Overlap is rejected rather than queued: the TUI keeps
  composer input inert for the complete active submission, and ACP rejects a second prompt instead of retaining stale
  input. No turn observes PromptAgent metadata after durable acceptance but before its non-failing provider/session
  fan-out.
- **PromptAgent commit:** preparation may allocate or fail while the old provider holder remains active; atomic metadata
  persistence is the only durable decision. The subsequent provider-holder swap, scalar `Session` commit, and TUI
  status application perform no service/filesystem calls or result-affecting cleanup. Restart reconciles from the
  accepted header's exact name, never through rollback or version inference.
- **TUI submission identity:** the active slot is an agent turn or a local background command, never a bare anonymous
  job. Palette option loads remain generation-owned frontend work and cannot mutate a session. The active identity stays
  registered through unwind, terminal reporting, and working-state clear, so a stale completion cannot affect newer
  work.
- **Agent-turn cancellation:** `Esc` requests cancellation of the turn job; in-flight SDK calls/tools observe
  `CancellationException`. One turn-specific cancellation report follows unwind. Persisted user/tool entries and real
  external side effects remain; cancellation is not rollback.
- **Local-command cancellation:** one atomic phase linearizes `requestCancel` against `beginCommit`. A cancellation win
  changes `Running` to `Cancelling` before cancelling the job and prevents every later command commit. A commit win
  changes `Running` to `Committing`; persistence and matching live/UI mutation complete in `NonCancellable` and report
  natural success/failure. Repeated or post-commit `Esc` is inert. Input remains inert in both phases.
- **ACP cancellation:** ACP keeps its active turn registered until cancellation has fully unwound, so `session/cancel`
  cannot accidentally target a competing prompt. TUI local-command phases and copy do not add an ACP command surface.
  Steering and graceful TUI shutdown are specified in [tui.md](tui.md#active-submissions-and-cancellation).

```kotlin
class AgentLoop(scope: CoroutineScope, provider: AgentProvider, tools: ToolExecutor,
                session: Session, store: SessionStore, ui: UiEvents) {
    fun submit(text: String): Job  // launches runTurn, streams AgentEvents to `ui`
    fun cancel()
}
```

## Compaction integration

`ContextWindowTracker` keeps the latest `Usage.totalTokens` and the model's context window. Before each turn, if
`totalTokens > contextWindow - reserveTokens`, the loop runs the `Compactor`, which replaces the summarized span
with a `CompactionEntry`. Full algorithm in [compaction.md](compaction.md). `AgentLoop` applies the provider capability
before constructing the tracker, so compaction applies to **Prompt** only even if a frontend passes enabled settings;
Hosted agents manage their own context.

## Error handling & retry

| Situation | Handling |
|-----------|----------|
| Transient HTTP (429/5xx/timeout) | The ephemeral Prompt adapter retries before output with capped exponential backoff and a structured status; this policy does not classify overflow or apply to Hosted |
| Exact safe Prompt context overflow | The adapters expose an SDK-free typed failure; `AgentLoop` performs at most one immediate compact + reconstructed-history retry without another user entry |
| Overflow after text/completed output/tool activity, no compactable span, or second overflow | Do not replay; surface one terminal failure under the documented stage precedence |
| Cancellation during request/compaction/retry | Propagate cancellation; do not emit `Failed` or start further recovery work |
| Tool failure | Return `ToolResult(isError = true)`; the model sees the error and can recover |
| Fatal (auth/config) | Emit `AgentEvent.Failed`; render an error entry; keep the session usable |

Context overflow is checked at the Prompt Responses adapter seam by HTTP 400 plus the exact structured code; it never
joins transient backoff. Hosted is excluded because it neither calls this seam nor owns client-compacted history. The
full classifier is in [providers.md](providers.md#context-overflow-classification), and the bounded state machine is in
[compaction.md](compaction.md#reactive-context-overflow-recovery).

## Package layout

```
src/main/kotlin/com/konductor
├── Main.kt           # entry point → interactive TUI, or the headless ACP frontend when run with `acp`
├── core/            # domain model: Entry, Session, ToolCall/Result, Usage, AgentContext
├── agent/           # AgentLoop + structured context loading/rendering + prompt assembly
├── provider/        # AgentProvider, AgentEvent, TurnRequest
│   ├── PromptProvider.kt       # client-owned function-tool loop
│   ├── inference/   # ephemeral + PromptAgent Foundry Responses adapters/test seam
│   └── hosted/      # HostedProvider (agent-scoped client, sessions, logs, files)
├── session/         # SessionStore (JSONL), serialization
├── compaction/      # Compactor, context tracker, summary serialization/truncation
├── tool/            # ToolRegistry + built-in tools (read/edit/write/bash/grep/find/ls)
├── config/          # Config bootstrap/loading, env/settings, workspace trust store/coordinator
├── workspace/       # canonical cwd/root/config containment + bounded workspace reads
├── i18n/            # ResourceBundle-backed frontend string catalog
├── conversation/    # TUI adapter + session/model/agent commands
├── acp/             # headless ACP frontend (stdio JSON-RPC) — alternate to tui/
└── tui/             # rendering + multiline input + streaming/cancellation
```

## Related docs

[index.md](../index.md) · [providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) ·
[agent-context.md](agent-context.md) · [tools.md](tools.md) · [sessions.md](sessions.md) ·
[compaction.md](compaction.md) · [tui.md](tui.md) · [configuration.md](configuration.md) · [acp.md](acp.md) ·
[implementation-roadmap.md](../implementation-roadmap.md)
