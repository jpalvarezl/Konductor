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
- **Sub-agents** — spawn scoped child agents for delegated tasks. *Value: medium · Effort: high.* Maps to the
  **ACP client role** (Phase D in [acp.md](spec/acp.md)): a headless Konductor acting as an ACP *client* that
  drives another agent over stdio.
- **Interactive per-call approval** — prompt before mutating tools run. *Value: medium · Effort: low.*

## Sessions & collaboration

- **Branching / tree navigation** — the `parentId` field is already in the schema ([sessions.md](spec/sessions.md));
  add `/tree`, `/fork`, `/clone` and branch summaries. *Value: medium · Effort: medium.*
- **Export / share** — HTML/JSONL export, shareable links. *Value: low · Effort: low.*

## Ecosystem & ops

- **Themes / packages / extensions** — pi-style customization surface. *Value: low · Effort: high.*
- **Multi-provider auth** — API-key providers, other clouds, provider resolution order. *Value: low · Effort:
  medium.*
- **Foundry evaluations & tracing** — use `azure-ai-projects` (evaluations, red-teaming, insights) to score/trace
  Konductor runs. *Value: high · Effort: medium.* Strong dog-fooding of the projects SDK.
- **Non-streaming → streaming everywhere / cost accounting polish** — see M6 in
  [implementation-roadmap.md](implementation-roadmap.md).

## Related docs

[index.md](index.md) · [providers.md](spec/providers.md) · [hosted-agents.md](spec/hosted-agents.md) ·
[compaction.md](spec/compaction.md) · [implementation-roadmap.md](implementation-roadmap.md)
