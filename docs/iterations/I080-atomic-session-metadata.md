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

Persisted rename and model changes replace a complete candidate header atomically without reserializing transcript
bytes or losing appends made through the same store instance.

## Scope

- Add a `SessionStore` operation that persists candidate metadata without requiring mutation of the live `Session`.
- Replace a JSONL header through a forced-and-closed sibling temporary file and same-filesystem atomic move; fail
  without replacement when atomic move is unsupported.
- Preserve the existing transcript bytes and ordering while replacing only the complete header.
- Serialize append, rewrite, and metadata replacement per session within one `JsonlSessionStore` instance.
- Commit live model and name metadata only after persistence succeeds.
- Deterministically exercise temporary-file failures, unsupported replacement, restart, and concurrent append behavior.

## Non-goals

- Model discovery, Foundry service calls, or TUI palette changes.
- Hosted lifecycle or binding changes.
- Two-phase PromptAgent binder + metadata commit, tracked separately by
  [issue #92](https://github.com/jpalvarezl/Konductor/issues/92).
- A generic transaction framework or support for concurrent writers using multiple stores or processes.

## Acceptance

- [x] `SessionStore` accepts immutable candidate metadata and the JSONL store never needs a pre-mutated live session for
  rename or model changes.
- [x] JSONL metadata updates close and force a complete sibling temporary file before atomic replacement; unsupported
  atomic move fails without replacing the accepted file.
- [x] Transcript bytes and order are unchanged by successful metadata updates.
- [x] One store instance serializes append, rewrite, and metadata replacement per session; multiple-store/process
  writers are explicitly unsupported.
- [x] Model switch and rename commit live values only after persistence succeeds.
- [x] `SessionStore.persistMetadata` is required and `NoOpSessionStore` explicitly implements no-op persistence.
- [x] Deterministic tests cover success, temporary-file write/replacement failures, best-effort cleanup, restart,
  concurrent append retention, and post-success-only in-memory commit.
- [x] The sessions specification states the implemented atomicity and durability limits and focused/full
  test/package validation passes.

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
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — model and rename metadata commits.

### Tests

- `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — byte preservation and deterministic filesystem
  failure boundaries.
- `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — model/name in-memory commit ordering.
- `src/test/kotlin/com/konductor/session/NoOpSessionStoreTest.kt` — explicit non-persistent behavior.

### Targeted searches

```bash
rg -n "persistHeader|rename\(|promptAgentName\s*=|modelName\s*=" src/main/kotlin src/test/kotlin
rg -n "SessionStore|JsonlSessionStore|SessionCodec" docs/spec/sessions.md src/main/kotlin src/test/kotlin
```

## Decisions and constraints

- Candidate metadata is immutable; rename and model changes leave the live `Session` unchanged until persistence
  returns.
- Metadata replacement changes only the header. Transcript entry bytes are copied rather than reserialized.
- The temporary file is a sibling so replacement stays on one filesystem. There is no non-atomic fallback.
- Candidate file contents are forced before replacement, but the parent directory is not forced; operating-system and
  power-failure durability is not guaranteed. Failed temporary-file cleanup is best-effort.
- No Azure SDK or Foundry service surface is involved, so this delivery cannot produce SDK/service feedback.

## Validation

```bash
./mvnw -q -Dtest=JsonlSessionStoreTest,NoOpSessionStoreTest,AgentLoopSessionTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
```

## Completion

[PR #89](https://github.com/jpalvarezl/Konductor/pull/89) delivers atomic-only sibling replacement, byte-exact
transcript preservation, same-instance per-session writer serialization, and post-persistence commits for model and
name changes. Two-phase PromptAgent binder + metadata commit remains follow-up
[issue #92](https://github.com/jpalvarezl/Konductor/issues/92); no Azure SDK/service feedback was required.
