---
id: I038
title: Mutating tool approval policy
issue: https://github.com/jpalvarezl/Konductor/issues/38
created: 2026-08-11
---

# I038 — Mutating Tool Approval Policy

Issue [#38](https://github.com/jpalvarezl/Konductor/issues/38) owns discussion, assignment, dependencies, status, and
closure. This packet is the repository-local implementation brief and context pack.

## Outcome

Every valid, enabled, client-local mutating tool invocation passes one shared, cancellation-safe authorization gate
immediately before execution: the TUI asks locally, ACP delegates to `session/request_permission`, and every missing,
unavailable, malformed, or invalid noninteractive authorization path denies deterministically without a side effect.

## Scope

- Add a required, non-null `ToolMutability` enum property with no default to every harness-owned `Tool`, and dispatch it
  with an exhaustive `when` and no `else`. The exact built-in table is `read`/`ls`/`find`/`grep` = `ReadOnly` and
  `bash`/`write`/`edit` = `Mutating`; registry construction rejects duplicate names.
- Preserve `grep` as read-only by removing bare-name/PATH `rg` discovery and all external-executable configuration.
  I038 uses only in-process search. A future #46 revision may add a canonical absolute application-bundled `rg` only
  with regular-file/no-symlink/install-root checks and SHA-256 matching immutable distribution metadata, all independent
  of cwd, project config/trust, `PATH`, and model input.
- Keep enablement, preparation, authorization, and containment separate and ordered. Unknown or allow-list-disabled
  calls fail before preparation. An enabled tool purely parses a top-level JSON object and strictly schema-validates
  required/type/unknown-key rules into one immutable `PreparedToolInvocation` before policy lookup or prompting.
  Read-only prepared calls execute without prompting; mutating prepared calls enter the shared gate.
- Make malformed arguments return exactly `invalid arguments for tool: <name>` with the original call id and
  `isError = true`. They must not prompt, consume or seed a session decision, mark ACP unavailable, or execute. The
  prepared typed values and parsed `JsonObject` are the only values shown, authorized, mapped to ACP `rawInput`, and
  executed; tools do not parse a second payload after approval.
- Add one frontend-neutral suspendable `ToolApprover` seam and one session policy. Missing/direct/headless composition
  uses a deny-all approver, never implicit allow. Stored per-tool states are Ask, Allow for session, and Deny for
  session; decisions are allow once, deny once, allow this tool for this session, and deny this tool for this session.
- Key session decisions by stable tool name. TUI `/new` or successful `/resume` creates a fresh scope; each new or
  loaded ACP logical session owns a separate scope. Approval state is never written to settings, workspace trust, or
  session JSONL.
- Give every mutating call an exact identity (scope generation + call id + unforgeable nonce) and a linearizable state:
  `Pending(identity) → Admitted(identity) | Denied(identity) | Cancelled(identity)`. A returned allow is only a
  candidate; the executor checks caller activity and must win exact admission before caching session allow or invoking
  the prepared tool. Cancellation and deny target the same identity; losing/stale/duplicate/overlapping paths do
  nothing and fail closed.
- Add a main-TUI approval overlay showing stable identity, a localized summary, canonical prepared argument JSON, and
  four decisions. Deny once is selected by default. Both summary and detail are sanitized after localization/encoding:
  normalize line endings; visibly escape terminal C0/C1/DEL/ESC and Unicode bidi controls; retain LF only in detail.
  Bound complete post-escape UTF-8 output—including truncation marker—to 512 bytes for summary and 4096 bytes for
  detail. Build summary and canonical JSON from indivisible safe-scalar, punctuation, complete JSON-escape, and
  complete-visible-control-escape tokens so truncation splits neither UTF-8 nor an escape sequence.
- Route approval input before the ordinary active-submission guard. Arrows select, `Enter` submits the candidate,
  `Esc` atomically cancels the exact pending request and whole turn, and `Ctrl+C` does the same before normal shutdown.
  Other composer/palette/submission input is inert. Resize and tiny-terminal rendering remain bounded.
- Map ACP Ask to `currentCoroutineContext().client.requestPermissions(...)` from `acp-jvm:0.24.0`. Send exact session
  and call ids, tool title/kind, `IN_PROGRESS`, prepared `rawInput`, and stable `ALLOW_ONCE`, `ALLOW_ALWAYS`,
  `REJECT_ONCE`, and `REJECT_ALWAYS` options. ACP “always” means this tool in this logical session only.
- Because ACP 0.24.0 has no permission capability bit, probe operationally on the first valid uncached mutating call.
  Method-not-found, malformed/unknown selection, live protocol failure, or a 60-second monotonic permission timeout
  denies and marks that logical session's channel unavailable. Protocol `Cancelled` and parent turn/session/connection
  cancellation propagate cancellation.
- Implement the permission timeout as an exact competing timer result (for example cancellation-transparent `select`
  with `onTimeout`), not by catching `TimeoutCancellationException`. An outer `withTimeout` must remain the original
  cancellation and must not synthesize denial or poison channel availability.
- Keep `PromptProvider`'s adjacent-identical-call suppression before `ToolExecutor`, using its existing exact adjacent
  `(name, argumentsJson)` key excluding call id. A suppressed duplicate executes nothing, so it does not prompt,
  consult/change policy, or consume allow-once. The first non-suppressed call follows the complete gate and cancellation
  is never converted into duplicate output.
- Centralize exact nonlocalized result construction for unknown/disabled, malformed, and policy-denied calls. Preserve
  `unknown or disabled tool: <name>` and
  `tool call denied by policy: <name>; do not retry without new user approval`, original call id, and `isError = true`.
- Add deterministic shared, PromptProvider, Main/composition, TUI, ACP, persistence, timeout, and real protocol routing
  tests listed below.

## Non-goals

- Persisting approvals across a TUI session switch, ACP logical-session lifetime, process restart, or session reload.
- Treating saved project trust, `--approve`, `--no-approve`, a tools allow-list, or PromptAgent/Hosted binding as tool
  approval. Workspace trust continues to gate project-controlled configuration only.
- Adding auto-approve CLI/configuration, project-authored policy, per-path/per-command rules, or global “allow all.”
- Inferring whether a `bash` command is read-only; every `bash` call is mutating.
- Executing arbitrary `rg` from PATH and compensating with a permission prompt. `grep` remains intrinsically read-only.
- Approval for Hosted container/server tools, MCP tools, ACP client-delegated `fs/*` or `terminal/*`, or tools outside
  Konductor's local `ToolExecutor`.
- Batching approvals. Prompt calls are sequential and each non-suppressed, uncached mutating invocation asks separately.
- Removing PromptProvider's existing adjacent-duplicate no-execution nudge.
- Rolling back a tool after `Admitted` wins and execution begins.
- Localizing tool identities, option ids, stable tool errors, model payloads, or ACP fields. TUI explanatory copy remains
  localized through `AppStrings`.
- Completing the broader ACP transcript golden protocol test; I038 owns a focused real permission round trip.

## Acceptance

- [ ] Kotlin makes classification explicit: `Tool.mutability` has no default, executor matching is exhaustive, registry
  rejects duplicate names, and tests assert the exact built-in table. No string/nullable/unknown mutability can become
  read-only.
- [ ] `grep` never probes or launches bare `rg` from PATH and exposes no executable override. I038 production uses
  only in-process search; a fake workspace/PATH `rg` is never run.
- [ ] Unknown/disabled calls cannot prompt or execute. Valid read-only calls execute under every policy state. Missing
  approver context deterministically denies valid mutating calls.
- [ ] Strict JSON/schema preparation happens once before policy. Malformed, non-object, missing/wrong-type, and unknown
  properties produce the exact malformed error and leave approver count, side-effect count, session cache, and ACP
  channel availability unchanged. Valid cached allow/deny calls still use `Pending → Admitted/Denied`; cache hits never
  bypass cancellation linearization.
- [ ] An Ask mutating call invokes its approver exactly once and executes only after exact admission. Once and session
  decisions have the documented tool-specific scope and reset at TUI/ACP session boundaries without any policy write.
- [ ] The TUI overlay renders validated content, four choices, and deny-once default. Summary and detail contain no raw
  terminal/bidi controls; complete post-escape UTF-8 sizes are ≤512 and ≤4096 bytes respectively including markers;
  tests prove no UTF-8 sequence or visible escape token is split and tiny-terminal cases stay bounded.
- [ ] TUI `Esc`, shutdown, parent cancellation, and event-loop failure target the exact pending identity, clear the
  overlay, propagate cancellation, create no synthetic result, cache no allow, and perform no side effect. Stale input
  cannot release a new request.
- [ ] ACP sends one typed request with exact session/tool identity, prepared `rawInput`, and stable options. Known once
  and always selections map correctly; protocol `Cancelled` cancels the turn.
- [ ] Method-not-found, malformed/unknown outcome, live protocol failure, and the adapter's own deterministic 60-second
  timer fail closed and cache channel unavailability only in that logical session. Later uncached mutation denies
  without another request.
- [ ] A shorter outer timeout, `session/cancel`, connection cancellation, and caller cancellation preserve their
  original `CancellationException`, do not look like permission timeout, do not mark the channel unavailable, and allow
  a later live request to probe again.
- [ ] `Pending → Admitted | Denied | Cancelled` is atomic. Cancellation-before-admission wins with no execution/cache;
  admission-before-cancellation uses existing no-rollback cleanup. Identity mismatch, late/duplicate response, and
  overlap fail closed.
- [ ] PromptProvider suppresses only adjacent identical calls before the executor. Tests prove the first mutating call
  authorizes/executes once, the duplicate does neither, a changed/nonadjacent call re-enters authorization, and
  cancellation is exception-transparent.
- [ ] Stable errors come from one shared constructor/factory and match exactly in shared, TUI/ACP mapping, and persisted
  reload assertions.
- [ ] Denial/malformed calls retain `ToolCallStarted → ToolCallCompleted(error)` and persist one call/result pair.
  Pending cancellation may retain the call entry but persists no fabricated result. Reload contains no authorization
  state; a rebuilt runtime asks again.
- [ ] A mandatory in-process ACP SDK client/agent transport test observes real `session/request_permission` on two
  logical sessions and proves exact session/call routing, option response mapping, side-effect ownership, and no
  cross-session waiter release.
- [ ] Focused/full tests, documentation validation, and diff checks pass; tools/TUI/ACP/architecture/future agree.

## Context pack

Read only this first-pass context. Expand beyond it only when incomplete or contradicted by source.

### Specifications

- [`The Tool interface`](../spec/tools.md#the-tool-interface),
  [`Search`](../spec/tools.md#search-ripgrep-globs--ignored-dirs), and
  [`Safety & approval`](../spec/tools.md#safety--approval) — preparation, classification, executable trust, policy,
  state machine, duplicate suppression, and stable errors.
- [`Tool approval overlay`](../spec/tui.md#tool-approval-overlay) — sanitized bounded presentation/input/cancellation.
- [`Mutating tool permissions`](../spec/acp.md#mutating-tool-permissions) — request mapping, timeout identity, routing.
- [`Tool authorization boundary`](../spec/architecture.md#tool-authorization-boundary) and
  [`Threading & concurrency`](../spec/architecture.md#threading--concurrency) — ownership and admission races.

### Source entry points

**Shared preparation, policy, and gate**

- `src/main/kotlin/com/konductor/tool/Tool.kt` — required enum and pure prepared invocation contract.
- `src/main/kotlin/com/konductor/tool/BuiltinTools.kt` — exact exhaustive built-in classification/order.
- `src/main/kotlin/com/konductor/tool/ToolRegistry.kt` — duplicate rejection and enablement before preparation.
- Every built-in `*Tool.kt` — move strict typed JSON/schema decoding into pure `prepare`; execute captured typed values.
- `src/main/kotlin/com/konductor/tool/GrepTool.kt` — remove PATH probing/bare process execution and executable override;
  I038 production is in-process only.
- `src/main/kotlin/com/konductor/tool/RegistryToolExecutor.kt` — ordered prepare/authorize/admit/execute and errors.
- `src/main/kotlin/com/konductor/tool/ProviderToolRuntime.kt` — explicit approver composition with deny-all default.
- Add `src/main/kotlin/com/konductor/tool/ToolApproval.kt` for requests, decisions, stable error factories, thread-safe
  scope cache, and exact atomic state.
- `src/main/kotlin/com/konductor/provider/PromptProvider.kt` — preserve duplicate suppression position and cancellation.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — preserve event/persistence folding; no frontend prompts.

**TUI frontend**

- `src/main/kotlin/com/konductor/Main.kt` — inject the TUI approver explicitly; project trust is never approval input.
- `src/main/kotlin/com/konductor/core/AppState.kt` — render-facing exact pending request only.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — modal-first keys, exact cancellation, scope reset, shutdown cleanup.
- Add `src/main/kotlin/com/konductor/tui/TuiToolApprover.kt` and
  `src/main/kotlin/com/konductor/tui/component/ToolApprovalView.kt` (names may vary together).
- `src/main/kotlin/com/konductor/conversation/ToolRendering.kt` — derive summary from prepared input; do not treat current
  compact rendering as terminal-safe approval copy.
- Add one shared approval presentation sanitizer/bounder in the TUI layer and reuse it for summary and detail.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and root message bundle — semantic labels/markers only.

**ACP frontend**

- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` — fresh policy/approver per prepared new/loaded session.
- `src/main/kotlin/com/konductor/acp/KonductorAcpAgent.kt` — exact session coroutine client and active-turn routing.
- Add `src/main/kotlin/com/konductor/acp/AcpToolApprover.kt` with injected client operation and monotonic timer/select
  seam. It must not catch timeout subclasses to identify its own timer.
- ACP 0.24.0 supplies `CoroutineContext.client`, `ClientSessionOperations.requestPermissions`, `PermissionOption`, and
  `RequestPermissionOutcome`.

### Test map

**Shared/preparation/executor**

- `src/test/kotlin/com/konductor/tool/RegistryToolExecutorTest.kt` — exact classification, duplicate registry names,
  unknown/disabled/read-only/default-deny boundaries, stable factories, strict malformed variants, single parse,
  once/session scope, side-effect counts, and atomic admit/cancel races.
- Add `src/test/kotlin/com/konductor/tool/ToolApprovalTest.kt` — exact identity, stale/duplicate/overlap, per-tool scope,
  and no allow/deny cache poisoning.
- `src/test/kotlin/com/konductor/tool/GrepToolTest.kt` — fake cwd/PATH `rg` is never executed; search stays in-process.
- `src/test/kotlin/com/konductor/provider/PromptProviderTest.kt` — duplicate behavior relative to authorization, changed
  and nonadjacent calls, denial result, and cancellation transparency.
- `src/test/kotlin/com/konductor/agent/AgentLoopTest.kt` and `AgentLoopSessionTest.kt` — event shape and durable reload:
  denial/malformed persist exact call/result; pending cancellation stores no fabricated result; approval state does not
  serialize and a rebuilt runtime asks again.
- `src/test/kotlin/com/konductor/MainTest.kt` — production TUI composition injects its approver, missing composition
  denies, and `--approve`/workspace trust cannot seed a tool allow.

**TUI**

- `src/test/kotlin/com/konductor/tui/TuiAppKeyTest.kt` — modal-first keys, exact turn cancellation, stale identities,
  shutdown/event-loop failure, and successful new/resume reset boundaries.
- Add `src/test/kotlin/com/konductor/tui/TuiToolApproverTest.kt` — suspension, winning transitions, parent cancellation,
  and no stale waiter.
- Add `src/test/kotlin/com/konductor/tui/component/ToolApprovalViewTest.kt` — default, order, tiny/resize clipping,
  control/ESC/OSC and bidi payloads in summary/detail, exact post-escape UTF-8 boundary/marker assertions, and no
  partial visible escape token.
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — semantic resource/marker completeness.

**ACP**

- Add `src/test/kotlin/com/konductor/acp/AcpToolApproverTest.kt` — exact typed request/options/prepared `rawInput`, all
  known outcomes, Cancelled, method-not-found/protocol failure, malformed selection, own timer, cached unavailability,
  and external cancellation.
- The outer-timeout test wraps permission wait in a timeout shorter than the injected policy timer, asserts the same
  cancellation escapes, asserts channel availability remains Probe, and proves the next call requests permission.
- `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — denied/malformed updates/results, cancel waiting,
  active-turn ownership, session isolation, and durable event mapping.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — fresh policy ownership for new/load, deny default,
  and no relationship to project trust.
- Add `src/test/kotlin/com/konductor/acp/AcpPermissionRoundTripTest.kt` — mandatory in-process SDK protocol transport with
  two sessions and real client-bound `session/request_permission`; no Foundry/network dependency.

### Targeted searches

```bash
rg -n "ToolRegistry|RegistryToolExecutor|createToolRuntime|ToolCallStarted|ToolCallCompleted" \
  src/main/kotlin src/test/kotlin
rg -n "RIPGREP|ProcessBuilder|ToolRendering|argumentsJson|duplicateCallNudge" \
  src/main/kotlin/com/konductor src/test/kotlin/com/konductor
rg -n "activeSubmission|routeActiveSubmissionKey|commandPaletteView|AppState" \
  src/main/kotlin/com/konductor/{conversation,core,tui} src/test/kotlin/com/konductor/{conversation,tui}
rg -n "KonductorAgentSession|AcpSessionRuntime|requestPermissions|ToolCallUpdate|activeTurn" \
  src/main/kotlin/com/konductor/acp src/test/kotlin/com/konductor/acp
rg -n "WorkspaceTrust|--approve|toolAllow" \
  src/main/kotlin/com/konductor/{Main.kt,acp,config,tool} src/test/kotlin/com/konductor
```

## Decisions and constraints

- `ToolSpec` remains the stable model/SDK schema. Required enum mutability and prepared execution are harness-owned and
  never model-controlled/localized.
- Compile-time required enum + exhaustive `when` is the chosen enforceable classification. Do not use a nullable/string
  classification with a runtime “unknown means mutating” branch.
- Strict preparation is pure and precedes policy. Schema-invalid input cannot be approved. Exact immutable typed args
  close the parse-after-cache gap; a later valid same-tool call after session allow remains allowed by documented scope.
- Registry enablement remains first. Tool allow-lists expose capability only; approval never enables one.
- `grep` read-only classification requires trusted execution, not workspace trust. I038 is in-process only. #46 may
  revise this spec to add a canonical bundled absolute executable with immutable-distribution SHA-256 verification;
  project/global settings and environment can never select it.
- The shared policy contains no Lanterna/ACP types. Providers and `AgentLoop` remain frontend-agnostic. Missing approver
  context uses the shared deny-all implementation.
- Ask is the only initial mutating state. Session decisions are tool-specific and memory-only. Project `--approve` is
  one-run project configuration trust and cannot seed policy.
- The atomic admission state, not frontend response arrival, is the authorization linearization point. Every valid
  mutating call enters Pending; cached decisions drive immediate Admitted/Denied transitions rather than bypassing it.
  Allow-for-session caches only on `Admitted`; deny-for-session only on `Denied`; cancellation only on `Cancelled`.
- Prompt duplicate suppression is a no-execution optimization before authorization. It cannot use or create permission.
- ACP support is operationally probed; unrelated capabilities imply nothing. Valid known selection proves support.
- Option ids are `allow_once`, `allow_for_session`, `deny_once`, and `deny_for_session`; labels explain tool/session
  scope while protocol kinds remain `ALLOW_ALWAYS`/`REJECT_ALWAYS`.
- ACP permission timeout has its own typed timer branch and request identity. Outer/parent cancellation is
  exception-transparent and never recognized by checking `TimeoutCancellationException`.
- Stable errors are centrally built and remain below output caps. Frontends carry results; they do not duplicate copy.
- One implementation packet/PR lands core, TUI, and ACP. The focused in-process permission round trip is mandatory even
  if building it requires a test-only ACP transport harness; “if the SDK harness can” is not an escape hatch.
- No Azure service API is involved, so this delivery cannot produce Foundry service feedback.

## Validation

```bash
./mvnw -q -Dtest=RegistryToolExecutorTest,ToolApprovalTest,GrepToolTest,PromptProviderTest test
./mvnw -q -Dtest=AgentLoopTest,AgentLoopSessionTest,MainTest test
./mvnw -q -Dtest=TuiToolApproverTest,ToolApprovalViewTest,TuiAppKeyTest,AppStringsTest test
./mvnw -q -Dtest=AcpToolApproverTest,KonductorAgentSessionTest,AcpSessionRuntimeFactoryTest,AcpPermissionRoundTripTest test
./mvnw -q test
node scripts/validate-docs.mjs
git diff --check
```

No live Foundry validation is required. The in-process ACP permission round trip is required and must use the real SDK
client/agent protocol path rather than invoking `AcpToolApprover` directly.

## Completion

Record the final pull request, resulting behavior, and excluded follow-ups promoted to focused issues or durable
`future.md` direction. Use `Closes #38` only when both frontends, shared gate, durable tests, and real ACP round trip meet
this packet.
