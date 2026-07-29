---
id: I080
title: Atomic session metadata updates
issue: https://github.com/jpalvarezl/Konductor/issues/80
created: 2026-07-29
---

# I080 — Atomic Session Metadata Updates

Issue [#80](https://github.com/jpalvarezl/Konductor/issues/80) owns discussion, assignment, dependencies, status, and
closure. This packet is the repository-local implementation brief and context pack.

## Outcome

Persisted session metadata changes leave both memory and restart state complete and consistent when writing a candidate
header fails before replacement.

## Scope

- Add a `SessionStore` operation that persists candidate metadata without requiring mutation of the live `Session`.
- Replace a JSONL header through a closed sibling temporary file and same-filesystem atomic move where supported.
- Preserve the existing transcript bytes and ordering while replacing only the complete header.
- Commit live model, name, and PromptAgent binding metadata only after persistence succeeds.
- Deterministically exercise creation, temporary-write, and replacement failures and complete old-or-new restart state.

## Non-goals

- Model discovery, Foundry service calls, or TUI palette changes.
- Hosted lifecycle or binding changes.
- A generic transaction framework or changing append-only transcript entry writes.

## Acceptance

- [x] `SessionStore` accepts immutable candidate metadata and the JSONL store never needs a pre-mutated live session.
- [x] JSONL metadata updates close and force a complete sibling temporary file before atomic replacement, with a
  documented non-predelete fallback when `ATOMIC_MOVE` is unavailable.
- [x] Transcript bytes and order are unchanged by successful metadata updates.
- [x] Model switch, rename, and PromptAgent binding commit their live values only after persistence succeeds.
- [x] Deterministic tests cover success, failure before temporary write, temporary-write failure, replacement
  failure/cleanup, complete old-or-new restart state, and post-success-only in-memory commit.
- [x] The sessions specification matches the implemented persistence contract and focused/full test/package validation
  passes.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Storage`](../spec/sessions.md#storage), [`SessionStore API`](../spec/sessions.md#sessionstore-api), and
  [`Persisted agents & resume`](../spec/sessions.md#persisted-agents--resume).
- [`Foundry-first platform alignment`](I055-foundry-first-platform-alignment.md#decisions-and-constraints) — preserve
  the Foundry-only architecture and focused test seams.

### Source entry points

- `src/main/kotlin/com/konductor/core/models/Session.kt` — mutable live metadata and immutable candidate shape.
- `src/main/kotlin/com/konductor/session/SessionStore.kt` — candidate metadata persistence contract and rename path.
- `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt` — sibling temporary-file write and replacement.
- `src/main/kotlin/com/konductor/session/SessionCodec.kt` — complete candidate header serialization.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — model, rename, and PromptAgent metadata commits.
- `src/main/kotlin/com/konductor/conversation/PromptAgentCommand.kt` and
  `src/main/kotlin/com/konductor/tui/TuiApp.kt` — persist-before-bind ordering.

### Tests

- `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — byte preservation and deterministic filesystem
  failure boundaries.
- `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — model/name/PromptAgent in-memory commit ordering.
- `src/test/kotlin/com/konductor/conversation/PromptAgentCommandTest.kt` — binding/status commit ordering.

### Targeted searches

```bash
rg -n "persistHeader|rename\(|promptAgentName\s*=|modelName\s*=" src/main/kotlin src/test/kotlin
rg -n "SessionStore|JsonlSessionStore|SessionCodec" docs/spec/sessions.md src/main/kotlin src/test/kotlin
```

## Decisions and constraints

- Candidate metadata is immutable; the live `Session` remains unchanged until `SessionStore` persistence returns.
- Metadata replacement changes only the header. Transcript entry append behavior and bytes are not reserialized.
- The temporary file is a sibling so replacement stays on one filesystem. The fallback never predeletes the target.
- No Azure SDK or Foundry service surface is involved, so this delivery cannot produce SDK/service feedback.

## Validation

```bash
./mvnw -q -Dtest=JsonlSessionStoreTest,AgentLoopSessionTest,PromptAgentCommandTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
```

## Completion

[PR #89](https://github.com/jpalvarezl/Konductor/pull/89) delivers immutable candidate metadata persistence,
forced-and-closed sibling replacement with byte-exact transcript preservation, and post-success commits for model,
name, and PromptAgent binding changes. No follow-up or Azure SDK/service feedback was required.
