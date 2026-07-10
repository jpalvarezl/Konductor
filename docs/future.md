# Future Ideas (Living Backlog)

Ideas intentionally deferred beyond the hackathon so they aren't lost. Nothing here is committed — it's a parking
lot with enough detail to pick up later. Each item notes rough **value/effort** and, where relevant, the **SDK
entry point**. See [index.md](index.md) for what's in scope now.

## More agent kinds

- **Workflow agents** — declarative multi-agent orchestration. *Value: high · Effort: high.* SDK:
  `WorkflowAgentDefinition.setWorkflow(csdlYaml)`; register via `createAgentVersion`. Would add a `WorkflowProvider`
  behind the existing seam ([providers.md](spec/providers.md)); the TUI could visualize sub-agent steps.
- **External agents** — observability-only registration of a third-party agent (GCP/AWS). *Value: low · Effort:
  low.* SDK: `ExternalAgentDefinition.setOtelAgentId(...)`. Useful for tracing/eval experiments, not for running a
  coding agent.
- **Persisted "CompactorAgent"** — instead of summarizing via an ephemeral inference call, mint a dedicated
  **persisted PromptAgent** in Foundry (`createAgentVersion`) whose baked instructions + summary template *are*
  the summarizer, and invoke it agent-scoped to compact ([compaction.md](spec/compaction.md)). *Value: medium
  (strong dog-fooding) · Effort: medium.* Leans Konductor further into a **Foundry-opinionated** harness: the
  compaction summarizer becomes a first-class, versioned service artifact rather than a client-side prompt, reusing
  the M2.5 PromptAgent surface ([providers.md](spec/providers.md#persisted-prompt-agents-promptagent)). Would also
  give the summarizer a *stable* server-side system prompt in every case (the open trade-off behind today's
  ephemeral summarizer). Keep the ephemeral summarizer as the default/offline fallback.

## Agent orchestration

**What we mean by "agent orchestration":** one agent coordinating *other* agents — decomposing a task,
delegating scoped sub-tasks to child agents, and composing their results — instead of a single agent running one
linear loop. Konductor can grow into this along two complementary axes:

- **Client-side — the ACP *client* role (ACP Phase D, [acp.md](spec/acp.md#status)).** The mirror image of
  Konductor's ACP *agent* role: rather than *being driven* by an ACP client over stdio, a headless Konductor
  *acts as* the ACP client and drives one or more **other** ACP agents (another Konductor instance, or any
  ACP-compliant agent). This is the natural home for **sub-agents** — a parent decomposes a task, spawns scoped
  child agents over stdio, relays their `session/update`s, and merges the results. It reuses the same `acp-jvm`
  transport already in the tree, just on the client side of the protocol. *Value: medium · Effort: high.*
  Deferred because the **agent role** (being a spec-compliant ACP agent that clients can drive) is the primary
  goal; orchestration is additive on top of it.
- **Server-side — Workflow agents (see [More agent kinds](#more-agent-kinds)).** Declarative multi-agent
  workflows authored server-side (`WorkflowAgentDefinition`) and run by Foundry, rather than driven step-by-step
  by Konductor. Complementary to the client-side path: server-authored graphs vs. client-driven delegation.

**Scope guards for a future client-role pass:** child-agent lifecycle (spawn / cancel / cleanup, mirroring the
Hosted provider's session cleanup), permission and tool-policy propagation to children, transcript composition
(how child sessions fold into the parent [session](spec/sessions.md)), and context budgeting across the agent
tree.

## Server-side conversation state

- **Conversations** — let the service hold conversation state via `conversation` / `previousResponseId` on
  `ResponseCreateParams`. *Value: medium · Effort: medium.* Trade-off: it **removes client-side compaction control**
  ([compaction.md](spec/compaction.md)), so it'd be an alternate "server-managed history" mode, not the default.
- **Memory Stores** — durable, **per-user/session** memory (e.g. `ChatSummaryMemoryItem`, `MemorySearchPreviewTool`,
  `BetaMemoryStoresClient`). *Value: high · Effort: high.* This is long-term memory, distinct from short-term
  compaction — a good fit for "remember my preferences/project facts across sessions."

## Richer tools

- **Bundle `ripgrep` (and maybe `fd`) with releases** — `grep` already prefers an `rg` binary on `PATH` and
  falls back to a portable in-process search; `find`/`grep` prune noise dirs in-process. Shipping a per-OS `rg`
  in the jpackage image (or a first-run download to `~/.konductor/bin/`, mirroring pi's `~/.pi/agent/bin/rg`)
  would give ripgrep's speed + `.gitignore`-awareness everywhere **without breaking self-containment** — `rg` is
  a standalone binary needing no shell, unlike relying on MSYS/Git-Bash on Windows. *Value: medium · Effort:
  low–medium.* SDK entry point: n/a (packaging in the `dist` profile + release workflow, [distribution.md](distribution.md)).
- **Server-side tools** — `CodeInterpreterTool`, `FileSearchTool`, `AzureAISearchTool`, `BingGroundingTool`,
  `WebSearchTool`, `McpTool`, `OpenApiTool`, etc. *Value: high · Effort: medium.* Attach on the agent/response
  instead of executing locally; would extend [tools.md](spec/tools.md) with a "server tools" section.
- **MCP** — expose external MCP servers as tools. *Value: medium · Effort: medium.*
- **Sub-agents** — spawn scoped child agents for delegated tasks. See [Agent orchestration](#agent-orchestration)
  (the ACP *client* role, Phase D). *Value: medium · Effort: high.*
- **Interactive per-call approval** — prompt before mutating tools run. *Value: medium · Effort: low.*

## Sessions & collaboration

- **Structured failed/aborted turn entries** — the current durable policy keeps the user entry and completed tool
  actions, but persists no partial assistant text without `TurnCompleted`. Add explicit `FailedEntry`/`AbortedEntry`
  variants when machine-readable resume/audit fidelity justifies a schema change. *Value: medium · Effort:
  low–medium.* Until then, the tested current policy remains authoritative
  ([sessions.md](spec/sessions.md)).
- **Branching / tree navigation** — the `parentId` field is already in the schema ([sessions.md](spec/sessions.md));
  add `/tree`, `/fork`, `/clone` and branch summaries. *Value: medium · Effort: medium.*
- **Export / share** — HTML/JSONL export, shareable links. *Value: low · Effort: low.*

## Ecosystem & ops

- **Themes / packages / extensions** — pi-style customization surface. *Value: low · Effort: high.*
- **Multi-provider auth** — API-key providers, other clouds, provider resolution order. *Value: low · Effort:
  medium.*
- **Dynamic Foundry model discovery** — `/model` takes a free-text model name and `/agent create` bakes in
  whatever context is active; neither validates against what the Foundry *project* actually has deployed (a
  project only exposes its own deployments). Enumerate the project's deployed models via `azure-ai-projects`
  and drive `/model` validation + completion — and the status-bar context-window lookup
  ([ModelContextWindow](../src/main/kotlin/com/konductor/core/ModelContextWindow.kt)) — from that live list,
  rather than free text + a static table. *Value: medium · Effort: medium.* Would also let `PromptAgentCommand`
  present real choices instead of relying on the current-context provider alone.
- **Foundry evaluations & tracing** — use `azure-ai-projects` (evaluations, red-teaming, insights) to score/trace
  Konductor runs. *Value: high · Effort: medium.* Strong dog-fooding of the projects SDK.
- **Non-streaming → streaming everywhere / cost accounting polish** — see M6 in
  [implementation-roadmap.md](implementation-roadmap.md).

## Related docs

[index.md](index.md) · [providers.md](spec/providers.md) · [hosted-agents.md](spec/hosted-agents.md) ·
[compaction.md](spec/compaction.md) · [implementation-roadmap.md](implementation-roadmap.md)
