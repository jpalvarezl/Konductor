# Architecture

This is the **keystone** document. It defines the layers, the domain model, and the core abstractions
(`AgentProvider`, `AgentEvent`, `AgentContext`, `Session`) that every other doc builds on. Read
[index.md](../index.md) first for the confirmed decisions and SDK grounding facts.

> **Code blocks are illustrative design sketches, not committed implementation.** `src/` is intentionally
> untouched. The sketches fix names, shapes, and responsibilities so contributors (and lower-context agents) can
> implement consistently.

## Goals & non-goals

**Goals**

- Dog-food `azure-ai-agents` / `azure-ai-projects` (v2) by building a genuinely useful local coding agent.
- A clean **provider seam** so different Foundry **agent kinds** plug in behind one interface — starting with
  **Prompt** and **Hosted**.
- **Client-owned** conversation history with **client-side compaction** for the Prompt provider.
- Keep the existing Lanterna TUI; replace only the `ConversationController` seam.
- Keep the agent loop **frontend-agnostic**: one core powers both the interactive TUI and a **headless** ACP
  frontend ([acp.md](acp.md)).

**Non-goals (hackathon)** — see [future.md](../future.md): Workflow/External agent kinds, server-side Conversations
& Memory Stores, session branching, server-side tools, MCP, sub-agents, themes/packages.

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
│ AgentProvider  (loop-ownership seam)                               │
│   ├─ PromptProvider  — owns client loop; speaks neutral types       │
│   │        └─ InferenceClient  (vendor seam) — one model call        │
│   └─ HostedProvider  — server-owned loop (container)               │
└─────────────────▲──────────────────────────────────┬───────────────┘
                  │                                   │ HTTPS
┌─────────────────┴──────────────────────────────────▼───────────────┐
│ Azure SDKs   azure-ai-agents · azure-ai-projects                    │
│   AzureResponsesInferenceClient (only SDK importer) · agent-scoped  │
│   OpenAI client · Agents/Sessions                                   │
└──────────────────────────────────────────────────────────────────────┘

