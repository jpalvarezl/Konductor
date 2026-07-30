# Future Directions

Durable unscheduled product direction. This file is not ordered, prioritized, assigned, or a commitment to implement;
GitHub issues and milestones own actionable planning and coordination.

When an idea needs discussion or prioritization, create a focused GitHub issue. When non-trivial work is accepted and
scoped, link a stable [`I###-*.md`](iterations/README.md) delivery packet and remove actionable planning from this file.
Tiny obvious fixes may remain packet-free. Keep durable trade-offs here only while no owning spec exists; move stable
contracts into `docs/spec/`.

Priority, status, ownership, dependencies, and burndown metadata belong on linked GitHub issues and milestones, not
in this file.

## Foundry-first platform constraint

Konductor targets **Azure AI Foundry only**. Future work should deepen useful integration with `azure-ai-agents` and
`azure-ai-projects`, not preserve backend portability or introduce a generic vendor registry. Narrow interfaces remain
appropriate for SDK adaptation, preview isolation, lifecycle ownership, and deterministic tests, but must not imply a
planned non-Foundry implementation.

The current migration is owned by
[#55](https://github.com/jpalvarezl/Konductor/issues/55), its
[delivery packet](iterations/I055-foundry-first-platform-alignment.md), and the
[Foundry-first platform alignment milestone](https://github.com/jpalvarezl/Konductor/milestone/2). Every direction
below should be interpreted through that constraint. Project deployments/connections establish the composition boundary;
Memory Stores, Foundry tools, conversations, evaluations, and additional Foundry agent kinds build on it.

## More agent kinds

- **Workflow agents** — declarative multi-agent orchestration. Tracked in
  [#42](https://github.com/jpalvarezl/Konductor/issues/42). SDK:
  `WorkflowAgentDefinition.setWorkflow(csdlYaml)`; register via `createAgentVersion`. Would add a `WorkflowProvider`
  behind the existing seam ([providers.md](spec/providers.md)); the TUI could visualize sub-agent steps.
- **External agents** — observability-only registration of a third-party agent (GCP/AWS). Tracked in
  [#43](https://github.com/jpalvarezl/Konductor/issues/43). SDK:
  `ExternalAgentDefinition.setOtelAgentId(...)`. Useful for tracing/eval experiments, not for running a coding agent.
- **Persisted "CompactorAgent"** — instead of summarizing via an ephemeral Foundry Responses call, mint a dedicated
  **persisted PromptAgent** in Foundry (`createAgentVersion`) whose baked instructions + summary template *are*
  the summarizer, and invoke it agent-scoped to compact ([compaction.md](spec/compaction.md)). Leans Konductor
  further into a **Foundry-opinionated** harness: the
  compaction summarizer becomes a first-class, versioned service artifact rather than a client-side prompt, reusing
  the M2.5 PromptAgent surface ([providers.md](spec/providers.md#persisted-prompt-agents-promptagent)). Tracked in
  [#44](https://github.com/jpalvarezl/Konductor/issues/44). Would also give the summarizer a *stable* server-side
  system prompt in every case (the open trade-off behind today's ephemeral summarizer). Keep the ephemeral
  summarizer as the default/offline fallback.

## Runtime agent creation and registry

GitHub tracking:
[#33 — Runtime agent creation and registry](https://github.com/jpalvarezl/Konductor/issues/33).

- **Unified runtime agent creation** — Konductor already has two concrete creation seams:
  `/agent create` mints a persisted PromptAgent version through `PromptAgentClient.createAgentVersion`, while
  `HostedAgentClient.selectOrCreateAgentVersion` selects or deploys a Hosted version. A future iteration could define
  a small `AgentSpec` that lowers into those existing calls, plus a user-controlled registry for reuse and switching.
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

GitHub tracking:
[#34 — ACP client role and child-agent orchestration](https://github.com/jpalvarezl/Konductor/issues/34).

**What we mean by "agent orchestration":** one agent coordinating *other* agents — decomposing a task,
delegating scoped sub-tasks to child agents, and composing their results — instead of a single agent running one
linear loop. Konductor can grow into this along two complementary axes:

- **Client-side — the ACP *client* role (ACP Phase D, [acp.md](spec/acp.md#status)).** The mirror image of
  Konductor's ACP *agent* role: rather than *being driven* by an ACP client over stdio, a headless Konductor
  *acts as* the ACP client and drives one or more **other** ACP agents (another Konductor instance, or any
  ACP-compliant agent). This is the natural home for **sub-agents** — a parent decomposes a task, spawns scoped
  child agents over stdio, relays their `session/update`s, and merges the results. It reuses the same `acp-jvm`
  transport already in the tree, just on the client side of the protocol. Deferred because the **agent role**
  (being a spec-compliant ACP agent that clients can drive) is the primary goal; orchestration is additive on top
  of it.
- **Server-side — Workflow agents (see [More agent kinds](#more-agent-kinds)).** Declarative multi-agent
  workflows authored server-side (`WorkflowAgentDefinition`) and run by Foundry, rather than driven step-by-step
  by Konductor. Complementary to the client-side path: server-authored graphs vs. client-driven delegation.

**Scope guards for a future client-role pass:** child-agent lifecycle (spawn / cancel / cleanup, mirroring the
Hosted provider's session cleanup), permission and tool-policy propagation to children, transcript composition
(how child sessions fold into the parent [session](spec/sessions.md)), and context budgeting across the agent
tree.

## ACP agent-role completion

GitHub tracking:
[#32 — Complete the ACP agent role](https://github.com/jpalvarezl/Konductor/issues/32).
The shared mutating-tool authorization policy is tracked in
[#38](https://github.com/jpalvarezl/Konductor/issues/38).

- **Protocol observability and permissions** — complete the current ACP agent role before starting the client-role
  orchestration work.
  - Replay persisted transcript entries as `session/update`s on load.
  - Emit stable usage/context and compaction updates.
  - Add `session/request_permission` for mutating tools with a deterministic headless policy.
  - Add an in-process client/agent golden protocol test.
  - Client-delegated `fs/*` and `terminal/*` remain optional and should be evaluated separately.

## Server-side conversation state

- **Conversations** — let the service hold conversation state via `conversation` / `previousResponseId` on
  `ResponseCreateParams`. Tracked in [#45](https://github.com/jpalvarezl/Konductor/issues/45). Trade-off: it
  **removes client-side compaction control** ([compaction.md](spec/compaction.md)),
  so it'd be an alternate "server-managed history" mode, not the default.
- **Azure AI Agents Memory Stores** — optional long-term preferences and procedures backed by
  `BetaMemoryStoresClient`. This is distinct from transcript persistence and short-term compaction. Tracked in
  [#39](https://github.com/jpalvarezl/Konductor/issues/39).
  - Require explicit operator identity or scope; never infer identity from OS/Git metadata.
  - Support user-global, user+repository, autonomous-agent, and explicit scope modes.
  - Default to disabled, read-only, reuse-only behavior; store creation and writes require explicit opt-in.
  - Preload a bounded number of `USER_PROFILE`/`PROCEDURAL` items into the prompt at TUI or ACP session start.
  - Keep SDK calls behind a focused Foundry memory service so prompt assembly does not import Azure SDK types.
  - Open questions: item granularity, repository-key normalization, per-session ACP scope overrides, deletion UX,
    privacy guidance, and whether remembered tool preferences are soft guidance or enforceable policy.

## Richer tools

- **Bundle `ripgrep` (and maybe `fd`) with releases** — `grep` already prefers an `rg` binary on `PATH` and
  falls back to a portable in-process search; `find`/`grep` prune noise dirs in-process. Shipping a per-OS `rg`
  in the jpackage image (or a first-run download to `~/.konductor/bin/`, mirroring pi's `~/.pi/agent/bin/rg`)
  would give ripgrep's speed + `.gitignore`-awareness everywhere **without breaking self-containment** — `rg` is
  a standalone binary needing no shell, unlike relying on MSYS/Git-Bash on Windows. Tracked in
  [#46](https://github.com/jpalvarezl/Konductor/issues/46). SDK entry point:
  n/a (packaging in the `dist` profile + release workflow, [distribution.md](distribution.md)).
- **Server-side tools** — `CodeInterpreterTool`, `FileSearchTool`, `AzureAISearchTool`, `BingGroundingTool`,
  `WebSearchTool`, `McpTool`, `OpenApiTool`, etc. Attach on the agent/response
  instead of executing locally; would extend [tools.md](spec/tools.md) with a "server tools" section. The shared
  local-versus-server tool boundary is tracked in [#40](https://github.com/jpalvarezl/Konductor/issues/40).
- **MCP** — expose external MCP servers as tools; tracked with the server-tool boundary in
  [#40](https://github.com/jpalvarezl/Konductor/issues/40).
- **Sub-agents** — spawn scoped child agents for delegated tasks. See [Agent orchestration](#agent-orchestration)
  (the ACP *client* role, Phase D).
- **Interactive per-call approval** — prompt before mutating tools run; the TUI/ACP/headless policy is tracked in
  [#38](https://github.com/jpalvarezl/Konductor/issues/38).

## Sessions & collaboration

- **Structured failed/aborted turn entries** — the current durable policy keeps the user entry and completed tool
  actions, but persists no partial assistant text without `TurnCompleted`. Add explicit `FailedEntry`/`AbortedEntry`
  variants when machine-readable resume/audit fidelity justifies a schema change. Tracked in
  [#35](https://github.com/jpalvarezl/Konductor/issues/35). Until then, the
  tested current policy remains authoritative ([sessions.md](spec/sessions.md)).
- **Branching / tree navigation** — the `parentId` field is already in the schema ([sessions.md](spec/sessions.md));
  add `/tree`, `/fork`, `/clone` and branch summaries. Tracked in
  [#47](https://github.com/jpalvarezl/Konductor/issues/47).
- **Export / share** — HTML/JSONL export and later shareable links; tracked in
  [#48](https://github.com/jpalvarezl/Konductor/issues/48).

## Ecosystem & ops

- **Themes / packages / extensions** — pi-style customization surface; tracked in
  [#49](https://github.com/jpalvarezl/Konductor/issues/49).
- **Foundry authentication and project selection** — improve Entra credential diagnostics, tenant/project selection,
  and noninteractive authentication where concrete Foundry scenarios require them. This does not include alternate
  inference backends; the superseded multi-provider proposal is recorded in
  [#50](https://github.com/jpalvarezl/Konductor/issues/50).
- **Foundry model metadata** — startup selection and in-session discovery use deployment names without deriving
  runtime policy from catalog metadata. The status bar still derives context-window size from the static
  [ModelContextWindow](../src/main/kotlin/com/konductor/core/ModelContextWindow.kt) table; future catalog metadata can
  replace that lookup and support richer completion/selection. PromptAgent-definition model inference remains excluded
  from startup bootstrap and needs its own accepted contract if required.
- **Foundry evaluations & tracing** — use `azure-ai-projects` (evaluations, red-teaming, insights) to score/trace
  Konductor runs. Tracked in [#41](https://github.com/jpalvarezl/Konductor/issues/41). Strong dog-fooding of the
  projects SDK.
- **Runtime provider switching** — `/model` can update a Prompt session, but changing Prompt ↔ Hosted requires
  rebuilding provider lifecycle and session semantics. The prerequisite provider capability/lifecycle design is
  tracked in [#30](https://github.com/jpalvarezl/Konductor/issues/30), and the switching outcome itself in
  [#51](https://github.com/jpalvarezl/Konductor/issues/51).

## Related docs

[index.md](index.md) · [providers.md](spec/providers.md) · [hosted-agents.md](spec/hosted-agents.md) ·
[iterations](iterations/index.md) · [compaction.md](spec/compaction.md) ·
[implementation-roadmap.md](implementation-roadmap.md)
