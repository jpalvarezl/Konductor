---
id: I081
title: Background TUI command cancellation
issue: https://github.com/jpalvarezl/Konductor/issues/81
created: 2026-07-30
---

# I081 — Background TUI Command Cancellation

Issue #81 owns discussion, assignment, dependencies, blockers, status, and closure. This packet is the
repository-local implementation brief and context pack.

## Outcome

The TUI identifies agent turns and local background commands separately and reports cancellation according to one
race-free command commit boundary: cancellation accepted before commit prevents command mutation, while a request after
commit has begun cannot claim cancellation or rollback and the command reports its natural completion or failure.

## Scope

- Give asynchronous agent turns and `CommandAction.Background` work distinct submission kinds, ownership, localized
  cancellation copy, and terminal reporting.
- Add one controller-owned, atomic local-command state machine that linearizes cancellation against commit and remains
  authoritative until the job has fully unwound.
- Treat background preparation as cancellable and side-effect-free with respect to Konductor-owned durable session
  state, active runtime/session/binding state, and command-result UI state. Put persistence and the corresponding live
  mutation inside the winning non-cancellable commit phase.
- Apply the contract to `/new`, `/resume <number|id>`, `/compact`, every `/model` form, and `/connections`, including
  cancellation during catalog/provider I/O, at the commit boundary, and during persistence.
- Define repeated `Esc`, graceful TUI shutdown, stale job completion, and attempted command supersession.
- Preserve model option-picker generation cancellation and insertion-for-confirmation as a separate frontend concern;
  define how submitted `/model` discovery enters the local-command state machine.
- Add deterministic focused tests and semantic `AppStrings` copy for agent-turn versus local-command cancellation.
- Reconcile the TUI and architecture specs with the implemented render-lock/state-applier path, inert input, and the
  absence of a steering, follow-up, command, or UI-event queue.

## Non-goals

- Rolling back filesystem, service, tool, provider, or other external effects that already happened.
- Changing the agent turn's partial-transcript contract: an accepted user entry and completed tool entries remain
  persisted after cancellation.
- Changing ACP cancellation or adding slash-command/discovery protocol surfaces to ACP.
- Adding steering, queued prompts, queued commands, command supersession, or restoration of submitted text to the
  composer.
- Changing startup model bootstrap/selector cancellation, command-palette staging behavior, or using a palette snapshot
  to authorize a later `/model` submission.
- Moving currently immediate commands such as `/name`, `/session`, and `/agent` onto background execution. PromptAgent
  binder/persistence coordination remains owned by issue #92.
- Weakening the atomic metadata and transcript candidate guarantees in I080, inventing file rollback, or adding a
  persistence transaction across local files and Foundry resources.

## Acceptance

- [ ] `ConversationController.Submission` distinguishes an agent turn from a local background command; `TuiApp` keeps
  one exact active-submission identity and uses kind-specific cancellation/reporting rather than naming every job a
  turn.
- [ ] A local command has one atomic state owner. Its paths are `Running -> Cancelling -> Cancelled`,
  `Running -> Committing -> Completed|Failed`, or `Running -> Failed` for an unexpected preparation error that wins
  its race with cancellation. `Esc` and `beginCommit` compare-and-set the same owner. Parent-scope/graceful-shutdown
  cancellation goes through that owner before cancelling the job.
- [ ] If interactive `Esc` wins while `Running`, the job is cancelled, no command persistence, live runtime, session,
  or binding mutation, `AppState` result mutation, or ordinary command result is published, and exactly one localized
  command-cancelled line is added only after unwind. Input remains inert through `Cancelling`. Graceful shutdown uses
  the same prevention guarantee but may omit presentation because the frontend is closing.
- [ ] If `beginCommit` wins, the complete command commit runs in `NonCancellable`; later/repeated `Esc` does not cancel
  the job or publish cancellation. Persistence success produces the normal command completion report; persistence or
  another commit failure produces the normal command failure report.
- [ ] Local persistence precedes its matching live in-memory and presentation commit. Expected validation/fallible
  preparation precedes durable publication; expected post-persistence in-memory assignments are infallible. An
  unexpected post-persistence failure reports the accepted durable state for reconciliation and never claims rollback.
