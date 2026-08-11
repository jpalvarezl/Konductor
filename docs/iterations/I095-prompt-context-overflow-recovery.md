---
id: I095
title: Prompt context-overflow recovery
issue: https://github.com/jpalvarezl/Konductor/issues/95
created: 2026-07-30
---

# I095 — Prompt Context-Overflow Recovery

Issue [#95](https://github.com/jpalvarezl/Konductor/issues/95) owns discussion, assignment, dependencies, status, and
closure. This packet is the repository-local implementation brief and context pack.

## Outcome

A Prompt turn that receives a precisely classified Foundry Responses context-overflow failure before producing model
output or tool activity compacts the client-owned transcript once and retries that same recorded user turn once, while
unsafe, cancelled, Hosted, and exhausted cases terminate without replay.

## Scope

- Classify the current Azure-composed openai-java Responses failure at the two Prompt adapter boundaries by HTTP status
  and structured service code, then expose only an SDK-free Prompt overflow type above that boundary.
- Recover only the first eligible overflow from the main Prompt provider attempt: compact once, durably commit the
  marker, reconstruct history, and retry once without recording another `UserEntry`.
- Gate recovery on a client-owned Prompt transcript and the absence of assistant output, completed model output, tool
  call/result events, tool execution, or other turn side effects.
- Define cancellation, failure precedence, event emission, transcript persistence, proactive-compaction interaction,
  and the one-attempt cap.
- Add deterministic offline classifier, loop, persistence, cancellation, and capability tests for ephemeral Prompt and
  persisted PromptAgent paths.

## Non-goals

- Retrying a turn after any assistant delta, completed response, tool call/result, or possible tool side effect.
- Recovering an overflow caused by a tool result produced during the same turn; a future safe continuation policy would
  need an explicit non-replay design.
- Hosted recovery or inspection of Hosted errors/history; its context and loop are server-owned.
- Message-substring, bare-HTTP-400, token-estimator, or model-name heuristics for service-error classification.
- A generic provider retry framework, changes to transient 429/5xx/timeout backoff, or additional PromptAgent retries.
- New persisted failure/retry entry types, rollback of an accepted user or compaction entry, or SDK types above
  `provider/inference`.
- A more aggressive reactive cut policy, configuration knob, server conversation, or `previousResponseId` flow.

## Acceptance

- [x] Both Prompt Responses adapters map only an openai-java HTTP `400` whose structured code is exactly
  `context_length_exceeded` to the SDK-free overflow type; message-only, other-code, other-status, malformed, and
  cancellation fixtures remain non-overflow failures.
- [x] An eligible first-call overflow records one user entry, commits and emits one reactive compaction, reconstructs
  the reduced transcript, and retries the provider once; successful retry records the ordinary terminal assistant and
  does not emit the swallowed first failure.
- [x] Any assistant delta, usage/completed output, tool-call start, tool result, or possible tool side effect makes the
  overflow ineligible; the original failure is surfaced with no compaction or replay.
- [x] No compactable span, summary failure, rewrite failure, and retry failure each emit one stage-specific terminal
  failure with the prescribed cause precedence; a second overflow never starts another recovery cycle.
- [x] Cancellation before or during compaction/retry propagates without `AgentEvent.Failed`; accepted user and
  compaction state remains durable and no cancelled work is replayed.
- [x] Auto-compaction enabled/disabled, proactive success/summary failure/rewrite failure, PromptAgent binding, and
  Hosted exclusion follow the owning specs and are covered offline.
- [x] Focused tests, full tests, package, documentation validation, and diff checks pass without a live Foundry call.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Reactive context-overflow recovery`](../spec/compaction.md#reactive-context-overflow-recovery) — eligibility,
  bounded cycle, proactive interaction, cancellation, events, and failure precedence.
- [`Context-overflow classification`](../spec/providers.md#context-overflow-classification) — exact openai-java
  classification and SDK containment.
- [`Turn lifecycle (Prompt provider)`](../spec/architecture.md#turn-lifecycle-prompt-provider) and
  [`Error handling & retry`](../spec/architecture.md#error-handling--retry) — layer ownership and retry boundary.
- [`Overflow-recovery persistence`](../spec/sessions.md#overflow-recovery-persistence) — transcript and restart
  behavior.

### Source entry points

- `src/main/kotlin/com/konductor/provider/inference/FoundryResponsesFailure.kt` — **new**
  `PromptContextOverflowException`, exact structured classifier, and SDK-to-domain mapping shared by both Prompt
  adapters.
- `src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt` — `respond`,
  `respondStreaming`, and `isTransientFoundryResponsesError`; classify overflow before the existing transient policy.
- `src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt` — `respond` and
  `respondStreaming`; apply the same mapping without adding generic transient retries.
- `src/main/kotlin/com/konductor/provider/PromptProvider.kt` — `runTurn`; preserve the SDK-free typed error in
  `AgentEvent.Failed` and retain exception-transparent cancellation.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — `runTurnSingleFlight`, provider-event folding,
  `recordCompaction`, and new `ContextOverflowRecoveryException`/stage plus one-turn recovery state; swallow only the
  eligible first overflow, compact/rebuild, and drive one retry without re-entering public `AgentLoop.runTurn`.
- `src/main/kotlin/com/konductor/compaction/Compactor.kt` — `compact`, `planCut`, and `summarize`; reuse the existing
  cut, no-tools summary, and nullable no-plan result rather than introducing an overflow-specific algorithm.
- `src/main/kotlin/com/konductor/session/SessionHistory.kt` — `reconstructHistory`; rebuild the retry request from the
  durably committed marker and kept entries.

### Tests

- `src/test/kotlin/com/konductor/provider/inference/FoundryResponsesFailureTest.kt` — **new** fixture-driven exact
  surface-exception classifier tests, cancellation, and negative cases with no network.
- `src/test/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClientRetryTest.kt` — **new** assertion
  that overflow bypasses transient retries/status/delay while existing transient behavior stays unchanged.
- `src/test/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClientFailureTest.kt` — **new** same
  domain mapping for agent-scoped input-only calls without adding a generic retry loop.
- `src/test/kotlin/com/konductor/agent/AgentLoopCompactionTest.kt` — eligible recovery, no compactable span, proactive
  interactions, one-attempt cap, PromptAgent/client-history capability gate, and Hosted exclusion.
- `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — exactly-one user, event order, partial-output/tool
  safety gates, restart-visible marker/order, stage failure precedence, and successful-retry assistant persistence.
- `src/test/kotlin/com/konductor/agent/AgentLoopTest.kt` — cancellation during recovery and no post-cancellation replay.
- `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — reuse existing atomic rewrite failure fixtures;
  add only the restart assertion needed for the recovered transcript.

### Offline classifier fixtures

Implementation adds these bodies under
`src/test/resources/provider/inference/context-overflow/`; tests parse them locally and construct the pinned
openai-java 4.14.0 service exception. No recording, credential, endpoint, or service call participates.

`context-length-exceeded.json` — the only positive body when paired with HTTP `400`:

```json
{
  "error": {
    "message": "Fixture input exceeds the model context window.",
    "type": "invalid_request_error",
    "param": "input",
    "code": "context_length_exceeded"
  }
}
```

`message-only.json` — negative even though prose mentions context length:

```json
{
  "error": {
    "message": "This model's maximum context length was exceeded.",
    "type": "invalid_request_error",
    "param": "input",
    "code": null
  }
}
```

`other-bad-request.json` — negative structured 400:

```json
{
  "error": {
    "message": "A required input item is invalid.",
    "type": "invalid_request_error",
    "param": "input",
    "code": "invalid_prompt"
  }
}
```

`malformed.json` — negative unparseable payload:

```text
{"error":{"code":"context_length_exceeded"
```

The fixture matrix is exact:

| Case | Status | Body/cause | Overflow? |
|---|---:|---|---|
| structured code | 400 | `context-length-exceeded.json` | yes |
| wrong status | 429, 500 | `context-length-exceeded.json` | no |
| message only | 400 | `message-only.json` | no |
| other code | 400 | `other-bad-request.json` | no |
| malformed/missing body | 400 | `malformed.json` or no parsed error | no |
| cancellation | — | direct `CancellationException` delivered to the adapter | propagate; never classify |

### Targeted searches

```bash
rg -n "runTurnSingleFlight|recordCompaction|tracker.shouldCompact|AgentEvent.Failed" \
  src/main/kotlin/com/konductor/agent src/test/kotlin/com/konductor/agent
rg -n "respondStreaming|OpenAIServiceException|isTransientFoundryResponsesError|Retrying" \
  src/main/kotlin/com/konductor/provider src/test/kotlin/com/konductor/provider
rg -n "reconstructHistory|rewrite\(|CompactionEntry|UserEntry" \
  src/main/kotlin/com/konductor/session src/test/kotlin/com/konductor/session
```

## Decisions and constraints

### Classification and SDK boundary

- The pinned Responses transport is openai-java 4.14.0, reached through Azure Agents 2.2.0's composed
  `OpenAIClient`. The classifier therefore recognizes `OpenAIServiceException.statusCode() == 400` plus the exact,
  case-sensitive structured `code() == "context_length_exceeded"`. It does not add a speculative
  `HttpResponseException` payload parser to a path that does not currently emit that type.
- Classification is shared by the ephemeral and agent-scoped Prompt adapters and occurs before transient
  classification. The 400 is never backoff-retried and never emits `FoundryResponsesEvent.Retrying`.
- The adapter seam expects the exact SDK exception directly. A LIVE overflow probe through the Azure Agents-composed
  client produced a direct openai-java `BadRequestException` with no nested cause, matching the documented
  `OpenAIServiceException` surface. The classifier therefore checks only that delivered throwable after propagating a
  direct `CancellationException`. Missing accessors, wrappers, malformed bodies, unknown failures, and future service
  codes fail closed as ordinary errors; messages are diagnostic only.
- The adapters throw `PromptContextOverflowException` with stable application meaning and retain the SDK exception
  only as a diagnostic cause. `PromptProvider` and `AgentLoop` branch solely on the application type and import no
  OpenAI/Azure exception or payload type. If a later SDK changes the concrete error contract, update this chokepoint
  and its offline fixtures rather than leaking SDK inspection upward.

### Eligibility and one-cycle state machine

- Recovery is available only when capabilities say `clientCompaction` and `SessionHistoryOwnership.Client`; this
  includes ephemeral Prompt and bound PromptAgent and excludes Hosted even if a faulty provider emits the typed error.
- The eligible error is the first main provider attempt's `AgentEvent.Failed(PromptContextOverflowException)`. Before
  that event, only retry-status events may have been emitted. Any `TextDelta` (including an empty delta),
  `UsageReported`, `ToolCallStarted`, `ToolCallCompleted`, or `TurnCompleted` marks the attempt unsafe. This is stricter
  than inferring whether a particular tool began its external side effect.
- The loop records and persists the submitted `UserEntry` once, before proactive or reactive compaction. Recovery does
  not recursively call public `AgentLoop.runTurn`; it keeps the single-flight lock, invokes `Compactor.compact` once,
  requires a non-null marker, commits it through `recordCompaction`, reconstructs history, and drives the provider once
  more.
- The initial overflow `Failed` is withheld while recovery runs. A successful compaction emits the existing
  `AgentEvent.Compacted` before retry output. A successful retry then emits/persists ordinary events. There is no
  generic `Retrying` event for this immediate compact-and-retry transition and no synthetic persistence record.
- The per-logical-turn recovery budget is consumed when reactive compaction starts. A failure during compaction and
  any retry failure—including a second overflow—ends the turn. Compactor summary calls and adapter transient retries
  do not recursively acquire another overflow-recovery budget.

### Proactive compaction, persistence, and failure precedence

- Reactive recovery is a correctness fallback and does not depend on `CompactionSettings.enabled`; that setting
  controls only the usage-based proactive trigger. Both paths still require the Prompt client-compaction capability.
- Proactive work does not consume the reactive budget. If proactive compaction succeeds but the first main request
  still overflows, one reactive cycle may re-compact survivors using the unchanged cut policy. If there is no further
  compactable span, recovery stops without a retry. A proactive summary failure remains best-effort and an ensuing
  overflow may use the reactive cycle. A proactive rewrite failure remains terminal before any main provider call.
  At most two `Compacted` events can therefore occur in one turn: one proactive and one reactive.
- A committed compaction marker is not rolled back if retry fails or is cancelled. It is a valid transcript rewrite,
  and restart reconstruction uses it. The one accepted user entry also remains. Safe eligibility guarantees there are
  no first-attempt assistant/tool entries to remove. Partial text is display-only and never persisted.
- Cancellation has absolute precedence and propagates without `AgentEvent.Failed`. During summary generation it
  commits no marker; after atomic rewrite/in-memory commit it may leave the valid marker even if cancellation prevents
  event delivery or retry. The implementation checks coroutine activity before starting the retry.
- Otherwise the loop emits exactly one terminal `AgentEvent.Failed`. Ineligible recovery forwards the original
  overflow. Eligible recovery uses an SDK-free `ContextOverflowRecoveryException` with stage
  `NO_COMPACTABLE_HISTORY`, `COMPACTION`, or `RETRY`: the no-plan case retains the original overflow as cause;
  compaction/rewrite failure or retry failure is the primary cause and the original overflow is retained as suppressed
  diagnostic context. No lower-precedence failure replaces cancellation, and no original failure is emitted first.

## Validation

```bash
./mvnw -q \
  -Dtest=FoundryResponsesFailureTest,EphemeralFoundryResponsesClientRetryTest,PromptAgentFoundryResponsesClientFailureTest,AgentLoopCompactionTest,AgentLoopSessionTest,AgentLoopTest,JsonlSessionStoreTest \
  test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

All context-overflow classification and recovery coverage is offline. A live overflow recording is not required for
acceptance; any future observed service payload that differs from the exact fixture contract must fail closed until the
adapter classifier and fixtures are deliberately updated.

## Completion

Implemented in [PR #121](https://github.com/jpalvarezl/Konductor/pull/121). Validation uses offline structured-error
fixtures and deterministic adapter, loop, persistence, capability, cancellation, and failure-precedence tests, plus the
full Maven test/package lifecycle, documentation routes, and diff checks. No live Foundry call or schema change is
required.
