---
id: I092
title: PromptAgent binding commit and reconciliation
issue: https://github.com/jpalvarezl/Konductor/issues/92
created: 2026-07-30
---

# I092 — PromptAgent Binding Commit and Reconciliation

Issue [#92](https://github.com/jpalvarezl/Konductor/issues/92) owns assignment, discussion, dependencies, status, and
closure. This packet is the repository-local implementation brief and context pack. It composes with the provisional
`SessionStore.newCandidate` / `persistNew` publication API contract already merged in
[I001](I001-workspace-context-and-trust.md#tui-startup-session-policy); implementation must not restore early durable
session creation on a branch where those source symbols have not landed yet.

## Outcome

A PromptAgent binding change has one durable decision point and no rollback protocol: prepare an unpublished provider
delegate, atomically persist the exact normalized name, then perform only non-failing in-process commits to provider,
live `Session`, and displayed status. Fresh sessions publish their complete binding in their first header. Resume treats
an accepted header as authoritative and prepares its exact name; an omitted `promptAgentName` field decodes to in-memory
`null` and selects the ephemeral unbind.

## Scope

- Replace immediate `PromptAgentBinder.bindAgent` mutation with a PromptAgent-specific prepare/commit handle.
- Serialize PromptAgent binding changes, turns, and live session changes in the existing `AgentLoop` operation boundary.
- Coordinate `/agent use` and the adoption half of `/agent create` with `SessionStore.persistMetadata`.
- Carry configured/current PromptAgent names into I001 provisional fresh candidates before `persistNew` at startup and
  `/new`; do not publish an unbound header and patch it afterward.
- Prepare the exact persisted name before resume commits the provider and live session; an absent name explicitly
  prepares the ephemeral adapter.
- Make the status-bar name and success/failure copy reflect only a committed result.
- Define local failure, process-interruption, remote-create ambiguity, and restart reconciliation outcomes.
- Add deterministic tests for every boundary without requiring Foundry or filesystem timing races.

## Non-goals

- A generic transaction, unit-of-work, rollback, or write-ahead-log framework.
- Changes to JSONL sibling replacement, atomic-move, or durability mechanics delivered by I080 and specified by I001.
- Pinning, persisting, inspecting, or claiming the exact PromptAgent version used by Azure Responses.
- PromptAgent definition compatibility/layout classification or automatic recreation of deleted agents.
- Deleting or rolling back a PromptAgent version created before a later local adoption failure.
- Adding a new `/agent unbind` command; in-memory `null` remains the binding used by configured/fresh ephemeral sessions
  and when an omitted `promptAgentName` field is resumed.
- Making multiple `SessionStore` instances/processes or direct out-of-band binder mutation safe.

## Acceptance

- [ ] `prepareBinding(name)` normalizes and constructs the candidate delegate without changing the committed provider;
  abort leaves it unchanged, and commit swaps one immutable `(normalizedName, delegate)` holder without throwing.
- [ ] One PromptAgent operation is serialized with turns and session switching. No turn can observe the interval between
  durable metadata acceptance and the in-process commits.
- [ ] `/agent use` and successful-create adoption execute prepare → atomic metadata persist → provider commit → live
  `Session` commit → status/reporting in that order. Every recoverable failure occurs before the durable decision and
  leaves disk, provider, live session, and displayed status at the old binding.
- [ ] A created version followed by local adoption failure is reported as created-but-not-adopted and is never deleted;
  an ambiguous create failure says a version may exist while the current local binding remains unchanged.
- [ ] Fresh startup and `/new` put the configured/current normalized name in the unpublished candidate, publish it once
  through `persistNew`, and never call `persistMetadata` as post-publication adoption.
- [ ] Resume prepares and commits the exact persisted name before changing the active live session or status. An omitted
  `promptAgentName` field decodes to in-memory `null` and selects unbind. Resume performs no `listAgents`
  existence/version preflight and never silently rewrites a missing name.
- [ ] After interruption, restart reads the accepted header as the sole durable binding authority and reconstructs the
  provider/live/status state from it. No journal, rollback, or remote version inference is introduced.
- [ ] Deterministic focused tests cover success, same-name no-op, prepare/abort/old-close behavior, each persistence and
  publication failure boundary, post-durable interruption, create ambiguity, fresh adoption, resume, unbind, and status.
- [ ] The provider, session, TUI, and architecture specifications agree on the implemented guarantees and make no
  exact-version or cross-process durability claim.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Persisted Prompt agents`](../spec/providers.md#persisted-prompt-agents-promptagent), especially selection/session
  lifecycle and the name-scoped Azure Responses limitation.
- [`Storage`](../spec/sessions.md#storage), [`SessionStore API`](../spec/sessions.md#sessionstore-api), and
  [`Persisted agents & resume`](../spec/sessions.md#persisted-agents--resume).
- [`Slash-commands`](../spec/tui.md#slash-commands) and [`Status bar`](../spec/tui.md#status-bar).
- [`Provider capabilities and runtime`](../spec/architecture.md#provider-capabilities-and-runtime) and
  [`Threading & concurrency`](../spec/architecture.md#threading--concurrency).
- I001 [`TUI startup session policy`](I001-workspace-context-and-trust.md#tui-startup-session-policy) and
  [`ACP behavior`](I001-workspace-context-and-trust.md#acp-behavior) for provisional publication.
- I080 [`Decisions and constraints`](I080-atomic-session-metadata.md#decisions-and-constraints) for immutable candidate
  metadata and atomic-only replacement.

### Source entry points

- `src/main/kotlin/com/konductor/provider/inference/PromptAgentBinder.kt` — replace immediate mutation with
  `prepareBinding`; define a one-shot `PreparedPromptAgentBinding` whose normalized name is the only value persisted.
- `src/main/kotlin/com/konductor/provider/inference/SwitchableFoundryResponsesClient.kt` — prepare the replacement
  delegate without publication; atomically commit one immutable name/delegate holder; keep abort and superseded-client
  close best-effort and non-throwing.
- `src/main/kotlin/com/konductor/provider/ProviderRuntime.kt` — retain the Prompt-only management surface and prevent
  callers from bypassing the coordinated binding path.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — own serialized prepare/persist/provider/session ordering, fresh
  candidate binding, and resume/unbind preparation alongside existing session/turn operations.
- `src/main/kotlin/com/konductor/core/models/Session.kt` — reuse immutable `SessionMetadata` and non-failing
  `commitMetadata`; do not add transaction state to the persisted model.
- `src/main/kotlin/com/konductor/session/SessionStore.kt`, `JsonlSessionStore.kt`, and `NoOpSessionStore.kt` — consume
  I001 `newCandidate`/`persistNew` and I080 `persistMetadata`; do not change replacement mechanics for this delivery.
- `src/main/kotlin/com/konductor/conversation/PromptAgentCommand.kt` — create first, then request coordinated adoption;
  distinguish not-created/ambiguous and created-but-not-adopted copy without claiming that a returned version is pinned.
- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — `/new` and `/resume` update transcript and
  PromptAgent status only from the successfully committed loop result; remove post-publication `onFreshSession` and
  post-resume binder mutation.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — remove the mutate-then-`persistSessionHeader` callback; initialize and
  update `AppState.activeAgentName` from committed session outcomes under the normal state-application lock.
- `src/main/kotlin/com/konductor/core/AppState.kt` and
  `src/main/kotlin/com/konductor/tui/component/StatusBar.kt` — keep status presentation-only; no reconciliation logic or
  second binding authority belongs here.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and
  `src/main/resources/com/konductor/i18n/messages.properties` — semantic copy for prepare/persistence rejection,
  created-but-not-adopted, ambiguous creation, and restart guidance.
- `src/main/kotlin/com/konductor/provider/inference/PromptAgentClient.kt` and `AzurePromptAgentClient.kt` — preserve
  name-scoped lifecycle/reference behavior; do not convert the returned version into a binding identity.

### Tests

- Add `src/test/kotlin/com/konductor/provider/inference/SwitchableFoundryResponsesClientTest.kt` — candidate construction
  without publication; exact normalized/null target; same-name no-allocation; one holder swap; abort cleanup; old-close
  failure after commit; one-shot handle misuse rejected before orchestration.
- Extend `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — event-recorder tests pin prepare →
  `persistMetadata` → provider commit → `Session.commitMetadata`; preparation and persistence failure preserve all old
  state; no turn/session operation interleaves; a deliberate stop immediately after accepted metadata reloads and
  resumes the new header binding.
- Extend `src/test/kotlin/com/konductor/conversation/PromptAgentCommandTest.kt` — `/agent use` success/rejection and
  status; create rejection; ambiguous create with unchanged local state; successful creation plus prepare/persist failure
  reports created-but-not-adopted; retrying `/agent use <name>` adopts by name without asserting a version.
- Extend `src/test/kotlin/com/konductor/conversation/SessionCommandsTest.kt` — `/new` adopts the current committed name in
  its first persisted header; `persistNew` failure retains old session/binder/status; resume exact name and `null`
  unbind; resume preparation failure retains old transcript/session/binder/status; zero `listAgents` calls; displayed
  status changes only after commit and remains unchanged on reported failure, without a Lanterna test.
- Extend `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — reuse deterministic file operations to prove
  metadata rejection keeps the old header and restart after successful replacement sees the new normalized name; do
  not duplicate I080 atomic-move mechanics.
- Extend the I001 provisional tests in `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` and
  `NoOpSessionStoreTest.kt` — bound candidate invisibility before `persistNew`, exact first header after publication,
  failure leaves no accepted file, and NoOp has no I/O while committing the live in-memory outcome.
### Deterministic boundary matrix

| Boundary | Injected result | Required observation |
|---|---|---|
| Normalize/prepare | factory throws | old disk/provider/session/status; no persistence; failed command/resume |
| Prepared abort | persistence or publication rejects | candidate closes best-effort; old delegate remains routable |
| Metadata candidate write/replace | throws | atomic store keeps old header; provider commit not called |
| Durable metadata accepted | test stops before provider commit | restarted load uses new header and prepares that exact name |
| Provider commit | prepared handle commits | one non-throwing holder swap; old-close failure is ignored |
| Live session commit | provider already committed | exact persisted candidate copied with no callback/I/O |
| Display commit | core success returned | status gets that exact normalized name before success copy |
| Same name | prepare current name | no factory, metadata rewrite, delegate close, or status churn |
| `/agent create` before accepted response | deterministic/ambiguous failure | old local state; ambiguity copy never claims absence remotely |
| Create accepted, adoption rejects | prepare/persist failure | created version remains; old local state; retry guidance by name |
| Fresh `persistNew` | publication rejects | no accepted/listable candidate; provisional runtime closes; old `/new` state stays |
| Resume name | preparation succeeds | no metadata rewrite or agent listing; provider/session/status switch together |
| Resume omitted field (decoded `null`) | ephemeral preparation succeeds | exact unbind across provider/session/status |
| Resume preparation | throws | current session/transcript/provider/status remain selected |
| Process/power interruption | accepted path is old/new/missing | restart trusts the valid accepted header; missing fresh candidate is not a session |

### Targeted searches

```bash
rg -n "PromptAgentBinder|bindAgent|activeAgent|PromptAgents" src/main/kotlin src/test/kotlin
rg -n "promptAgentName|persistMetadata|commitMetadata|persistSessionHeader" src/main/kotlin src/test/kotlin
rg -n "onFreshSession|onResumedSession|newSession\(|resume\(" src/main/kotlin/com/konductor src/test/kotlin/com/konductor
rg -n "newCandidate|persistNew|loadHeader" src/main/kotlin src/test/kotlin docs/spec
rg -n "activeAgentName|StatusBar|agentFailed|createdAgent" src/main/kotlin src/main/resources src/test/kotlin
rg -n "buildAgentScopedOpenAIClient|createAgentVersion|PromptAgentRef" src/main/kotlin src/test/kotlin
```

## Resolved design contract

### One durable decision, then non-failing fan-out

`PromptAgentBinder.prepareBinding(rawName)` trims the name, maps blank to `null`, and builds the candidate
`FoundryResponsesClient` while the old committed holder remains routable. The returned one-shot handle exposes that
normalized `agentName`; callers must use it for metadata rather than persisting the raw input. Preparation of the
current name is a no-allocation/no-close handle.

For a published Prompt session, the serialized coordinator executes:

1. reject a concurrent turn, compaction, session change, or PromptAgent operation rather than queueing behind stale
   state;
2. prepare the desired provider binding;
3. derive immutable `candidate = session.metadata.copy(promptAgentName = prepared.agentName)`;
4. call `SessionStore.persistMetadata(session, candidate)`;
5. commit the prepared provider holder;
6. call `session.commitMetadata(candidate)`;
7. return the exact committed name so the TUI sets `AppState.activeAgentName` and emits success copy in one state
   application.

Step 4 is the sole durable decision point. Steps 5–7 perform no allocation, SDK/service call, filesystem I/O, callback,
or close that can affect the result. Provider commit is one volatile assignment of an immutable `(agentName, delegate)`
holder. Disposal of the superseded delegate occurs afterward under best-effort suppression. `Session.commitMetadata`
is scalar assignment. Status is a presentation cache set from the returned committed name, never an independent
binding decision.

This is not cross-resource atomicity. Disk may contain the new name for a short interval before the in-process fan-out,
but the operation lock prevents a turn, session switch, status render, or second binding operation from observing that
interval. A recoverable exception is reported only before `persistMetadata` returns, when the old accepted state still
wins everywhere. An implementation that lets provider/session/status commit throw violates the seam invariant; it must
not catch that invariant defect and report an ordinary rejection claiming the old binding. Process termination is
reconciled on restart as described below.

`PreparedPromptAgentBinding.close()` aborts an uncommitted candidate and suppresses candidate-close failure. A committed
handle cannot be aborted or committed twice. Direct binder mutation is removed from `ProviderManagement`; the
coordinator is the only production commit caller. These narrow rules replace rollback: no attempt rebuilds the old
provider after the durable decision.

### Command outcomes

`/agent use <name>` starts the local transaction directly. Preparation rejection and metadata rejection close the
candidate, preserve the old four-way state, and report failure. Same normalized name is success without a metadata
rewrite or status churn.

`/agent create [name]` first asks Foundry to create a version from the current stable instructions and tools. Creation
is outside the local binding transaction and is never rolled back:

- a failure before an accepted lifecycle response leaves the local binding unchanged; because a transport failure can
  be ambiguous, copy conservatively says a version may exist and suggests `/agent list` followed by
  `/agent use <name>` rather than claiming no service mutation;
- after a successful lifecycle response, adoption uses only `ref.name`. A local prepare/persistence failure says the
  version was created but **not adopted**, retains the old binding/status, and suggests `/agent use <name>`;
- successful adoption may display the lifecycle-returned version as creation information, but the binding and next
  Responses call remain name-scoped. Konductor does not claim that this version is pinned or still latest.

A remote version that exists after ambiguous/failed local adoption may become the latest version selected by a later
name-scoped invocation. Konductor neither deletes it nor attempts an exact-version reconciliation.

### Fresh publication, resume, and unbind

Fresh startup follows I001: `newCandidate` allocates no file; composition writes the effective normalized configured
PromptAgent name into the unpublished candidate and prepares/builds the provisional Prompt runtime for that same name.
After all local validation, `persistNew` publishes one complete header, then runtime/session/status become visible.
Publication failure closes the provisional graph and leaves no accepted session. There is no post-publication
`persistMetadata` adoption.

`/new` copies the current committed PromptAgent name into its candidate before `persistNew`. The existing provider
holder already matches and remains unchanged; successful publication switches the live session and derives status from
the candidate. Failure retains the previous live session, transcript, provider, and status. NoOp follows the same order
without filesystem I/O.

Resume/load treats strict accepted header metadata as authoritative. Before changing the live session or transcript,
the coordinator prepares `candidate.promptAgentName`; an omitted header field decodes to in-memory `null`, which
prepares the ephemeral delegate and is the unbind path. After preparation, provider commit and live-session selection
are non-failing, and the frontend applies transcript and status together. Preparation failure leaves the previous
selection intact.

Resume does not call `listAgents` and does not silently fall back or rewrite a saved name when a list snapshot omits it.
Azure's Responses construction is name-scoped, an existence snapshot can race deletion or new-version creation, and it
cannot validate the version the next invocation will select. A deleted/unusable name therefore fails authoritatively
when preparation or the next service request proves it unusable; the persisted name remains available for explicit
retry/recovery. `/agent list` remains an explicit advisory command, not a transaction precondition.

### Interruption and restart reconciliation

There is no transaction journal. The valid accepted JSONL header is the only durable PromptAgent binding authority.
After a process stop or power loss, startup/load ignores vanished in-memory provider/session/status state, reads the
accepted header, prepares its exact `promptAgentName`, and displays that name only after publication/resume succeeds.
If atomic metadata replacement was accepted, restart uses the new name; if it was not, restart uses the old name. I080's
lack of parent-directory force means an operating-system/power failure may expose either valid accepted header; the one
actually loaded wins. An unpublished fresh candidate or missing accepted path is not resumed.

If restart cannot prepare the accepted name, startup/resume fails visibly while leaving the header unchanged. The user
may retry after the local/service problem is corrected or resume another session; Konductor does not infer a version,
rewrite to ephemeral, or create/delete remote state. This bounded read-header-and-prepare rule is the complete
reconciliation mechanism.

## Decisions and constraints

- The contract is PromptAgent-specific and remains inside the existing TUI → `AgentLoop` → provider layering.
- The operation uses I080 immutable metadata and atomic replacement and I001 provisional publication; it does not alter
  either filesystem algorithm.
- `activeAgent` remains the committed provider read used by model-switch gating and palette detail.
- Only one process/store instance and coordinated application paths are supported. Direct mutation remains unsupported.
- Azure Responses binding stays name-scoped. A lifecycle-returned version is evidence about creation, not execution
  identity.
- No new SDK/service pain point is discovered by this design-only work. Implementation should update existing PromptAgent
  feedback only if it encounters evidence beyond the already documented name-only agent-scoped client limitation.

## Validation

For this design-only PR:

```bash
node scripts/validate-docs.mjs
git diff --check
```

During implementation, run the focused deterministic suites and then the full build:

```bash
./mvnw -q -Dtest=SwitchableFoundryResponsesClientTest,PromptAgentCommandTest,AgentLoopSessionTest,SessionCommandsTest,JsonlSessionStoreTest,NoOpSessionStoreTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
```

No live Foundry test is required for the transaction boundaries. Manually verify `/agent use`, create-success followed
by local adoption failure, ambiguous create copy, `/new` first-header adoption, resume of a named session, resume of an
ephemeral session, and restart after an accepted metadata replacement. Confirm model switching is fixed only by the
committed provider holder and status never advances on a reported failure.

## Completion

The final implementing PR closes issue #92 and records the resulting commit/reconciliation behavior here. This
design-only packet and spec update do not claim implementation completion. Excluded follow-ups belong in focused issues
or [`future.md`](../future.md), not in a broader transaction abstraction.