- [ ] During interactive operation, the local-command report (success, failure, or cancellation), status-bar, model,
  and token changes plus `isAwaitingResponse = false` are applied under the presentation lock in that order. The exact
  active identity is cleared last, so no new submission can overtake terminal reporting.
- [ ] Read-only `/model`, `/model list`, and `/connections` use result publication as their empty commit; cancellation
  before publication suppresses the result. `/model <deployment>` performs restriction checks and catalog lookup while
  cancellable, then commits metadata, live model/context, status, and copy in order. Discovery outage fallback is only
  a prepared outcome and cannot switch the model if cancellation won.
- [ ] `/new` prepares a detached candidate before its gate; `/resume <number|id>` loads and validates a detached
  candidate before its gate; and `/compact` summarizes/builds a candidate transcript before its gate. Their first
  durable, provider-binding, active-session, or live-transcript mutation occurs only after `beginCommit`, and their
  existing persistence/lifecycle contracts define the commit ordering.
- [ ] Agent-turn `Esc` remains a cancellation request, emits exactly one turn-specific cancellation line after unwind,
  and retains already persisted user/tool entries and real external side effects. It never presents those effects as
  rolled back.
- [ ] Repeated `Esc` is idempotent. While an active submission is `Running`, `Cancelling`, or `Committing`, composer
  editing/submission and palette triggers stay inert; scrolling and graceful-exit keys retain their documented
  behavior. No second command supersedes the first, and a stale completion cannot clear or report for a newer identity.
- [ ] Palette option loads remain separately generation-owned: close/replacement cancels only that load, late results
  are ignored, and selection only stages exact command text. Startup model selection remains separate. A staged model
  command receives fresh execution-time discovery/validation when submitted.
- [ ] Graceful shutdown cancels a pre-commit command through its state owner and may use the existing bounded wait for
  non-cooperative preparation; the closed command can never later commit. A command already in `Committing` is not
  cancelled, and graceful shutdown waits for its bounded commit operations and terminal state before closing runtime
  resources. Forced JVM/OS termination provides only the guarantees documented by the active `SessionStore` and may
  prevent UI reporting; it does not imply rollback.
- [ ] Deterministic latch/gate tests cover agent-turn versus command submission kinds and copy, cancellation during
  catalog/provider I/O, cancellation immediately before commit, `Esc` during persistence, persistence failure after
  commit begins, repeated `Esc`, post-commit completion, inert supersession attempts, palette independence, and both
  graceful-shutdown branches without sleep-based race assertions.
