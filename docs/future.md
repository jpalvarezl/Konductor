# Future Backlog

Unscheduled ideas that are worth preserving but are not committed implementation work. Each item keeps only enough
context to decide whether it should become an iteration.

When an item is accepted and scoped, remove its implementation plan from this file and create a stable
[`I###-*.md`](iterations/README.md) iteration. Current work must not be tracked here.

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

## Runtime agent creation and registry

- **Unified runtime agent creation** — Konductor already has two concrete creation seams:
  `/agent create` mints a persisted PromptAgent version through `PromptAgentClient.createAgentVersion`, while
  `HostedAgentClient.selectOrCreateAgentVersion` selects or deploys a Hosted version. A future iteration could define
  a small `AgentSpec` that lowers into those existing calls, plus a user-controlled registry for reuse and switching.
  *Value: high · Effort: high.*
  - Prompt inputs today: name, model, instructions, and tool declarations.
  - Hosted inputs today: agent name and container image; provisioning/version activation may be asynchronous.
  - Candidate UX: create from scratch or clone the current agent, validate, confirm, then create/list/show/switch.
  - Persistence should start at user scope (`~/.konductor/agents/`). Project-local agent definitions would require
    the trust/resource policy from [I001](iterations/I001-workspace-context-and-trust.md).
  - A YAML import format may be useful, but **ACP is the Agent Client Protocol and does not itself define a canonical
    agent-template schema**. Select or define the schema before promising "ACP templates."
  - Open questions: stable identity versus display name, per-agent tool policy, secret references, scripted
    noninteractive creation, and whether Hosted provisioning can remain non-blocking in both TUI and ACP.

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

## ACP agent-role completion

- **Protocol observability and permissions** — complete the current ACP agent role before starting the client-role
  orchestration work. *Value: high · Effort: medium.*
  - Replay persisted transcript entries as `session/update`s on load.
  - Emit stable usage/context and compaction updates.
  - Add `session/request_permission` for mutating tools with a deterministic headless policy.
  - Add an in-process client/agent golden protocol test.
  - Client-delegated `fs/*` and `terminal/*` remain optional and should be evaluated separately.

## Server-side conversation state

- **Conversations** — let the service hold conversation state via `conversation` / `previousResponseId` on
  `ResponseCreateParams`. *Value: medium · Effort: medium.* Trade-off: it **removes client-side compaction control**
  ([compaction.md](spec/compaction.md)), so it'd be an alternate "server-managed history" mode, not the default.
- **Azure AI Agents Memory Stores** — optional long-term preferences and procedures backed by
  `BetaMemoryStoresClient`. This is distinct from transcript persistence and short-term compaction. *Value: high ·
  Effort: high.*
  - Require explicit operator identity or scope; never infer identity from OS/Git metadata.
  - Support user-global, user+repository, autonomous-agent, and explicit scope modes.
  - Default to disabled, read-only, reuse-only behavior; store creation and writes require explicit opt-in.
  - Preload a bounded number of `USER_PROFILE`/`PROCEDURAL` items into the prompt at TUI or ACP session start.
  - Keep the backend behind a neutral memory service so prompt assembly does not depend on Azure SDK types.
  - Open questions: item granularity, repository-key normalization, per-session ACP scope overrides, deletion UX,
    privacy guidance, and whether remembered tool preferences are soft guidance or enforceable policy.

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
- **Runtime provider switching** — `/model` can update a Prompt session, but changing Prompt ↔ Hosted requires
  rebuilding provider lifecycle and session semantics. Treat this as a dedicated iteration rather than an M6
  checkbox. *Value: medium · Effort: medium.*

## Related docs

[index.md](index.md) · [providers.md](spec/providers.md) · [hosted-agents.md](spec/hosted-agents.md) ·
[iterations](iterations/index.md) · [compaction.md](spec/compaction.md) ·
[implementation-roadmap.md](implementation-roadmap.md)
