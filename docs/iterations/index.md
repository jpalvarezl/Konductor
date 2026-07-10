# Iterations

Routing index for Konductor delivery work. It is not a second tracker: status and checklists are owned by each linked
iteration file. Read this after [`AGENTS.md`](../../AGENTS.md), then open only the iteration relevant to the task.

## Active

No iteration is active.

## Ready

| Iteration | Outcome | Dependencies |
|---|---|---|
| [I001 — Workspace context and project trust](I001-workspace-context-and-trust.md) | Load repository instructions predictably while gating project-controlled runtime configuration | Foundations cycle |

## Completed

| Cycle | Outcome | Evidence |
|---|---|---|
| [I002 — Localized string assets](I002-localized-string-assets.md) | Locale-aware frontend resource catalog with stable command, protocol, prompt, and persistence identifiers | [PR #25](https://github.com/jpalvarezl/Konductor/pull/25) |
| Foundations — M0–M6 and ACP agent role | Prompt/Hosted providers, tools, sessions, compaction, TUI/ACP foundations, packaging | [roadmap](../implementation-roadmap.md) · [burndown](../burndown.md) |

## Intake

- Unscheduled ideas belong in [`future.md`](../future.md).
- Bugs and cross-cutting design discussions may start as GitHub issues.
- Once work is accepted and scoped, create an iteration and make it the sole implementation checklist.

Authoring and lifecycle rules: [`README.md`](README.md).
