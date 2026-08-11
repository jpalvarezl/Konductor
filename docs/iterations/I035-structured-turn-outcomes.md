---
id: I035
title: Structured failed and aborted turn outcomes
issue: https://github.com/jpalvarezl/Konductor/issues/35
created: 2026-08-11
---

# I035 — Structured Failed and Aborted Turn Outcomes

Issue [#35](https://github.com/jpalvarezl/Konductor/issues/35) owns discussion, assignment, dependencies, status, and
closure. This packet is the repository-local implementation brief and context pack.

## Outcome

Every accepted agent turn has at most one durable terminal entry: a completed assistant, a structured failure, or a
structured cancellation. Terminal persistence reconciles ambiguous earlier writes against the accepted file and never
creates two terminals. Any unresolved ordinary-append exception poisons that session's writer, preventing a second
ambiguous write or turn until accepted bytes are authoritatively reconciled. TUI and ACP resume expose safe outcome
status, while Prompt history starts a new replay-safe epoch after any failed, aborted, or legacy dangling turn so
uncertain user/tool activity is never sent back to the model.

## Scope

- Add schema-v3 `FailedEntry` and `AbortedEntry` variants for non-cancellation failures and turn cancellation.
- Make v3 represent either Prompt or Hosted while retaining strict decode compatibility for Prompt v1 and Hosted v2.
- Replace terminal `append` with an explicit atomic `SessionStore.terminalize` decision for `AssistantEntry`,
  `FailedEntry`, and `AbortedEntry`; a terminalization of a legacy file promotes its header to v3 in that same decision.
- Reconcile one immediately preceding ambiguous non-terminal append, complete unterminated JSON, matching partial UTF-8
  suffixes, LF/CRLF legacy records, and exceptions that may occur after atomic replacement.
- Poison a session writer after every non-terminal append exception. Permit only that active turn's matching
  terminalization to clear it; otherwise reject all further writes/turns until a fresh process/store strictly reloads
  reconciled bytes or the operator explicitly repairs the file and restarts.
- Validate turns with one shared forward scanner used by load, list/summarize, rewrite, terminalization, replay, and
  model-history planning.
- Build Prompt input and compaction from replay-safe successful-turn epochs only. Exclude failed/aborted turns and
  historical dangling turns in full, including their user and tool records; include an open turn only when its exact
  `userEntryId` is the current loop-owned request.
- Keep partial assistant prose live-only and record only whether non-empty assistant text was observed.
- Define one cancellation-versus-terminal-admission gate so accepted disk state, persistence failure, and late
  cancellation produce the same terminal classification in AgentLoop, TUI, and ACP.
- Render resumed outcomes through localized TUI status lines and safe ACP replay updates; align live ACP failure copy
  with the same privacy boundary and serialize replay before prompts through an initialization gate.
- Add deterministic codec goldens plus store/reconciliation, loop, restart, actual TUI startup, ACP replay/status,
  cancellation, overflow, persistence-failure, tool-activity, safe-history, and compaction-cut coverage.

## Non-goals

- Persisting partial assistant text, exception messages/classes/stacks, HTTP/service payloads, request ids, paths,
  suppressed failures, tool arguments, or new copies of tool output.
- Claiming that a started or completed tool call proves or disproves an external side effect, or rolling any effect
  back after failure/cancellation.
- Guaranteeing an outcome when the user append did not return success, the complete-file terminal decision cannot be
  accepted or reconciled, or the process is forcibly terminated before the accepted path contains a complete terminal.
- Silently repairing an arbitrary corrupt file. Terminalization may remove only the exact partial suffix of the one
  supplied ambiguous append; every other malformed/mismatched byte sequence fails closed.
- Changing existing tool-result truncation, adding an error-detail truncator, or exporting/sharing session files.
- Adding branching, independent persisted turn ids, steering, queued prompts, automatic retry, a generic failure
  taxonomy, or a general-purpose session repair/migration command.
- Teaching Hosted history from local JSONL. Hosted continues to send only the current user and treats local outcomes as
  audit/presentation state; server history remains authoritative.
- Adding a new ACP method/update type, changing ACP 0.24 stop-reason vocabulary, or completing ACP permissions,
  usage/compaction updates, client-role orchestration, or a full stdio protocol golden.
- Making non-terminal append, metadata replacement, transcript rewrite, and terminalization transactional with each
  other or with Foundry/tool effects. Terminalization reconciles only the explicitly supported immediate ambiguity.

## Acceptance

- [ ] Fresh Prompt and Hosted sessions write v3 headers. New readers strictly decode legacy Prompt v1, Hosted v2, and
  both valid v3 shapes; malformed binding combinations and versions newer than v3 fail closed.
- [ ] No terminal entry uses ordinary append. `AssistantEntry`, `FailedEntry`, and `AbortedEntry` are accepted only by a
  forced-and-closed complete-file v3 candidate plus atomic replacement. A v1/v2 terminalization promotes the header in
  the same replacement; old binaries therefore fail at their newer-version header guard before entry decode.
- [ ] Terminalization byte-scans the accepted path under the session lock, validates the decoded forward state, and
  compares it with the caller's live prefix. It accepts one complete/unterminated immediately preceding attempted
  append, removes only a byte-for-byte matching partial attempted suffix, preserves accepted transcript record bytes
  and existing LF/CRLF separators, and fails closed on any other divergence.
- [ ] Every non-terminal append exception atomically poisons that session's writer before it escapes. Poison blocks a
  second append, rewrite, metadata write, terminalization for another entry/turn, and another turn. The sole in-process
  escape is the active turn's terminalization carrying that exact one ambiguous entry; accepted replacement or an exact
  clean-prefix reread reconciles it. An initial user-append exception has no accepted turn and remains poisoned.
- [ ] Any terminalization failure is reconciled by rereading the final path. An exact already accepted terminal is
  authority. Rejection means the bytes equal the caller's validated live prefix byte-for-byte, with no suffix logically
  discarded and no complete ambiguous extension. A still-present matching partial suffix—including one split inside a
  UTF-8 code point—or a complete extension is indeterminate and stays poisoned. Deterministic terminal id/content is
  reused; retry never adds a second terminal.
- [ ] The shared forward scanner recognizes successful, failed, aborted, legacy-dangling, and current-open turns; checks
  outcome `userEntryId`/physical-tip parent, tool-call/result ordering, and at-most-one terminal; rejects terminal/tool
  records outside their turn. `load` and `listForCwd`/summary use the same full validation, so listing cannot advertise a
  session that full load would reject. `loadHeader` intentionally remains header-only.
- [ ] Exact outcome JSON fields/enums match the contract below. No raw diagnostic or partial prose enters an outcome;
  `partialAssistant` is the only partial-output fact and tool results retain their existing independent bound.
- [ ] Prompt input includes only fully successful turns after the most recent failed, aborted, or legacy-dangling turn,
  plus the exact current open user when explicitly named. It excludes every user/tool/compaction record in a
  non-replayable turn. An unsegmentable or invalid history blocks continuation rather than guessing; Hosted remains
  current-user-only.
- [ ] Compaction/token/summary planning uses that indexed replay-safe projection, starts after the latest hard epoch
  boundary, and preserves every physical audit entry in rewrites. Zero-content outcomes cannot move the token
  threshold or become `firstKeptEntryId`; rounding chooses a projected user boundary, then a safe assistant split only
  for an oversized successful turn, and never splits a tool call/result pair.
- [ ] Non-cancellation provider/orchestration failure, all terminal overflow stages, assistant/tool persistence failure,
  cancellation before/after tool activity, cancellation around reactive compaction, and a provider flow ending without
  a terminal event produce the prescribed result. The complete atomic phase graph covers `Running`, `Cancelling`,
  `Terminalizing`, and `Terminal`, including `Cancelling -> Terminalizing(Aborted)`, the pre-I/O activity downgrade,
  and clean assistant rejection directly to `Terminalizing(Failed(persistence))` without reopening `Running`. A cancel
  after final admission is inert; accepted disk authority wins. Outcome-write failure never recurses or masks the
  pre-admission live cause.
- [ ] TUI live behavior keeps streamed partial text and one transient terminal line. Startup, `--continue`/`--resume`,
  and `/resume` rebuild safe failed/cancelled status in physical order and never recreate partial prose. Coverage
  instantiates the production `TuiApp` startup composition and inspects its seeded `AppState`, not only a mapper or
  controller approximation.
- [ ] ACP live failure emits safe fixed copy and `end_turn`; accepted cancellation emits `cancelled`. Loaded replay is
  an immutable validated snapshot emitted sequentially from `postInitialize` after the load response and before any
  prompt update. Non-cancellation notification failure is logged once, stops replay, and opens the prompt gate without
  retry; replay cancellation propagates and permanently fails the gate/session. `session/cancel` targets prompts only,
  never replay. Replay emits no partial prose or `PromptResponse` and never exposes raw diagnostics.
- [ ] Deterministic Prompt-v3 and Hosted-v3 JSONL goldens cover every entry subtype and exact enum/default encoding;
  legacy v1/v2 fixtures remain decode-only compatibility goldens. Focused/full tests, package, docs validation, and
  diff checks pass offline.

## Context pack

Read only this context first. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Storage`](../spec/sessions.md#storage), [`Entry model & on-disk schema`](../spec/sessions.md#entry-model--on-disk-schema),
  and [`Atomic turn terminalization`](../spec/sessions.md#atomic-turn-terminalization) — accepted bytes, line handling,
  state validation, exact schema, and reconciliation.
- [`Reconstructing Responses input`](../spec/sessions.md#reconstructing-responses-input) — replay-safe epochs, current
  open turn, and continuation blocking.
- [`Reactive context-overflow recovery`](../spec/compaction.md#reactive-context-overflow-recovery) and
  [`Cut-point rules`](../spec/compaction.md#cut-point-rules) — terminal overflow, cancellation, projection-based cuts,
  and exact rounding.
- [`Rendering agent output`](../spec/tui.md#rendering-agent-output) and
  [`Active submissions and cancellation`](../spec/tui.md#active-submissions-and-cancellation) — live/resumed copy,
  actual startup composition, and terminal admission.
- [`How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor), [`Status`](../spec/acp.md#status), and
  [`Overlap and cancellation policy`](../spec/acp.md#overlap-and-cancellation-policy) — post-load replay gate and live
  terminal behavior.
- [`Core domain model`](../spec/architecture.md#core-domain-model),
  [`Turn lifecycle (Prompt provider)`](../spec/architecture.md#turn-lifecycle-prompt-provider), and
  [`Error handling & retry`](../spec/architecture.md#error-handling--retry) — shared loop ownership and precedence.

### Source and implementation map

- `src/main/kotlin/com/konductor/core/models/Entry.kt` plus new outcome model files — outcome models, wire enums,
  `NonTerminalEntry` for user/tool append, and `TurnTerminalEntry` implemented by assistant/failed/aborted; compaction
  remains rewrite-only.
- `src/main/kotlin/com/konductor/session/SessionCodec.kt` — v3 encoder; strict v1/v2/v3 decode matrix; exact
  enum/default encoding; decoded header version; strict UTF-8 record decode.
- New `src/main/kotlin/com/konductor/session/SessionTurnState.kt` — the one forward scanner, turn spans, replay-safe
  epoch/projection, tool pairing, terminal/outcome validation, and current-open-user inclusion.
- `src/main/kotlin/com/konductor/session/SessionStore.kt` — add abstract `terminalize(session, request)`, explicit
  accepted/rejected/indeterminate result types, and session-poison semantics; no default that can degrade to append.
- `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt` and its `SessionFileOperations` seam — byte record scan,
  expected-prefix/ambiguous-suffix reconciliation, poison-before-rethrow and mutator guards, complete candidate
  write/force/close, atomic replace, accepted-path reread, and full validated list summary.
- `src/main/kotlin/com/konductor/session/NoOpSessionStore.kt` — explicit in-memory accepted terminal result using the
  same scanner, with no filesystem I/O.
- Every direct interface implementation must compile deliberately: production `JsonlSessionStore` and
  `NoOpSessionStore`; `RecordingSelectionStore` in `src/test/kotlin/com/konductor/MainTest.kt`; the full anonymous
  failing store and `MockMetadataSessionStore` in
  `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt`. Delegating test stores inherit terminalization unless a
  test intentionally overrides it; old `append` fault injectors for assistant terminals move to `terminalize`.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — accepted user, partial-text bit, attempted ambiguous entry,
  complete atomic terminal-admission graph, classification, accepted-snapshot/poison reconciliation, same-session turn
  blocking, and terminal result exposed to frontend finalization.
- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — agent-turn cancellation requests the loop
  admission handle rather than unconditionally cancelling an already admitted terminal; `/resume` uses the same
  validated transcript mapper.
- `src/main/kotlin/com/konductor/session/SessionHistory.kt` and
  `src/main/kotlin/com/konductor/provider/inference/ResponsesMapping.kt` — state-aware history projection and complete
  model-input exclusion.
- `src/main/kotlin/com/konductor/compaction/Compactor.kt` and
  `src/main/kotlin/com/konductor/compaction/TokenEstimator.kt` — indexed safe projection, hard epoch start, exact cut
  rounding, and no outcome/non-replayable-turn summary or tokens.
- `src/main/kotlin/com/konductor/conversation/SessionTranscript.kt`,
  `src/main/kotlin/com/konductor/i18n/AppStrings.kt`, and message bundles — localized deterministic replay status.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — production startup seeding plus a narrow internal state snapshot seam
  for construction-level tests; active-turn terminal reporting uses the accepted terminal result.
- `src/main/kotlin/com/konductor/acp/KonductorAcpAgent.kt` — validated loaded snapshot, one-shot post-initialize replay
  gate, sequential notifications, fixed safe status, terminal-derived stop reason, and prompt-only cancellation.

### Test map

- `src/test/kotlin/com/konductor/session/SessionCodecTest.kt` — exact v3 goldens, all outcome enums/defaults, v1/v2
  decode-only fixtures, malformed combinations, unknown versions, and unknown outcome values.
- `src/test/resources/session/current-session-v1.jsonl` and
  `current-session-v2-hosted.jsonl` — unchanged legacy decode fixtures.
- `src/test/resources/session/current-session-v3-prompt.jsonl` and
  `current-session-v3-hosted.jsonl` — new deterministic full-schema encoder goldens.
- New `src/test/kotlin/com/konductor/session/SessionTurnStateTest.kt` — forward transitions; multiple terminals;
  wrong-user/wrong-parent outcomes; dangling/consecutive users; allowed outstanding tool on failure/abort; rejected
  orphan/duplicate result and successful outstanding call; replay epochs; current-user gate; malformed continuation.
- `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — fresh v3 publication; every terminal type through
  atomic replacement; v1/v2 promotion; byte/separator preservation; LF, CRLF, mixed lines, complete EOF JSON, terminal
  CR, matching partial suffix (including a split multibyte UTF-8 character), unrelated corrupt suffix; complete
  ambiguous append; poison blocks a second append and every same-session mutator; only the exact active request may
  reconcile; candidate/pre/post-replace failures; partial-discard plus rejected move remains indeterminate/poisoned;
  clean-prefix rejection; accepted-path authority; deterministic retry; existing different terminal; list/load shared
  validation.
- `src/test/kotlin/com/konductor/session/NoOpSessionStoreTest.kt` — explicit terminal acceptance/reconciliation semantics
  without I/O. `src/test/kotlin/com/konductor/MainTest.kt` and direct AgentLoop store fakes prove the complete abstract
  implementation map remains compile-time explicit.
- `src/test/kotlin/com/konductor/session/SessionHistoryTest.kt` and
  `src/test/kotlin/com/konductor/provider/inference/ResponsesMappingTest.kt` — successful epochs only, whole-turn
  exclusion around tools/outcomes, legacy dangling reset, exact active user, Hosted current-only, and block-on-invalid.
- `src/test/kotlin/com/konductor/compaction/CompactorTest.kt` and
  `src/test/kotlin/com/konductor/compaction/TokenEstimatorTest.kt` — no excluded-turn/outcome content, hard epoch start,
  physical preservation, thresholds adjacent to outcomes, projected-index-to-physical rounding, oversized successful
  turn fallback, and no tool-pair split.
- `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — ordinary/partial failure; initial-user zero-byte,
  complete, ASCII-partial, and partial-multibyte append exceptions; poisoned same-runtime second-turn rejection;
  restart acceptance of a complete dangling user and strict failure for unrepaired partial UTF-8; active tool-append
  reconciliation; assistant terminal reconciliation; the complete deterministic phase-edge matrix (including
  cancellation's pre-I/O downgrade and abort admission); clean assistant rejection to persistence failure without
  reopening `Running`; exactly one terminal; malformed provider completion; parent/order; outcome-write
  rejection/indeterminate state; and restart authority.
- `src/test/kotlin/com/konductor/agent/AgentLoopCompactionTest.kt` — recovered versus terminal overflow stages,
  persistence classification, unsafe tool activity, epoch behavior, and cancellation before/after committed compaction
  and terminal admission.
- `src/test/kotlin/com/konductor/conversation/ConversationControllerTest.kt` and
  `SessionCommandsTest.kt` — `/resume` replay and terminal-derived late-cancel presentation.
- New `src/test/kotlin/com/konductor/tui/TuiAppStartupTest.kt` — instantiate the real `TuiApp` with a resumed loop and
  production constructor, inspect seeded state through the narrow seam, and prove localized ordered outcomes/no partial
  prose for startup `--continue`/`--resume`; helper-only tests are insufficient.
- `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — safe live failure; terminal-derived stop; response
  before replay; strict sequential ordering; prompt waits for gate; non-cancellation notification failure opens gate
  without retry; replay cancellation permanently fails it; cancel does not target replay; no prompt response/partial
  prose/raw error; stable ids; outcomes after tool updates; initial-user append ambiguity rejects every later
  same-runtime prompt before provider work; and another session id remains usable.
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — semantic failed/aborted replay strings in root and fallback
  catalogs.

### Targeted searches

```bash
rg -n "SCHEMA_VERSION|encodeHeader|decodeHeader|append\(|rewrite\(|readSession|summarize" \
  src/main/kotlin/com/konductor/session src/test/kotlin/com/konductor/session
rg -n ": SessionStore|SessionStore by|SessionFileOperations" src/main/kotlin src/test/kotlin
rg -n "runTurnSingleFlight|foldProviderEvent|AgentEvent.Failed|requestCancel|CancellationException" \
  src/main/kotlin/com/konductor/{agent,conversation,acp} src/test/kotlin/com/konductor/{agent,conversation,acp}
rg -n "reconstructHistory|serializeHistory|serializeEntry|firstKeptEntryId|roundToCut|sessionEntriesToMessages" \
  src/main/kotlin/com/konductor/{provider,compaction,conversation,session,tui}
rg -n "postInitialize|loadSession|AgentMessageChunk|UserMessageChunk|ToolCallUpdate|StopReason" \
  src/main/kotlin/com/konductor/acp src/test/kotlin/com/konductor/acp
```

## Decisions and constraints

### Exact v3 wire contract

Fresh writers emit `version: 3` for both kinds. Version is not the v3 kind discriminator; the complete Hosted binding
is, so no generic provider-kind field is added.

| Header version | Prompt shape | Hosted shape | Invalid |
|---|---|---|---|
| v1 | no Hosted fields; optional non-blank/trimmed `promptAgentName` | never | either Hosted field |
| v2 | never | both Hosted fields; no `promptAgentName` | missing/partial Hosted binding or any PromptAgent |
| v3 | both Hosted fields absent; optional valid `promptAgentName` | both Hosted fields present; no `promptAgentName` | one Hosted field, or Hosted plus PromptAgent |

All existing cwd/model/id/binding validation remains. V3 Hosted still requires `hostedSessionId == header.id` and
accepts/preserves a non-blank legacy model as opaque metadata; fresh Hosted writes canonical `hosted`. Unknown header
fields remain ignored, but an unknown entry discriminator or enum value fails closed.

Exact canonical outcome lines are:

```jsonl
{"type":"failed","id":"...","parentId":"...","timestamp":"...","userEntryId":"...","failureKind":"context_overflow","overflowStage":"retry","partialAssistant":true}
{"type":"aborted","id":"...","parentId":"...","timestamp":"...","userEntryId":"...","reason":"cancelled","partialAssistant":false}
```

`failureKind` is exactly `error`, `context_overflow`, or `persistence`. `overflowStage` is omitted unless
`failureKind == context_overflow` and is exactly `initial`, `no_compactable_history`, `compaction`, or `retry`.
`reason` currently has the sole value `cancelled`. All booleans encode explicitly. `userEntryId` is the accepted user
that opened the logical turn; no persisted turn id is minted.

`partialAssistant` is true only when at least one non-empty `TextDelta` was exposed, or a non-empty completed assistant
could not be accepted as terminal. It records no text, byte/character count, hash, preview, truncation field, exception
class/message/stack/cause/suppressed data, service payload/code beyond the allowlisted category, request id, provider,
path, or duplicate tool data. Existing user text and independently bounded tool results stay in their own records.

### Forward turn state and safe model epochs

One scanner walks decoded entries from the header toward the physical tip. Its state is `BetweenTurns` or
`Open(user, entries, outstandingCalls)`, and it records terminal spans:

- `UserEntry` in `BetweenTurns` opens a turn. A second user closes the prior open span as legacy dangling and opens a
  new one; this is recoverable history, not a fabricated outcome.
- Tool call/result records are legal only in `Open`. Calls are unique in that turn; a result must match one outstanding
  call exactly once. A successful assistant requires no outstanding call. Failure, abort, or dangling closure may
  retain outstanding calls because absence of a result proves no absence of effect.
- `AssistantEntry` closes `Open` as successful. `FailedEntry`/`AbortedEntry` close it only when `userEntryId` equals the
  opening user and `parentId` equals the current physical tip. Any terminal while `BetweenTurns`, any second terminal
  before another user, and any orphan/duplicate tool result is invalid.
- `CompactionEntry` is audit/control metadata and does not open or close a turn. Its existing reference validation is
  retained. Header version rules are checked before entries, and outcome entries require v3.
- EOF in `Open` is a dangling turn. It is loadable when otherwise valid, because forced termination and legacy files
  can produce it; it is never silently treated as successful.

Each failed, aborted, or dangling span is a **hard model-history epoch boundary**. Prompt projection discards that
span and everything before it, then includes only completely successful, tool-valid turns after it. The sole exception
is `projection(entries, currentUserEntryId)`, which may append the exact final open span when its opening id matches the
loop-owned current request; no other open span is replayed. Thus a cancelled tool call, a persistence-failed result, or
an old dangling user cannot be resent. Invalid/unsegmentable history fails load or blocks continuation rather than
falling back to raw `Entry` filtering. Hosted ignores this local projection and keeps its current-user-only contract.

### Atomic terminalization and accepted bytes

`SessionStore.terminalize` accepts an immutable request containing the accepted user id, a terminal draft with stable
id/timestamp/payload (`AssistantEntry`, `FailedEntry`, or `AbortedEntry`), the caller's immutable live-entry prefix, and
optionally the one non-terminal entry whose immediately preceding `append` threw. Before any append exception escapes,
the store atomically marks that session id poisoned with the exact attempted entry. Every same-session mutator then
fails before I/O except terminalization by the already active turn carrying that same entry and prefix. This guard is
what makes the one-ambiguous-entry reconciliation bound enforceable: a second uncertain append can never stack on the
first.

The draft's `parentId` is not trusted: after byte reconciliation the store binds it to the actual accepted physical tip,
then validates and returns the accepted snapshot and sole terminal. This is required because a complete ambiguous
append changes the parent while a matching partial append does not. There is no append fallback or standalone
reconcile-only API.

Under the per-session lock, `JsonlSessionStore` reads the final path as bytes and scans forward:

1. LF terminates a record; one immediately preceding CR is its CRLF delimiter and not JSON payload. Blank records,
   malformed strict UTF-8, bare CR in payload, unknown entries, and state-machine violations fail closed.
2. A final non-empty suffix with no LF is an accepted record when, after removing an optional final CR, it is strict
   UTF-8 and decodes as one complete valid JSON entry. The lone CR is an incomplete delimiter, not payload.
3. An incomplete final suffix is discardable only when it is a byte prefix of the canonical UTF-8 encoding of the one
   supplied ambiguous entry and occurs immediately after the validated/live prefix. A complete supplied entry is
   accepted as a one-entry disk extension. Any other suffix, missing/reordered live record, semantic mismatch, or more
   than one unexplained disk entry is indeterminate and is not replaced.
4. If the scan already finds a terminal for the requested user, that exact accepted terminal is returned; the caller
   does not append or replace it. A conflicting second terminal is invalid, not reconciled.
5. Otherwise the store binds the draft terminal's `parentId` to the reconciled accepted tip, then writes a sibling
   candidate with a canonical v3 header, every accepted transcript record payload and complete delimiter preserved
   byte-for-byte, any accepted complete EOF payload followed by an inferred delimiter, and the canonical terminal line. The header keeps its original LF/CRLF delimiter; new delimiters use the most recent
   complete transcript delimiter, then the header delimiter, then LF. Mixed legacy delimiters are not normalized.
6. The candidate is forced and closed, then accepted with same-filesystem `ATOMIC_MOVE + REPLACE_EXISTING`; unsupported
   atomic move fails. As elsewhere, directory force is not promised.

The accepted final path, never a temporary name or the method's exception alone, is authority. After any candidate or
replacement failure the store rereads it under the same lock and returns exactly one of:

- `Accepted(snapshot, terminal)` when it contains the validated requested terminal or another valid sole terminal for
  that user. The authoritative snapshot replaces live entries and clears matching append poison.
- `Rejected` only when accepted bytes equal the caller's validated live prefix byte-for-byte. No logical suffix discard
  or complete ambiguous extension may be needed. This reread proves that an earlier append exception wrote no bytes and
  clears matching append poison, but it proves no terminal was accepted.
- `Indeterminate` for every other state. In particular, if candidate construction or replacement fails after the scan
  logically discarded a matching partial suffix, the accepted path still contains those bytes; a partial suffix split
  inside a UTF-8 code point is no less ambiguous. A complete ambiguous extension left without a terminal is also not a
  rejection because live state lacks it. Poison remains and all same-session writes/turns stay blocked.

Candidate cleanup remains best effort and cannot change the result. An accepted terminal replacement is the only way to
physically remove a matching partial suffix in-process. An initial user append exception has no accepted turn that may
call terminalization, so the runtime stays poisoned. A new process/store may continue only if strict full load
reconciles complete valid bytes (a complete user becomes a legacy dangling boundary); a partial/malformed suffix needs
explicit operator repair before restart. Loading or inspecting through the already poisoned writer does not clear it,
and I035 adds no general repair command.

`NoOpSessionStore` performs the same prefix/state/sole-terminal checks over the immutable in-memory request and returns
an accepted candidate without I/O. Terminal persistence precedes replacement of the live entries with the returned
snapshot. This return value reconciles a complete ambiguous append that reached disk but never reached the live list.

### Classification, terminal admission, and cancellation

`AgentLoop` owns one atomic phase per accepted user. The complete transition graph is:

```text
Running -- requestCancel / observed inactive before admission --> Cancelling
Running -- provisional natural terminal CAS --> Terminalizing(Assistant | Failed)
Terminalizing(Assistant | Failed, I/O not started)
        -- post-CAS inactive check --> Cancelling
Cancelling -- cancellation owner --> Terminalizing(Aborted)
Terminalizing(any) -- accepted disk authority --> Terminal(Accepted(authoritative terminal))
Terminalizing(Assistant) -- clean Rejected --> Terminalizing(Failed(persistence))
Terminalizing(Failed | Aborted) -- clean Rejected --> Terminal(Unaccepted, clean)
Terminalizing(any) -- Indeterminate --> Terminal(Unaccepted, poisoned)
```

`Running -> Terminalizing` is provisional until the immediate post-CAS coroutine-activity check. Direct child/parent
cancellation observed there takes the only downgrade edge to `Cancelling`; the cancellation owner then admits exactly
one `AbortedEntry`. Once that final active check passes—or once `Cancelling -> Terminalizing(Aborted)` occurs—I/O starts
in `NonCancellable`, all later cancellation is inert, and `Terminalizing` never reopens as `Running`. Thus frontend
`requestCancel` that wins `Running -> Cancelling` cancels provider work; repeated requests do nothing.

A provider/orchestration/persistence failure chooses `FailedEntry`; `TurnCompleted` chooses `AssistantEntry`.
Reconciliation may return any already accepted sole terminal for the user, which becomes authoritative regardless of
candidate. Clean assistant rejection permits exactly one intra-terminalizing downgrade to `FailedEntry(persistence)`;
it is not a second admission, does not recheck cancellation, and reuses the same accepted user/live prefix. A rejected
failure/abort ends without recursion. Indeterminate state ends poisoned. If no terminal was accepted, cancellation is
the primary live result when `Cancelling` won; otherwise the original failure is primary, with terminalization failure
only suppressed process-local context. Frontends derive accepted behavior from `Terminal`, never `Job.isCancelled`.

Failure classification is application-owned. A non-terminal `SessionStore` write that throws while the turn is still
active is `persistence`, even when overflow recovery later wraps it. A raw `PromptContextOverflowException` is
`context_overflow/initial`; `ContextOverflowRecoveryException` maps to `no_compactable_history`, `compaction`, or
`retry`; every other non-cancellation failure is `error`. Store boundaries use an internal tag so provider
`IOException` is not mistaken for persistence.

Assistant success itself goes through terminalization. Only clean rejection may retarget the existing terminalizing
phase once to `FailedEntry(persistence)` after reconciliation proved that no assistant terminal was accepted. An
indeterminate result blocks instead of guessing, and failure to terminalize that failed outcome ends without recursion.
Every non-terminal append exception is persistence-classified for an active accepted turn and poisons the writer until
that exact ambiguity is reconciled. Initial user append failure has no loop-accepted user/outcome or eligible
terminalization; the same runtime rejects another turn. After restart, a complete dangling user is excluded by safe
epoch projection, while partial UTF-8 or other malformed bytes fail strict load until explicitly repaired.

An overflow consumed by successful compact-and-retry writes no outcome. Terminal unsafe/non-capable overflow is
`initial`; no-plan, compaction/rewrite, and retry failures use their mapped stage unless a store-origin failure wins
classification. A cancellation before reactive commit leaves the current compaction state plus abort; after commit the
valid marker precedes the abort. Best-effort proactive summary failure remains non-terminal.

### Compaction projection and cut rounding

Token estimation, serialization, and cut planning consume indexed entries from the replay-safe epoch rather than raw
physical entries. Entries at/before the latest hard boundary, all outcomes, and compaction metadata inside an excluded
turn contribute zero content and cannot move the backward threshold. The latest usable compaction marker must be after
the hard boundary; older/tainted markers are ignored for model reconstruction.

The backward walk returns a projected entry plus its physical index. Rounding searches projected candidates only:

1. prefer the opening `UserEntry` of a complete successful turn at or before the threshold, keeping whole recent turns;
2. only when one complete successful turn itself exceeds `keepRecentTokens`, use its assistant boundary fallback; and
3. otherwise search forward for the next projected user/assistant boundary.

The fallback is illegal for a dangling/failed/aborted/current-open turn and may not put a tool call on one side without
its result. `firstKeptEntryId` is therefore always a replayable user/assistant id, never an outcome or zero-token audit
entry. The physical rewrite still preserves all entries in order and inserts the marker immediately before that
physical kept entry. If no safe projected cut exists, compaction returns no plan and reactive overflow terminalizes as
`no_compactable_history`.

### TUI and ACP replay

TUI live rendering remains event-driven: streamed partial text may remain visible, followed by one detailed live
failure or cancellation line. Resume cannot recreate partial text. `sessionEntriesToMessages` maps outcomes to
localized safe failed/context-overflow/persistence/cancelled lines at their physical positions. Startup and `/resume`
use the same mapper. `TuiAppStartupTest` must construct the production `TuiApp` and verify its actual initial
`AppState`; controller/helper-only coverage does not satisfy startup acceptance.

A loaded ACP session captures an immutable validated physical snapshot. Its one-shot initialization gate starts closed.
The SDK sends the successful `session/load` response, then `postInitialize` emits replay notifications one at a time and
awaits each send before the next. Only after replay completes or stops on an ordinary notification error does the gate
open; `prompt` waits at the gate and cannot interleave live updates. `session/new` has an already-open empty gate.

Replay maps user/assistant entries to message chunks with `messageId = entry.id`, reuses live tool call/update mapping,
and maps outcomes to fixed, nonlocalized, privacy-safe status chunks with the outcome id. It preserves emitted
physical order, contains no partial text or `PromptResponse`, and excludes compaction/usage updates. A notification
failure is caught, logged once to stderr without raw persisted content, and stops the remaining replay. It performs no
retry (avoiding duplicate prefix notifications) and opens the gate so the loaded session remains usable. A `CancellationException` from
post-initialize is lifecycle cancellation: it is rethrown, permanently fails the gate/session, emits no further replay,
and never becomes a status chunk. `session/cancel` targets only a prompt registered after the gate; it cannot cancel
replay, and cancellation of a caller merely waiting for the gate accepts no user and emits no response.

Exact replay outcome text is `⚠ Previous turn failed.`, `⚠ Previous turn failed: context window exceeded.`,
`⚠ Previous turn failed while saving session state.`, or `⏹ Previous turn was cancelled.` Live failure uses
`⚠ Turn failed.` and ends `end_turn`; an accepted abort has no second status chunk and ends `cancelled`. Stop reason is
derived from the loop's accepted terminal result, not only the child job flag, so a late cancel cannot contradict disk.

## Validation

```bash
FOCUSED_TESTS=SessionCodecTest,SessionTurnStateTest,JsonlSessionStoreTest,NoOpSessionStoreTest,SessionHistoryTest
FOCUSED_TESTS=$FOCUSED_TESTS,ResponsesMappingTest,CompactorTest,TokenEstimatorTest
FOCUSED_TESTS=$FOCUSED_TESTS,AgentLoopSessionTest,AgentLoopCompactionTest,ConversationControllerTest,SessionCommandsTest
FOCUSED_TESTS=$FOCUSED_TESTS,TuiAppStartupTest,KonductorAgentSessionTest,AppStringsTest,MainTest
./mvnw -q -Dtest=$FOCUSED_TESTS test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

All acceptance coverage is deterministic and offline. No Foundry service, credential, recording, ACP editor, or live
cancellation timing is required; controlled stores/providers/coroutine gates and exact byte fixtures prove the
contract.

## Completion

Record the final pull request, resulting behavior, and any excluded follow-up promoted to a focused issue or
`future.md`. Use `Related to #35` on intermediate pull requests and `Closes #35` only on the final
acceptance-completing pull request.
