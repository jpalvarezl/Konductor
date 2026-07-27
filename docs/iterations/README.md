# Delivery Packet Workflow

A **delivery packet** is a small repository-local implementation brief and context pack for accepted work. It says
what outcome is in scope, what is excluded, how completion is proved, and exactly which spec headings, source symbols,
and tests an agent should read first. It is not a sprint, roadmap, status board, or extra issue checklist.

Packets let humans and agents implement non-trivial work from a clone without requiring GitHub access, while issues and
milestones own collaboration and roadmap tracking. Design discussions, triaged defects, and unscheduled roadmap
candidates do not need packets. A tiny obvious fix may use only a focused issue and PR when a separate offline contract
would add no useful context.

## Information ownership

| Information | Canonical location |
|---|---|
| Current implementation behavior | `src/` and tests |
| Work discussion, assignment, dependencies, blockers, priority/status labels, closure | Focused GitHub issue |
| Related outcome or release progress | GitHub milestone |
| Scope, non-goals, acceptance, targeted context, constraints, validation | One `docs/iterations/I###-*.md` packet |
| Stable intended behavior | Owning `docs/spec/*.md` |
| Durable unscheduled product direction | [`docs/future.md`](../future.md) |
| Completed foundations history | [`docs/implementation-roadmap.md`](../implementation-roadmap.md) and [`docs/burndown.md`](../burndown.md) |
| Azure SDK/service rough edges | [`docs/service_feedback/`](../service_feedback/README.md) |

Each field has one owner. Issues link delivery packets instead of copying their acceptance criteria; packets link issues
instead of copying discussion, assignees, priority, status, or blockers. Pull requests link both and do not introduce a
third checklist.

## Reading a packet

When the issue or packet is already known, open it directly; do not read [`index.md`](index.md) first. Read only the
exact spec headings, source symbols, tests, and searches listed in the packet. Expand beyond that first-pass context
only when it is incomplete or contradicted by source.

The issue is useful coordination context but is not required for implementation. The packet must remain sufficient for
an offline or restricted agent to understand the accepted scope and prove completion.

## Promoting an issue to scoped work

1. Create a focused GitHub issue in `status:triage` or `status:needs-design`. Use it for discussion, assignment,
   dependencies, and blockers; a packet is not required at intake.
2. Resolve the product/architecture decisions needed to make the outcome implementable.
3. For accepted non-trivial work, copy [`TEMPLATE.md`](TEMPLATE.md) to
   `I<issue-number>-short-name.md`, zero-padding the number to at least three digits. Issue #31 therefore owns `I031`.
   This makes packet allocation deterministic when multiple agents work concurrently. Existing packet paths are stable
   historical identifiers and are not renamed to match this convention.
4. Add the issue URL to the packet and link the packet from the issue.
5. Define one coherent outcome, explicit non-goals, and measurable acceptance criteria in the packet.
6. Build a small first-pass context route: exact spec headings/anchors, source symbols, focused tests, and targeted
   searches. Do not list whole directories or broad documents when a subsection or symbol is enough.
7. Remove actionable planning for the selected work from `future.md`; retain only durable unscheduled direction or move
   stable contracts to the owning spec.
8. Register the packet in [`index.md`](index.md), then replace the issue's intake/design status with `status:ready`.
9. Add a milestone only when the issue contributes to a concrete multi-item outcome or release.

A tiny, obvious fix can skip the packet when its issue already provides enough context and no stable scope or design
contract is needed. Large outcomes should use a focused parent issue with sub-issues or dependencies. Do not accumulate
unrelated work or repeated checklists in one issue.

## Claiming and delivering work

- Claim work by assigning the issue and replacing `status:ready` with `status:in-progress`. For concurrent
  collaboration, a draft pull request is the strongest visible signal that work has started.
- Keep the issue open until packet acceptance is met, or until the focused issue/PR contract is met for an explicitly
  packet-free small fix.
- A packet may span multiple pull requests. Intermediate PRs use `Related to #`; only the final acceptance-completing
  PR uses `Closes #`. Each PR links the packet when one exists, updates owning specs when behavior changes, reports
  validation, and records newly discovered follow-ups without copying the packet checklist.

## Completing work

Before the final PR closes the issue:

- all packet acceptance criteria are verified;
- source/tests and owning specs describe the same behavior;
- relevant CLI, README, hero-scenario, and service-feedback docs are updated;
- excluded follow-ups are recorded as focused issues or durable entries in `future.md`;
- the completed packet records the final PR and a concise result.

Completed packets keep their stable paths as versioned implementation history. They are not migrated retroactively to
new metadata conventions unless they become active again.