Cross-cutting services: SessionStore (JSONL) · Compactor · ToolRegistry · Config · ContextWindowTracker
```

Each layer depends only on the layer below and on the domain model. **Frontends** turn user/client input into
agent-loop submissions and render the resulting `AgentEvent`s; they never talk to the SDK directly, and neither the
agent loop nor any provider touches a frontend (Lanterna or ACP). Because the agent loop is **frontend-agnostic**,
the interactive TUI and the **headless** ACP frontend share one core.

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

Everything the model sees *before* the transcript. Assembled by the agent-context layer
([agent-context.md](agent-context.md)) from the system prompt, discovered context files, and the tool registry.

```kotlin
data class AgentContext(
    val systemPrompt: String,          // system/developer instructions (+ AGENTS.md content)
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

- **Prompt** — the provider internally loops: call `ResponsesClient` → if the response contains function calls,
  invoke `ToolExecutor` for each, feed the outputs back, call again — until the model returns a final answer.
- **Hosted** — the provider opens the server session's response/log stream and relays it; the container owns the
  loop. (If a hosted protocol surfaces client-side tool calls, it uses the same `ToolExecutor`.)

```kotlin
interface AgentProvider {
    val kind: AgentKind                                  // Prompt | Hosted
    fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent>
    suspend fun close()
}

data class TurnRequest(
    val context: AgentContext,
    val history: List<Entry>,      // full client-owned transcript (Prompt provider)
    val sessionRef: SessionRef? = null,   // server-side session id (Hosted provider)
)

fun interface ToolExecutor { suspend fun execute(call: ToolCall): ToolResult }

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent           // streamed assistant text
    data class ToolCallStarted(val call: ToolCall) : AgentEvent
    data class ToolCallCompleted(val call: ToolCall, val result: ToolResult) : AgentEvent
    data class LogFrame(val line: String) : AgentEvent            // Hosted session logs
    data class UsageReported(val usage: Usage) : AgentEvent
    data class TurnCompleted(val assistant: AssistantEntry) : AgentEvent
    data class Failed(val error: Throwable) : AgentEvent
}
```

Why loop ownership lives *inside* the provider: the two SDKs drive their loops differently (client-side re-request
vs. server stream). Putting the loop behind `runTurn` keeps the agent-loop layer and the TUI identical for both
kinds; only tool *execution* is delegated out (so tools stay local and cwd-scoped). See [providers.md](providers.md)
and [hosted-agents.md](hosted-agents.md) for each implementation.

### Two axes, two seams

`AgentProvider` abstracts the **loop-ownership axis** — *who drives the tool loop* (client-side `Prompt` vs.
server-side `Hosted`). It deliberately does **not** abstract the **vendor axis** — *how one model call is made*.
That belongs to a narrower seam, `InferenceClient`, which lives **beneath the Prompt path only** (Hosted relays a
server stream and makes no local inference calls).

```kotlin
interface InferenceClient {
    suspend fun respond(request: InferenceRequest): InferenceResponse
    fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk>   // M6
    suspend fun close()
}

data class InferenceRequest(
    val model: String,
    val systemPrompt: String,
    val history: List<Entry>,       // reuse the domain model — no SDK types
    val tools: List<ToolSpec>,
    val temperature: Double? = null,
)

data class InferenceResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage?,
)
```

`InferenceClient` is the **AI-SDK chokepoint**: exactly one implementation (`AzureInferenceClient`) imports the
Foundry Responses/Agents surface (`com.openai.*` / `com.azure.ai.*`). Identity and credential types
(`com.azure.core.credential` / `com.azure.identity`) are separate concerns, owned by `Config`. `PromptProvider`
owns the loop but speaks only these neutral types, so it
is unit-testable with a fake client and vendor-swappable for free. This is **not** speculative vendor abstraction —
it earns its keep today via the SDK chokepoint and loop testability; a second vendor is a free side effect, not the
goal. Scope guard: one interface, one Azure implementation — no vendor registry, no config-driven vendor
selection, no OpenAI/Anthropic stubs (deferred, [future.md](../future.md)).

## Turn lifecycle (Prompt provider)

```
User submits text
  └─ Agent loop: append UserEntry → persist
     └─ ContextWindowTracker: if over threshold → Compactor.compact()  (see compaction.md)
        └─ Build TurnRequest(context, history)
           └─ provider.runTurn(request, toolExecutor) : Flow<AgentEvent>
              ├─ inference.respond(InferenceRequest(history, tools = context.tools))
              ├─ response has toolCalls?
              │    ├─ yes → emit ToolCallStarted → toolExecutor.execute() → emit ToolCallCompleted
              │    │        → append ToolCall/ToolResult entries → re-request with outputs appended
              │    └─ no  → emit TextDelta*  + UsageReported + TurnCompleted
              └─ Agent loop: append AssistantEntry → persist → render
```

The `input` sent each turn is the **reconstructed transcript** (post-compaction), never `previousResponseId` —
see the multi-turn decision in [index.md](../index.md).

## Threading & concurrency

- The Lanterna input read loop runs on the main thread (existing `TuiApp.eventLoop`).
- `runTurn` executes on a coroutine (`Dispatchers.IO`) inside an application `CoroutineScope`.
- `AgentEvent`s are collected and posted to a thread-safe **UI update queue**; the render loop drains it and
  repaints. UI state (`AppState`) is mutated only on the UI thread.
- **Cancellation:** `Esc` cancels the turn's `Job`; in-flight SDK calls and tool executions observe the
  `CancellationException`. Queued/steering input is documented in [tui.md](tui.md).

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
with a `CompactionEntry`. Full algorithm in [compaction.md](compaction.md). Compaction applies to the **Prompt**
provider only; Hosted agents manage their own context.

## Error handling & retry

| Situation | Handling |
|-----------|----------|
| Transient HTTP (429/5xx/timeout) | Retry with capped exponential backoff; surface a status line |
| Context overflow | Run compaction, then retry the turn once |
| Tool failure | Return `ToolResult(isError = true)`; the model sees the error and can recover |
| Fatal (auth/config) | Emit `AgentEvent.Failed`; render an error entry; keep the session usable |

## Target package layout

```
src/main/kotlin/com/konductor
├── Main.kt           # entry point → interactive TUI, or the headless ACP frontend when run with `acp`
├── core/            # domain model: Entry, Session, ToolCall/Result, Usage, AgentContext
├── agent/           # AgentLoop, ContextWindowTracker, ToolExecutor wiring
├── provider/        # AgentProvider, AgentEvent, TurnRequest
│   ├── prompt/      # PromptProvider (owns loop, neutral types)
│   │   └── azure/   # AzureResponsesInferenceClient (ONLY SDK importer)
│   └── hosted/      # HostedProvider (agent-scoped client, sessions, logs, files)
├── inference/       # InferenceClient, InferenceRequest/Response/Chunk (vendor seam)
├── session/         # SessionStore (JSONL), serialization
├── compaction/      # Compactor, summary prompt, serialization/truncation
├── tool/            # ToolRegistry + built-in tools (read/edit/write/bash/grep/find/ls)
├── config/          # Config loading, env vars, settings
├── conversation/    # (existing seam) → thin adapter onto AgentLoop
├── acp/             # headless ACP frontend (stdio JSON-RPC) — alternate to tui/
└── tui/             # (existing) rendering + input, extended for streaming/logs
```

## Related docs

[index.md](../index.md) · [providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) ·
[agent-context.md](agent-context.md) · [tools.md](tools.md) · [sessions.md](sessions.md) ·
[compaction.md](compaction.md) · [tui.md](tui.md) · [configuration.md](configuration.md) · [acp.md](acp.md) ·
[implementation-roadmap.md](../implementation-roadmap.md)