- [ ] Focused tests, the full suite, docs validation, and diff checks pass; owning TUI, architecture, and session specs
  agree with the implemented behavior.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Event loop & coroutine marshalling`](../spec/tui.md#event-loop--coroutine-marshalling) and
  [`Keybindings`](../spec/tui.md#keybindings) — render-lock marshalling, inert input, active-submission cancellation,
  and shutdown behavior.
- [`Slash-commands`](../spec/tui.md#slash-commands) — background command execution, picker separation, model
  discovery, and command-specific commit points.
- [`Threading & concurrency`](../spec/architecture.md#threading--concurrency) — frontend/core single-flight and
  cancellation ownership.
- [`Storage`](../spec/sessions.md#storage) and [`SessionStore API`](../spec/sessions.md#sessionstore-api) — metadata
  and transcript candidate persistence before live commits, with no non-atomic fallback or rollback fiction.

### Source entry points

- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — `Submission`, `submitAsync`,
  `launchBackground`, `runNew`, `runResume`, and `runCompact`; split submission identity and prepare local outcomes for
  the command commit coordinator.
- `src/main/kotlin/com/konductor/conversation/CommandRegistry.kt` — `CommandAction.Background`; retain command-owned
  suspend work without giving commands coroutine/job ownership.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — `activeTurn`, `handleKey`, `cancelActiveTurn`, `submitInput`, and
  `eventLoop`; replace turn-only ownership/copy with exact active-submission state and coordinated shutdown.
- `src/main/kotlin/com/konductor/conversation/FoundryDiscoveryCommand.kt` — `evaluateModel`, `switchModel`,
  `discoverDeployments`, and `publish`; separate cancellable preparation from read-only or model-mutation commit.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — `newSession`, `resume`, `compact`, `switchModel`, and
  `recordCompaction`; expose detached preparation/candidate commits without weakening loop single-flight.
- `src/main/kotlin/com/konductor/session/SessionStore.kt` and
  `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt` — `persistMetadata`, `rewrite`, and session
  publication; preserve persistence-first candidate guarantees and provide deterministic commit gates in tests rather
  than file rollback.
- `src/main/kotlin/com/konductor/tui/palette/CommandPaletteCoordinator.kt` — `launchLoad` and `cancelLoad`; retain
  generation-owned picker cancellation outside active submissions.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and
  `src/main/resources/com/konductor/i18n/messages.properties` — semantic, distinct turn/command cancellation copy.

### Tests

- `src/test/kotlin/com/konductor/conversation/ConversationControllerTest.kt` — replace the tests that currently assert
  discovery and compact return `Submission.Turn`; exercise distinct identities, terminal ordering, and command gates.
- `src/test/kotlin/com/konductor/conversation/FoundryDiscoveryCommandTest.kt` — extend `cancelsBeforeSwitch` with
  immediately-before-commit, persistence-in-progress, failure, and post-commit races.
- `src/test/kotlin/com/konductor/conversation/SessionCommandsTest.kt` — `/new`, `/resume`, and `/compact` detached
  preparation, cancellation, and active-session/transcript publication.
- `src/test/kotlin/com/konductor/tui/TuiAppKeyTest.kt` — repeated `Esc`, inert input/supersession, kind-specific
  reports, exact active identity, and graceful shutdown.
- `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — preserve
  `modelAndRenameCommitOnlyAfterPersistence`, persistence-failure behavior, Hosted session switching, and cancelled
  partial-turn persistence while adding candidate/gate coverage.
- `src/test/kotlin/com/konductor/agent/AgentLoopCompactionTest.kt` — preserve failed-rewrite/live-order behavior and
  add cancellation immediately before versus after rewrite begins.
- `src/test/kotlin/com/konductor/tui/palette/CommandPaletteCoordinatorTest.kt` — preserve independent load
  cancellation, stale-generation suppression, and insertion-only behavior.
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — root and fallback catalog coverage for distinct
  cancellation messages.

### Targeted searches

```bash
rg -n "Submission\.Turn|activeTurn|cancelActiveTurn|turnCancelled|CommandAction\.Background" \
  src/main/kotlin src/test/kotlin
rg -n "switchModel|persistMetadata|recordCompaction|newSession|resume\(" \
  src/main/kotlin/com/konductor/{agent,conversation,session} \
  src/test/kotlin/com/konductor/{agent,conversation,session}
rg -n "queue|queued|restore|inert|option load|active job" \
  docs/spec/{tui,architecture,sessions}.md src/main/kotlin/com/konductor/tui
```

## Decisions and constraints

### Submission identities

The active TUI work slot is a sealed identity, not a bare `Job`:

- **Agent turn** — invokes the shared `AgentLoop`, may persist incremental transcript entries and execute tools before
  cancellation, and uses turn-specific cancellation copy.
- **Local background command** — prepares a command result/candidate, owns the commit state below, and uses
  command-specific cancellation copy.
- **Palette option load** — is not a submission. It remains overlay/generation-owned, cannot mutate domain state, and
  never emits transcript cancellation copy.

The TUI accepts only one agent-turn or local-command identity at a time. Submitted text is consumed, not saved as a
follow-up and not restored on cancellation. Palette draft restoration applies only while an overlay owns pre-submission
input.

### Local-command state machine

One atomic state reference owned by the local-command submission is the linearization authority:

```text
                         beginCommit CAS wins
Running ------------------------------------------------> Committing
   |                |                                        |    |
   |                +--> Failed (unexpected preparation)     |    +--> Failed
   | requestCancel CAS wins                                  +-------> Completed
   v
Cancelling -- job has fully unwound --> Cancelled
```

