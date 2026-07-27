---
id: I031
title: GitHub planning and workflow hardening
issue: https://github.com/jpalvarezl/Konductor/issues/31
created: 2026-07-27
---

# I031 — GitHub Planning and Workflow Hardening

Issue [#31](https://github.com/jpalvarezl/Konductor/issues/31) owns discussion, assignment, dependencies, blockers,
status, and closure. This packet is the repository-local implementation brief: it records the accepted outcome,
boundaries, smallest useful context route, and proof required to complete the work.

## Outcome

GitHub exposes current hardening and roadmap work, while repository guidance gives agents a small, unambiguous route
from issue to implementation and CI detects broken packet links or metadata.

## Scope

- Record current architecture/process defects and durable roadmap candidates as focused GitHub issues with useful
  status, area, and milestone labels.
- Link repository future-direction and implementation-status documentation to the owning issues without copying
  GitHub status into docs.
- Define delivery packets in plain language and limit them to accepted, non-trivial work that benefits from an offline
  implementation brief and targeted context pack.
- Make issue-first promotion deterministic by deriving new packet IDs from GitHub issue numbers.
- Make issue forms start in triage/design rather than requiring a packet before issue creation.
- Distinguish intermediate (`Related to`) and final (`Closes`) pull requests and require explicit follow-up reporting.
- Validate local Markdown links/anchors, packet metadata, issue-number naming, and packet-registry coverage in CI.

## Non-goals

- Prioritizing or scheduling every roadmap candidate.
- Creating delivery packets for unscheduled roadmap or unresolved design issues.
- Implementing the architecture and feature issues opened by this delivery.
- Adding a GitHub Project or duplicating GitHub status in repository checklists.
- Changing application runtime behavior.

## Acceptance

- [x] Current hardening work is grouped in a multi-item milestone and future candidates are queryable by label.
- [x] Architecture defects found in the review have focused issues with evidence and appropriate design/triage state.
- [x] `docs/future.md` and affected specs link to GitHub ownership and no longer carry priority metadata.
- [x] Repository guidance explains what a delivery packet is, when one is optional, and how issue-first promotion works.
- [x] New packets use the owning issue number, avoiding concurrent sequential-ID allocation.
- [x] Issue and PR templates represent triage, intermediate PRs, final closure, validation, and follow-up capture.
- [x] CI validates documentation routes and delivery-packet metadata with a dependency-free script.
- [x] Documentation validation and `git diff --check` pass locally.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by repository state.

### Process contracts

- [`Read this first: route through the task`](../../AGENTS.md#read-this-first-route-through-the-task) — agent entry
  route and repository ownership rules.
- [`Delivery Packet Workflow`](README.md) — packet purpose, promotion, delivery, and completion.
- [`Finding things fast`](../index.md#finding-things-fast) — documentation router for unplanned work.

### GitHub and automation entry points

- `.github/ISSUE_TEMPLATE/implementation.yml` — issue-first implementation intake and initial status.
- `.github/ISSUE_TEMPLATE/bug.yml` and `design.yml` — defect/design intake state.
- `.github/PULL_REQUEST_TEMPLATE.md` — issue/packet linkage, closure, validation, and follow-ups.
- `.github/workflows/ci.yml` — documentation validation plus application build/test/smoke.
- `scripts/validate-docs.mjs` — dependency-free local-link, anchor, packet, and registry checks.

### Documentation entry points

- `docs/index.md` — canonical GitHub planning queries without copied status.
- `docs/future.md` — durable direction linked to roadmap issues.
- `docs/iterations/index.md` — offline packet registry.
- `docs/iterations/I001-workspace-context-and-trust.md` — current packet whose unresolved decisions gate readiness.
- `docs/spec/architecture.md`, `compaction.md`, and `hosted-agents.md` — implementation-status corrections found by
  the architecture review.

### Targeted searches

```bash
rg -n "delivery packet|status:ready|status:needs-design|Closes #|Related to #" \
  AGENTS.md docs .github
rg -n "Value:|Effort:|not implemented|issue #[0-9]+" docs/future.md docs/spec docs/distribution.md
node scripts/validate-docs.mjs
```

## Decisions and constraints

- A delivery packet is an implementation brief/context pack, not a roadmap, sprint, or second status tracker.
- Design discussions, triaged bugs, and unscheduled roadmap candidates do not need packets.
- Accepted non-trivial work gets a packet when stable scope, acceptance, targeted context, or offline execution guidance
  would materially reduce agent discovery. A tiny obvious fix may proceed with an issue and PR alone.
- New packet IDs derive from the owning issue number, zero-padded to at least three digits: issue #31 becomes `I031`.
  Existing I001/I002 paths remain stable historical identifiers and are not renamed.
- GitHub labels/milestones remain the only current-status source; packets contain no assignment, priority, or burndown.
- CI validates repository structure, not live GitHub state, so clones and forks remain deterministic.

## Validation

```bash
node scripts/validate-docs.mjs
git diff --check
```

Application tests are not required for documentation/process-only changes; CI continues to run the full package and
shaded-jar smoke test.

## Completion

Proposed in [PR #54](https://github.com/jpalvarezl/Konductor/pull/54). Follow-up implementation remains in the focused
architecture and roadmap issues; none of those issues is implicitly accepted or scheduled by this delivery.
