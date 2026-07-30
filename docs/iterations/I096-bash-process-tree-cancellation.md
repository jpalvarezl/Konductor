---
id: I096
title: Bash process-tree cancellation
issue: https://github.com/jpalvarezl/Konductor/issues/96
created: 2026-07-30
---

# I096 — Bash Process-Tree Cancellation

Issue [#96](https://github.com/jpalvarezl/Konductor/issues/96) owns discussion, assignment, dependencies, status, and
closure. This packet is the repository-local implementation brief and context pack.

## Outcome

Cancelling a running `bash` tool call promptly stops its observable process tree and propagates coroutine cancellation,
while timeout cleanup returns the existing bounded partial-output error and normal completion remains unchanged.

## Scope

- Make `BashTool.execute` observe coroutine cancellation while waiting for the shell process.
- On timeout or cancellation, snapshot and terminate observable descendants and the root process with bounded cleanup.
- Drain process output in fixed-size chunks, incrementally preserve the prior CRLF/CR/LF normalization, retain only the
  bounded prefix, and continue discarding beyond the cap.
- Close process streams and join the output pump for a bounded interval during cleanup.
- Preserve partial captured output for timeout errors and rethrow `CancellationException` after cancellation cleanup.
- Add portable deterministic real-process tests with a JVM helper and atomically published PID handshakes for normal
  completion, mixed line endings, timeout, cancellation transparency, capped no-newline output, process churn, and
  observable descendant termination.
- Update the tools specification with lifecycle and persistence semantics.

## Non-goals

- Containment or termination guarantees for detached, reparented, or otherwise unobservable processes.
- Operating-system job objects, process groups, cgroups, or platform-specific process-tree managers.
- Changing normal command output, exit-code behavior, timeout limits, or output caps.
- Rolling back command side effects or persisting a synthetic tool result after cancellation.

## Acceptance

- [x] Coroutine cancellation initiates prompt process cleanup and is rethrown rather than converted to `ToolResult`.
- [x] Timeout and cancellation leave the helper and stable descendants reported by JVM PID handshakes no longer alive,
  including cancellation while a descendant is creating short-lived processes.
- [x] Timeout returns its bounded partial-output error; a no-newline stream is capped while still being drained, mixed
  CRLF/CR/LF output retains the prior LF-normalized behavior across chunks, and normal exit behavior is unchanged.
- [x] Portable real-process tests cover normal completion, timeout partial output, cancellation transparency and
  latency, and the observable descendant cleanup described above.
- [x] The tools specification describes cleanup ordering, side-effect/event semantics, and the explicit detached-process
  and OS-containment non-goals.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Built-in tool set`](../spec/tools.md#built-in-tool-set) — `bash` execution and timeout behavior.
- [`File-I/O & text truncation`](../spec/tools.md#file-io--text-truncation) — bounded output requirements.

### Source entry points

- `src/main/kotlin/com/konductor/tool/BashTool.kt` — `BashTool.execute`, process wait, output pump, and timeout
  cleanup.
- `src/main/kotlin/com/konductor/tool/RegistryToolExecutor.kt` — cancellation propagation at the executor boundary.
- `src/main/kotlin/com/konductor/tool/ToolSupport.kt` — bounded stream capture and final tool-result truncation.

### Tests

- `src/test/kotlin/com/konductor/tool/BashToolTest.kt` — new focused real-process lifecycle coverage.
- `src/test/kotlin/com/konductor/tool/RegistryToolExecutorTest.kt` — executor cancellation contract.

### Targeted searches

```bash
rg -n "BashTool|waitFor|destroyForcibly|CancellationException|truncateToolResult" \
  src/main/kotlin/com/konductor/tool src/test/kotlin/com/konductor/tool
```

## Decisions and constraints

- Java `ProcessHandle` descendants are the portable observation and termination mechanism. Cleanup snapshots currently
  observable descendants and cannot promise containment of detached or reparented processes.
- Cleanup remains bounded and keeps root termination independent of descendant snapshot ordering so process churn cannot
  omit root fallback cleanup.
- No OS job objects, process groups, or other platform-specific containment primitives are introduced.
- Cancellation may occur after external side effects. Those side effects are not rolled back; cancellation propagates
  and therefore does not create a completed bash `ToolResult` or normal completion event to persist.
- This work does not involve Azure SDK or Foundry service behavior and cannot produce service feedback.

## Validation

```bash
./mvnw -q -Dtest=BashToolTest,RegistryToolExecutorTest test
./mvnw -q test
node scripts/validate-docs.mjs
```

## Completion

Implemented cancellation-aware observable process-tree cleanup and fixed-size, line-ending-normalized output draining
with bounded capture. Timeout retains capped no-newline partial output, cancellation remains transparent, and portable
JVM process tests use complete atomically published PID handshakes while covering stable observable descendants during
timeout and process churn during cancellation, plus mixed line endings and normal exit. Focused and full tests and
documentation validation pass. Implementation review: [PR #106](https://github.com/jpalvarezl/Konductor/pull/106).
No follow-up issue or `future.md` entry was required.
