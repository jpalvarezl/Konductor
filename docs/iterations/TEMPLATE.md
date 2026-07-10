---
id: I000
title: Replace with iteration title
status: ready
created: YYYY-MM-DD
updated: YYYY-MM-DD
issues: []
pull_requests: []
depends_on: []
---

# I000 — Replace with iteration title

One sentence describing the independently valuable outcome.

## Outcome

Describe what will be observably true when this iteration is complete.

## Scope

- Included behavior.
- Included integration surface.

## Non-goals

- Work deliberately excluded from this iteration.

## Acceptance

- [ ] A measurable behavior or scenario.
- [ ] Tests or validation that prove the behavior.
- [ ] Owning specs and user-facing docs describe the result.

## Context pack

Read these first and do not scan the whole repository unless they prove incomplete or incorrect.

### Specifications

- `docs/spec/example.md` — exact heading or contract.

### Source entry points

- `src/main/kotlin/.../Example.kt` — symbol and responsibility.

### Tests

- `src/test/kotlin/.../ExampleTest.kt` — existing coverage to extend.

### Targeted searches

```bash
rg -n "ExactSymbol|RelatedSymbol" src/main/kotlin src/test/kotlin
```

## Decisions and constraints

- Stable architectural or product constraints that shape this slice.
- Link issues or specs rather than copying their full content.

## Burndown

- [ ] First implementation unit.
- [ ] Integration and error handling.
- [ ] Tests and validation.
- [ ] Documentation and service feedback.

## Validation

```bash
./mvnw -q -Dtest=RelevantTest test
```

Add manual or live validation only when the behavior cannot be proved offline.

## Documentation impact

- Owning spec:
- User/developer docs:
- Service feedback:

## Completion

Record the merged PRs, final behavior, and any follow-ups promoted to `future.md` or a focused issue.
