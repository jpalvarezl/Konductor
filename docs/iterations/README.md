# Delivery Packet Workflow

Delivery packets are bounded, repository-local implementation contracts. They let humans and agents work from a clone
without requiring GitHub access, while issues and milestones own collaboration and roadmap tracking.

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

## Creating scoped work

1. Create a focused GitHub issue for the outcome or defect. Use it for discussion, assignment, dependencies, and
   blockers.
2. Copy [`TEMPLATE.md`](TEMPLATE.md) to the next stable `I###-short-name.md` path.
3. Add the issue URL to the packet and link the packet from the issue.
4. Define one coherent outcome, explicit non-goals, and measurable acceptance criteria in the packet.
5. Build a small first-pass context route: exact spec headings/anchors, source symbols, focused tests, and targeted
   searches. Do not list whole directories or broad documents when a subsection or symbol is enough.
6. Remove actionable planning for the selected work from `future.md`; retain only durable unscheduled direction or move
   stable contracts to the owning spec.
7. Add a milestone only when the issue contributes to a concrete multi-item outcome or release. Register the packet in
   [`index.md`](index.md) so clone-only agents can locate it.

Large outcomes should use a focused parent issue with sub-issues or dependencies. Do not accumulate unrelated work or
repeated checklists in a single issue.

## Claiming and delivering work

- Claim work by assigning the issue before implementation. For concurrent collaboration, a draft pull request is the
  strongest visible signal that work has started.
- Use issue assignment and status labels to signal ownership and phase; keep the issue open until packet acceptance is
  met.
- A packet may span multiple pull requests. Each PR links the canonical issue and packet, updates the owning specs when
  behavior changes, and reports validation without copying the packet checklist.

## Completing work

Before the final PR closes the issue:

- all packet acceptance criteria are verified;
- source/tests and owning specs describe the same behavior;
- relevant CLI, README, hero-scenario, and service-feedback docs are updated;
- excluded follow-ups are recorded as focused issues or durable entries in `future.md`;
- the completed packet records the final PR and a concise result.

Completed packets keep their stable paths as versioned implementation history. They are not migrated retroactively to
new metadata conventions unless they become active again.
