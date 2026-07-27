# Konductor Documentation

Konductor is a Kotlin/JVM terminal coding-agent harness that dogfoods the Azure AI Agents / Projects Java SDKs while
remaining a useful local coding tool in the spirit of pi and Copilot CLI. Azure AI Foundry is the explicit and only
service platform; internal interfaces isolate SDK behavior and support tests rather than backend portability.

Each kind of information has one canonical owner:

- **GitHub issues** own discussion, assignment, dependencies, blockers, priority/status labels, and closure;
- **GitHub milestones** group related issues and pull requests into concrete outcomes or releases;
- **delivery packets** under `docs/iterations/` are small implementation briefs/context packs for accepted,
  non-trivial work; they own scope, acceptance, targeted context, and validation;
- **specs** define stable intended behavior;
- **source and tests** define what actually exists;
- **future.md** preserves durable unscheduled direction without acting as a prioritized backlog;
- the original roadmap and burndown preserve the completed foundations cycle without tracking new work.

Indexes route to canonical information; they do not duplicate task lists, acceptance criteria, or status narratives.

Illustrative Kotlin in `docs/` is design material, not committed implementation.

## Start with the smallest useful document

| Question | Start here |
|---|---|
| What is being discussed or implemented? | [GitHub issues](https://github.com/jpalvarezl/Konductor/issues), milestones, and the linked delivery packet |
| How should scoped non-trivial work be implemented? | The linked `docs/iterations/I###-*.md` delivery packet |
| What is the stable design contract? | The owning document under [`spec/`](spec/) |
| What direction is intentionally unscheduled? | [`future.md`](future.md) |
| What shipped during the foundations cycle? | [`implementation-roadmap.md`](implementation-roadmap.md) and [`burndown.md`](burndown.md) |
| How do I build, run, or package Konductor? | [`development.md`](development.md) and [`distribution.md`](distribution.md) |
| Where are Azure SDK/service pain points recorded? | [`service_feedback/`](service_feedback/README.md) |

## GitHub planning views

These links query the canonical GitHub state without copying it into repository checklists:

- [Foundry-first platform alignment milestone](https://github.com/jpalvarezl/Konductor/milestone/2) — the current
  priority: explicit Foundry composition, agent-kind lifecycle/capabilities, and Projects SDK integration.
- [Post-foundations hardening milestone](https://github.com/jpalvarezl/Konductor/milestone/1) — completed procedural
  hardening history; remaining unscheduled work is tracked by focused issues.
- [Ready work](https://github.com/jpalvarezl/Konductor/issues?q=is%3Aopen+label%3Astatus%3Aready) — accepted packets
  that can be claimed.
- [Work needing design](https://github.com/jpalvarezl/Konductor/issues?q=is%3Aopen+label%3Astatus%3Aneeds-design) —
  recorded outcomes whose contract is not ready for implementation.
- [Roadmap candidates](https://github.com/jpalvarezl/Konductor/issues?q=is%3Aopen+label%3Aroadmap) — discussed future
  work that is not yet committed or scheduled.

## Documentation map

**Delivery and development**

| Document | Purpose | Ownership |
|---|---|---|
| [`iterations/index.md`](iterations/index.md) | Registry of versioned delivery packets | living |
| [`iterations/README.md`](iterations/README.md) | Delivery-packet ownership and authoring rules | process |
| [`future.md`](future.md) | Durable unscheduled product direction | direction |
| [`development.md`](development.md) | Build/run, project layout, Foundry setup, debugging | development |
| [`distribution.md`](distribution.md) | `jpackage` bundles, release workflow, artifacts | development |
| [`hero-scenario.md`](hero-scenario.md) | End-to-end Prompt/Hosted and TUI/ACP scenarios | living |
| [`foundry-responses-evaluation.md`](foundry-responses-evaluation.md) | Azure Agents 2.2.0 Responses API recommendation (#60) | decision evidence |
| [`implementation-roadmap.md`](implementation-roadmap.md) | M0–M6 foundations plan | historical |
| [`burndown.md`](burndown.md) | M0–M6 and ACP foundations completion record | historical |
| [`service_feedback/`](service_feedback/README.md) | Azure SDK/service rough edges by feature | living |

**Stable design specification**

| Document | Purpose |
|---|---|
| [`spec/architecture.md`](spec/architecture.md) | Layers, domain model, provider/event seams, data flow, threading |
| [`spec/providers.md`](spec/providers.md) | Prompt provider, Foundry Responses seam/adapters, persisted PromptAgents |
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
2. If the task references an issue, read it for coordination and follow its delivery-packet link when one exists.
3. A packet is an implementation brief/context pack for accepted non-trivial work. Read its scope, non-goals,
   acceptance, exact spec headings, source symbols, tests, and searches.
4. Design/triage issues and tiny focused fixes may have no packet; use their exact references and route through the
   owning spec and targeted source/tests.
5. Use open issues and milestones for roadmap/status; read [`iterations/index.md`](iterations/index.md) only to locate
   a packet or historical evidence.
6. Expand the search only when the targeted context is incomplete or contradicted by source.

Do not read all of `docs/` or `src/` by default. For unplanned questions, locate the owning document before reading:

```bash
rg -il "<concept>" docs/spec docs/*.md
rg -n "^#{1,3} " docs/spec/<owner>.md
rg -n "<ExactSymbol|RelatedSymbol>" src/main/kotlin src/test/kotlin
```

Every document starts with a purpose statement. Read its top and relevant headings, not necessarily the whole file.

## Update rules

- Assignment, blockers, dependencies, and closure update the GitHub issue rather than the delivery packet.
- Scope, acceptance, targeted context, constraints, or validation changes update the delivery packet when one exists.
- Behavior changes update the owning spec in the same change.
- Newly discovered actionable work goes to a focused GitHub issue; durable unscheduled direction goes to `future.md`.
- Accepted non-trivial work gets a linked delivery packet when an offline contract/context pack adds value; tiny
  obvious fixes may remain packet-free. Related work may share a milestone.
- Completed packets stay at their stable paths and record their final pull requests.
- Azure SDK/service pain points go in `service_feedback/`, including the exact API/version, impact, workaround, and
  suggested fix.

## Key external references

- [pi](https://pi.dev) — local coding-agent design inspiration.
- [Azure AI Foundry documentation](https://learn.microsoft.com/en-us/azure/foundry/).
- `sdk/ai/{azure-ai-agents,azure-ai-projects}` in
  [Azure/azure-sdk-for-java](https://github.com/Azure/azure-sdk-for-java) — SDK source and samples.
