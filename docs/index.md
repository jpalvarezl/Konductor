# Konductor Documentation

Konductor is a Kotlin/JVM terminal coding-agent harness that dogfoods Azure AI Agents / Projects SDKs while remaining
a useful local coding tool in the spirit of pi and Copilot CLI.

Every work item has one canonical home based on its lifecycle:

- **one iteration file** owns bounded current work;
- **specs** define stable intended behavior;
- **source and tests** define what actually exists;
- **future.md** holds only unscheduled ideas;
- the original roadmap and burndown preserve the completed foundations cycle without tracking new work.

Indexes route to canonical documents; they do not duplicate task lists, acceptance criteria, or status narratives.

Illustrative Kotlin in `docs/` is design material, not committed implementation.

## Start with the smallest useful document

| Question | Start here |
|---|---|
| What is being implemented now or next? | [`iterations/index.md`](iterations/index.md) |
| How should a current iteration be implemented? | The linked `I###-*.md` context pack |
| What is the stable design contract? | The owning document under [`spec/`](spec/) |
| What ideas are intentionally unscheduled? | [`future.md`](future.md) |
| What shipped during the foundations cycle? | [`implementation-roadmap.md`](implementation-roadmap.md) and [`burndown.md`](burndown.md) |
| How do I build, run, or package Konductor? | [`development.md`](development.md) and [`distribution.md`](distribution.md) |
| Where are Azure SDK/service pain points recorded? | [`service_feedback/`](service_feedback/README.md) |

## Documentation map

**Delivery and development**

| Document | Purpose | Ownership |
|---|---|---|
| [`iterations/index.md`](iterations/index.md) | Active, ready, and completed iteration board | living |
| [`iterations/README.md`](iterations/README.md) | Iteration lifecycle and authoring rules | process |
| [`future.md`](future.md) | Unscheduled product and engineering backlog | backlog |
| [`development.md`](development.md) | Build/run, project layout, Foundry setup, debugging | development |
| [`distribution.md`](distribution.md) | `jpackage` bundles, release workflow, artifacts | development |
| [`hero-scenario.md`](hero-scenario.md) | End-to-end Prompt/Hosted and TUI/ACP scenarios | living |
| [`implementation-roadmap.md`](implementation-roadmap.md) | M0–M6 foundations plan | historical |
| [`burndown.md`](burndown.md) | M0–M6 and ACP foundations completion record | historical |
| [`service_feedback/`](service_feedback/README.md) | Azure SDK/service rough edges by feature | living |

**Stable design specification**

| Document | Purpose |
|---|---|
| [`spec/architecture.md`](spec/architecture.md) | Layers, domain model, provider/event seams, data flow, threading |
| [`spec/providers.md`](spec/providers.md) | Prompt provider, Responses loop, Azure inference seam, persisted PromptAgents |
| [`spec/hosted-agents.md`](spec/hosted-agents.md) | Hosted provider, versions, server sessions, logs, session files |
| [`spec/agent-context.md`](spec/agent-context.md) | Base/dynamic prompt assembly, context files, tool surface |
| [`spec/tools.md`](spec/tools.md) | Built-in coding tools, execution, containment, truncation |
| [`spec/sessions.md`](spec/sessions.md) | Session lifecycle, entry model, JSONL persistence and resume |
| [`spec/compaction.md`](spec/compaction.md) | Context-window tracking and client-side summary compaction |
| [`spec/tui.md`](spec/tui.md) | Terminal layout, input, rendering, commands, status |
| [`spec/configuration.md`](spec/configuration.md) | Environment, settings, CLI precedence, provider selection |
| [`spec/acp.md`](spec/acp.md) | Headless Agent Client Protocol frontend over stdin/stdout |

## Finding things fast

For implementation work, use this order:

1. Read [`AGENTS.md`](../AGENTS.md) for repository rules.
2. Open [`iterations/index.md`](iterations/index.md).
3. Read the one active or relevant ready iteration.
4. Read only the spec headings, source entry points, and tests in that iteration's context pack.
5. Expand the search only when those references are incomplete or contradicted by source.

Do not read all of `docs/` or `src/` by default. For unplanned questions, locate the owning document before reading:

```bash
rg -il "<concept>" docs/spec docs/*.md
rg -n "^#{1,3} " docs/spec/<owner>.md
rg -n "<ExactSymbol|RelatedSymbol>" src/main/kotlin src/test/kotlin
```

Every document starts with a purpose statement. Read its top and relevant headings, not necessarily the whole file.

## Update rules

- Starting or finishing implementation work updates its iteration in the same change.
- Behavior changes update the owning spec in the same change.
- Newly discovered but unscheduled work goes to `future.md` or a focused GitHub issue, not the active iteration.
- Selecting a backlog item removes its implementation detail from `future.md` and promotes it into a new iteration.
- Completed iterations stay at their stable path and link their merged pull requests.
- Azure SDK/service pain points go in `service_feedback/`, including the exact API/version, impact, workaround, and
  suggested fix.

## Key external references

- [pi](https://pi.dev) — local coding-agent design inspiration.
- [Azure AI Foundry documentation](https://learn.microsoft.com/en-us/azure/foundry/).
- `sdk/ai/{azure-ai-agents,azure-ai-projects}` in
  [Azure/azure-sdk-for-java](https://github.com/Azure/azure-sdk-for-java) — SDK source and samples.