`requestCancel` first changes `Running` to `Cancelling` and only then cancels the job. `beginCommit` changes the same
`Running` value to `Committing`. It calls `ensureActive` before the compare-and-set, and every supported parent/shutdown
cancellation must call `requestCancel` rather than cancelling the child behind the state owner's back. Exactly one can
win. The commit winner executes under `NonCancellable`; the cancellation winner cannot enter command commit even when a
blocking SDK call returns after ignoring interruption.

Handled discovery/validation errors are prepared command outcomes and cross the publication gate like successes. An
unexpected preparation exception races cancellation for one terminal failure report. Commit exceptions end in
`Failed`, not `Cancelled`, even if the user pressed `Esc` after commit began. Finalizers compare the exact submission
identity before reporting or clearing the active slot.

### Command commit points

- **`/model`:** prepare the active-model presentation; commit immediately before publishing the read-only result.
- **`/model list`:** prepare catalog lookup and presentation; commit immediately before publishing the read-only
  result.
- **`/connections`:** prepare connection lookup and safe presentation; commit immediately before publishing the
  read-only result.
- **`/model <deployment>`:** prepare capability restriction, exact validation or outage-fallback decision, and immutable
  metadata/context candidates; commit immediately before metadata persistence, then loop/context and TUI status/copy.
- **`/new`:** prepare a detached candidate and local validation; commit before the first session publication, binding/
  lifecycle mutation, active-session replacement, or TUI reset required by the owning session contract.
- **`/resume <number|id>`:** prepare index/id resolution, detached load, schema/runtime/binding validation, and
  non-mutating lookup; commit before the first binding/lifecycle mutation or active-session/TUI replacement.
- **`/compact [instructions]`:** prepare single-flight acquisition, summary generation, cut planning, and the immutable
  transcript candidate; commit immediately before `SessionStore.rewrite`, then the same live entry order and TUI update.

Preparation can issue read-only catalog/service requests and compaction inference, but it does not mutate
Konductor-owned durable state, the selected provider/session/binding, or command-result `AppState`. Any remote lifecycle
operation that can create/change a resource belongs after the commit gate. Once the gate is crossed, the command's
owning session/provider contract determines persistence and lifecycle ordering; I081 adds cancellation linearization,
not a cross-system transaction.

### Persistence, presentation, and exit

A command commit orders durable local persistence before matching live memory and `AppState`. The coordinator then adds
one natural success/failure report and clears the busy flag/active identity last under the render lock. Cancellation
adds one command-cancelled report only after pre-commit work has unwound, then clears busy/identity. Repeated `Esc` and
`Esc` during `Committing` are no-ops.

Expected failures after local persistence are eliminated by preparing immutable candidates and keeping the remaining
assignments infallible. If an unexpected failure occurs after persistence or a Foundry lifecycle mutation, report what
committed and leave reconciliation to the owning session/provider contract. Never delete an accepted header, rewrite
old metadata, claim service rollback, or show cancellation for a commit that won.

On graceful exit, a `Running` local command is moved to `Cancelling` before its job is cancelled; bounded shutdown may
stop waiting for non-cooperative preparation, but its state prevents any late commit. `Committing` work is allowed to
finish before dependent runtime resources and the terminal are closed; operations admitted to commit must therefore
have their own finite bounds rather than relying on the old generic turn cancellation timeout. A forced process death
can prevent completion copy and is governed solely by the active store/service durability guarantees.

## Validation

```bash
FOCUSED_TESTS=ConversationControllerTest,FoundryDiscoveryCommandTest,SessionCommandsTest,TuiAppKeyTest
FOCUSED_TESTS=$FOCUSED_TESTS,AgentLoopSessionTest,AgentLoopCompactionTest
FOCUSED_TESTS=$FOCUSED_TESTS,CommandPaletteCoordinatorTest,AppStringsTest
./mvnw -q -Dtest=$FOCUSED_TESTS test
./mvnw -q test
node scripts/validate-docs.mjs
git diff --check
```

No live Foundry run is required: controlled catalogs, providers, stores, and coroutine gates prove every race and
ordering offline.

## Completion

Record the final implementation pull request, the resulting cancellation/commit behavior, and any follow-ups promoted
to focused issues. Use `Related to #81` on design or intermediate pull requests and `Closes #81` only when all
acceptance criteria are implemented and verified.
