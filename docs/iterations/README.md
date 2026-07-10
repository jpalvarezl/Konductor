# Iteration Workflow

Iterations are bounded delivery packets for work that is ready to implement. Every work item has exactly one
canonical home and, once scheduled, exactly one implementation checklist. Humans and agents start from that iteration,
then read only the spec sections, source entry points, and tests it links.

## Information ownership

| Information | Canonical location |
|---|---|
| Current implementation behavior | `src/` and tests |
| Active or ready implementation work | One file under `docs/iterations/` |
| Stable intended behavior | The owning `docs/spec/*.md` |
| Unscheduled ideas | [`docs/future.md`](../future.md) |
| Defect reports and design discussion | Focused GitHub issues |
| Completed foundations history | [`docs/implementation-roadmap.md`](../implementation-roadmap.md) and [`docs/burndown.md`](../burndown.md) |
| Azure SDK/service rough edges | [`docs/service_feedback/`](../service_feedback/README.md) |

Do not maintain actionable checklists for the same work in an issue, backlog entry, spec, and iteration. Link source
material, record only the decisions needed for the delivery slice, and remove the selected plan from `future.md` once
the iteration becomes ready. A linked GitHub issue may retain discussion and defect evidence, but the iteration is
the sole implementation tracker.

## Lifecycle

| Status | Meaning |
|---|---|
| `ready` | Scoped, accepted, and waiting to start |
| `active` | Implementation is in progress |
| `blocked` | Started, but cannot proceed; keep it under Active in the index and record the blocker |
| `completed` | Acceptance and documentation requirements are satisfied |
| `superseded` | Replaced by another iteration; list it under Completed and link the replacement |

Files keep a stable `I###-short-name.md` path and are never moved between status directories. The board in
[`index.md`](index.md) is only a routing index derived from each iteration's metadata; it must not repeat acceptance
criteria or task checklists.

## Creating an iteration

1. Confirm the work is no longer merely an idea. If it is still exploratory, keep it in `future.md` or a GitHub issue.
2. Copy [`TEMPLATE.md`](TEMPLATE.md) to the next sequential `I###-short-name.md`.
3. Define one coherent outcome with explicit non-goals and measurable acceptance checks.
4. Build the context pack: exact spec headings, source files or symbols, tests, and targeted search terms.
5. Remove or shorten the selected `future.md` entry so the iteration becomes the only implementation plan.
6. Add the iteration to the `ready` table in [`index.md`](index.md).

An iteration may span multiple pull requests, but each PR should link the iteration and update its checklist. Multiple
iterations may be active only when their source ownership and acceptance criteria do not overlap.

## Completing an iteration

Before marking an iteration `completed`:

- all acceptance checks are verified;
- the owning specs describe the resulting behavior rather than the old target;
- relevant CLI, README, hero-scenario, and service-feedback docs are updated;
- follow-up ideas are moved to `future.md` or a focused issue rather than left as unchecked implementation tasks;
- the iteration links the merged PRs and records a concise completion note.
