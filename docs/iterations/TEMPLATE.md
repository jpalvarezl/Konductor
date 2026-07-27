---
id: I123
title: Replace with delivery title
issue: https://github.com/jpalvarezl/Konductor/issues/123
created: YYYY-MM-DD
---

# I123 — Replace with Delivery Title

Name this packet from its owning issue number: issue #123 becomes `I123-short-name.md`. The linked GitHub issue owns
discussion, assignment, dependencies, blockers, status, and closure. This packet is the repository-local implementation
brief and context pack.

## Outcome

One sentence describing what will be observably true when this delivery completes.

## Scope

- Included behavior.
- Included integration surface.

## Non-goals

- Work deliberately excluded from this delivery.

## Acceptance

- [ ] A measurable behavior or scenario.
- [ ] Tests or validation that prove the behavior.
- [ ] Owning specs and user-facing docs describe the result.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`System layers`](../spec/architecture.md#system-layers) — replace this example with the exact contract affected by
  the work.

### Source entry points

- `src/main/kotlin/.../Example.kt` — `ExactSymbol`, its responsibility, and why it is in scope.

### Tests

- `src/test/kotlin/.../ExampleTest.kt` — existing focused coverage to extend.

### Targeted searches

```bash
rg -n "ExactSymbol|RelatedSymbol" src/main/kotlin src/test/kotlin
```

## Decisions and constraints

- Stable architectural or product constraints that shape this slice.
- Link issues or specs rather than copying their discussion or full content.

## Validation

```bash
./mvnw -q -Dtest=RelevantTest test
```

Add manual or live validation only when the behavior cannot be proved offline.

## Completion

Record the final pull request, resulting behavior, and follow-ups promoted to `future.md` or focused issues. Use
`Related to #` on intermediate PRs and `Closes #` only on the final acceptance-completing PR.
