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

Every enabled client-local mutating tool call passes one shared, cancellation-safe authorization gate immediately
before execution: the TUI asks locally, ACP delegates to `session/request_permission` when the connected client can
answer, and every unavailable or invalid noninteractive path denies deterministically without performing the side
effect.

## Scope

- Add explicit `ReadOnly` versus `Mutating` metadata to harness-owned tools. Classify `read`, `ls`, `find`, and `grep`
  as read-only and `bash`, `write`, and `edit` as mutating; require every future tool to declare a classification and
  make an omitted/unrecognized classification fail closed as mutating at the authorization boundary.
- Keep registry enablement and authorization separate. Unknown or allow-list-disabled calls are rejected before any
  approval request; enabled read-only calls execute without prompting; enabled mutating calls enter the approval gate.
- Add one frontend-neutral, suspendable approval seam. Stored per-tool states are Ask, Allow for session, and Deny for
  session; every mutating tool starts at Ask. Decisions are allow once, deny once, allow this tool for the current
  session, and deny this tool for the current session; once decisions do not change stored state.
- Key session decisions by stable tool name, not by argument or by the broad mutating class. A TUI `/new` or successful
  `/resume` starts a fresh policy scope; each new or loaded ACP logical session owns a separate scope. No decision is
  written to settings, the workspace trust store, or session JSONL.
- Add a main-TUI approval overlay that identifies the tool, shows its localized summary plus at most 4 KiB of wrapped,
  control-escaped argument JSON with a truncation marker, offers all four decisions, defaults to deny once, and remains
  usable on terminal resize/small terminals.
- Route approval-overlay input before the ordinary active-submission guard: arrows choose, `Enter` decides, `Esc`
  cancels the whole agent turn, and `Ctrl+C` follows normal shutdown. Other composer, command-palette, and submission
  input remains inert while approval is pending.
- Map an ACP Ask to the typed `acp-jvm:0.24.0` client operation
  `currentCoroutineContext().client.requestPermissions(...)`. Send a `ToolCallUpdate` for the exact call id, tool-name
  title/kind, `IN_PROGRESS` status, and parsed arguments as `rawInput`, plus stable options whose kinds are
  `ALLOW_ONCE`, `ALLOW_ALWAYS`, `REJECT_ONCE`, and `REJECT_ALWAYS`; ACP “always” means this
  tool for this logical ACP session only.
- Treat ACP support operationally because ACP 0.24.0 advertises no permission capability bit. A selected known option
  proves support. JSON-RPC method-not-found, a malformed/unknown selection, another protocol failure while the turn is
  still live, or a 60-second monotonic timeout denies the call and marks the permission channel unavailable for the
  remainder of that ACP session, so later Ask decisions deny immediately. A protocol `Cancelled` outcome or parent
  turn/session/connection cancellation propagates cancellation instead of becoming a denial.
- Preserve stable, nonlocalized tool errors: unknown/disabled remains exactly
  `unknown or disabled tool: <name>`; every policy denial, including ACP fallback and timeout, is
  `tool call denied by policy: <name>; do not retry without new user approval`, with `isError = true` and the original
  call id.
- Add deterministic core, TUI, and ACP tests for classification, policy scope, deny paths, protocol mapping,
  timeout/disconnect behavior, rendering/input, persistence effects, and cancellation/side-effect races.

## Non-goals

- Persisting approvals across a TUI session switch, ACP logical-session lifetime, process restart, or session reload.
- Treating saved project trust, `--approve`, `--no-approve`, a tools allow-list, or PromptAgent/Hosted binding as tool
  approval. Workspace trust continues to gate project-controlled configuration only.
- Adding an auto-approve CLI/configuration mode, project-authored approval policy, per-path/per-command rules, or a
  global “allow all mutating tools” choice.
- Attempting to infer whether a `bash` command is read-only; every `bash` call is mutating for policy purposes.
- Approval for Hosted container/server tools, MCP tools, ACP client-delegated `fs/*` or `terminal/*`, or tools not
  executed by Konductor's local `ToolExecutor`.
- Batching approvals for multiple calls. The existing Prompt loop executes a returned call list sequentially and asks
  separately for each uncached mutating call.
- Rolling back a tool that was admitted and began executing before later cancellation.
- Localizing tool identities, option ids, raw tool errors, or ACP fields. Human-facing TUI labels and explanatory copy
  remain localized through `AppStrings`.

## Acceptance

- [ ] Built-ins have an exhaustive authoritative classification, and unknown/disabled calls cannot trigger a prompt or
  execute. Read-only calls still execute under every approval-policy state.
- [ ] An enabled mutating call invokes the approval seam exactly once while Ask, executes only after allow, and returns
  the exact stable denial result after deny or unavailable headless authorization.
- [ ] Allow-once and deny-once affect only one call. Session allow/deny affects later calls with the same tool name but
  not a different mutating tool, and resets at the specified TUI/ACP session boundary without any policy write.
- [ ] The TUI overlay renders the localized summary, bounded control-safe argument detail, and four choices with
  deny-once selected by default; its exact active request owns input, stale decisions cannot release a newer request,
  and resize/tiny-terminal cases remain bounded.
- [ ] TUI `Esc`, shutdown, or parent cancellation while approval is pending clears the overlay, resumes no stale waiter,
  propagates `CancellationException`, produces no synthetic tool result, and performs no tool side effect.
- [ ] ACP sends one typed `session/request_permission` request with exact session/tool-call identity, tool kind/status,
  parsed `rawInput`, and four stable option ids/kinds; valid once/always outcomes map to call/session decisions, while
  protocol `Cancelled` cancels the turn.
- [ ] ACP method-not-found, malformed/unknown outcomes, protocol failure, and a deterministic 60-second timeout fail
  closed, never execute the tool, use the stable denial result, and make later uncached mutating calls in that logical
  session deny without another permission request. Connection/turn cancellation remains exception-transparent.
- [ ] Cancellation and approval are linearized at one side-effect admission gate. Cancellation before admission wins
  with no execution or cached session allow; after admission, existing tool cancellation and no-rollback semantics
  apply. At most one approval is pending per single-flight session, and a stale response cannot authorize another call.
- [ ] Denied calls retain the existing `ToolCallStarted` → error `ToolCallCompleted` event/persistence shape, so the
  model, TUI, and ACP see one recoverable result. Cancellation while pending may retain the already-recorded call
  entry but records no fabricated result.
- [ ] Focused and full tests plus documentation validation pass, and the tools, TUI, ACP, and architecture specs match
  the implementation.

## Context pack

Read only this first-pass context. Expand beyond it only when it is incomplete or contradicted by source.

### Specifications

- [`Safety & approval`](../spec/tools.md#safety--approval) — classification, decisions, scopes, stable errors, and the
  side-effect gate.
- [`Tool approval overlay`](../spec/tui.md#tool-approval-overlay) — interactive presentation, key routing, and
  cancellation.
- [`Mutating tool permissions`](../spec/acp.md#mutating-tool-permissions) — ACP request mapping and deterministic
  fallback.
- [`Tool authorization boundary`](../spec/architecture.md#tool-authorization-boundary) and
  [`Threading & concurrency`](../spec/architecture.md#threading--concurrency) — layer ownership and admission races.

### Source entry points

**Shared tool policy and gate**

- `src/main/kotlin/com/konductor/tool/Tool.kt` — add authoritative tool mutability metadata.
- `src/main/kotlin/com/konductor/tool/BuiltinTools.kt` — exhaustive built-in classification/order.
- `src/main/kotlin/com/konductor/tool/ToolRegistry.kt` — resolve enablement before authorization.
- `src/main/kotlin/com/konductor/tool/RegistryToolExecutor.kt` — shared authorize-then-admit-then-execute boundary,
  stable errors, cancellation transparency, and lazy execution admission.
- `src/main/kotlin/com/konductor/tool/ProviderToolRuntime.kt` — compose one session-scoped policy/approver for both
  frontends without changing model-facing `ToolSpec` schemas.
- Add a focused `src/main/kotlin/com/konductor/tool/ToolApproval.kt` (or equivalently cohesive file) for mutability,
  decisions, the suspendable frontend seam, exact denial construction, and thread-safe session state.
- `src/main/kotlin/com/konductor/provider/PromptProvider.kt` and
  `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — preserve sequential calls and existing started/completed
  folding; do not move frontend prompting into either layer.

**TUI frontend**

- `src/main/kotlin/com/konductor/Main.kt` — compose the TUI-scoped approver with the provider tool runtime; workspace
  trust is a separate input and never seeds approval state.
- `src/main/kotlin/com/konductor/core/AppState.kt` — render-facing pending approval state only.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — attach the approver, route modal keys ahead of active-submission
  cancellation, reset scope after successful session replacement, and clear/cancel during shutdown.
- Add `src/main/kotlin/com/konductor/tui/TuiToolApprover.kt` and
  `src/main/kotlin/com/konductor/tui/component/ToolApprovalView.kt` (names may vary together) — exact-request
  coordination and a stateless, terminal-bounded overlay.
- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` and
  `src/main/kotlin/com/konductor/conversation/ToolRendering.kt` — reuse bounded summaries and expose successful
  new/resume scope transitions without coupling the shared executor to Lanterna.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and
  `src/main/resources/com/konductor/i18n/messages.properties` — semantic TUI approval copy only.

**ACP frontend**

- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` — compose a fresh policy for each prepared new/loaded
  logical session.
- `src/main/kotlin/com/konductor/acp/KonductorAcpAgent.kt` — request through the session coroutine context, map exact
  ACP option/outcome types, and preserve active-turn cancellation/updates.
- Add a focused `src/main/kotlin/com/konductor/acp/AcpToolApprover.kt` with an injected client-operation seam and
  timeout
  for deterministic tests. ACP 0.24.0 supplies `CoroutineContext.client`,
  `ClientSessionOperations.requestPermissions`, `PermissionOption`, and `RequestPermissionOutcome`.

### Tests

**Shared policy/executor**

- `src/test/kotlin/com/konductor/tool/RegistryToolExecutorTest.kt` — extend for classification, no-prompt boundaries,
  exact errors, once/session scope, side-effect counts, and cancellation/admission races.
- Add `src/test/kotlin/com/konductor/tool/ToolApprovalTest.kt` if keeping policy-state tests separate improves focus.
- `src/test/kotlin/com/konductor/agent/AgentLoopTest.kt` — denied result event/persistence shape and pending-cancel
  absence of a synthetic result.

**TUI**

- `src/test/kotlin/com/konductor/tui/TuiAppKeyTest.kt` — modal-first key routing, turn cancellation, stale identities,
  shutdown, and reset boundaries.
- Add `src/test/kotlin/com/konductor/tui/TuiToolApproverTest.kt` and
  `src/test/kotlin/com/konductor/tui/component/ToolApprovalViewTest.kt` for suspend/resume races, default choice,
  bounded
  rendering, and semantic localized copy.
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — resource completeness.

**ACP**

- Add `src/test/kotlin/com/konductor/acp/AcpToolApproverTest.kt` — exact typed request/options, known and unknown
  selections, Cancelled, method-not-found/protocol error, timeout, cached unavailability, and external cancellation.
- `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — denied tool updates/results, session isolation,
  cancel while waiting, and active-turn ownership.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — fresh policy ownership for new/load and no
  relationship to project trust.

### Targeted searches

```bash
rg -n "ToolRegistry|RegistryToolExecutor|createToolRuntime|ToolCallStarted|ToolCallCompleted" \
  src/main/kotlin src/test/kotlin
rg -n "activeSubmission|routeActiveSubmissionKey|commandPaletteView|AppState|renderToolCall" \
  src/main/kotlin/com/konductor/{conversation,core,tui} src/test/kotlin/com/konductor/{conversation,tui}
rg -n "KonductorAgentSession|AcpSessionRuntime|requestPermissions|ToolCallUpdate|activeTurn" \
  src/main/kotlin/com/konductor/acp src/test/kotlin/com/konductor/acp
rg -n "WorkspaceTrust|--approve|toolAllow" \
  src/main/kotlin/com/konductor/{Main.kt,acp,config,tool} src/test/kotlin/com/konductor
```

## Decisions and constraints

- `ToolSpec` remains the stable model/SDK schema. Mutability is harness-owned execution metadata and is not localized or
  sent as a model-controlled field.
- Registry enablement is the first enforcement boundary. Approval cannot make a disabled/unknown tool available, and
  an allow-list only exposes a tool—it never authorizes a mutating call.
- The shared policy contains no Lanterna or ACP types. Frontend adapters map one domain request/decision; providers and
  `AgentLoop` remain frontend-agnostic.
- Ask is the only initial mutating state. Read-only is intrinsically allowed; there is no intrinsic mutating allow
  default. Session decisions are tool-specific and memory-only.
- Project-config trust and local side-effect authorization protect different boundaries. Existing `--approve` means
  one-run project trust only and must not silently become a broad tool bypass.
- ACP 0.24.0 has a typed client request operation but no capability bit for permissions. Do not infer support from
  unrelated `fs`, terminal, or plan capabilities. Probe only when the first uncached mutating call needs a decision,
  then cache valid session decisions or channel unavailability.
- The ACP option ids are stable protocol values such as `allow_once`, `allow_for_session`, `deny_once`, and
  `deny_for_session`; labels explain the session/tool scope, while `ALLOW_ALWAYS`/`REJECT_ALWAYS` are protocol kinds,
  not durable Konductor promises.
- The authorization coordinator uses exact call/request identity and a single pending slot. Prompt tool calls are
  already sequential; overlap remains a fail-closed invariant rather than a queue.
- Authorization and execution have an explicit admission point. An allow-for-session decision is cached only when that
  call wins admission, preventing cancellation between the UI/protocol decision and admission from leaving a stale
  broad allowance. Deny-for-session may be cached when its exact pending request wins because it cannot cause a side
  effect.
- The ACP adapter handles its own `TimeoutCancellationException` as denial and rethrows every other
  `CancellationException` before generic protocol fallback. ACP's permission `Cancelled` outcome intentionally becomes
  turn cancellation. A timeout is a policy denial, not a turn timeout.
- One implementation packet and one implementation PR should land shared policy, TUI, and ACP together. Staging would
  either leave one frontend executing mutating calls without the accepted shared gate or temporarily deny all ACP
  mutation without its protocol path. Organize implementation and review in core → TUI → ACP commits/tests inside
  that PR rather than creating staged packets.
- No Azure SDK/service API is involved; this delivery cannot produce Foundry service feedback.

## Validation

```bash
./mvnw -q -Dtest=RegistryToolExecutorTest,ToolApprovalTest,AgentLoopTest test
./mvnw -q -Dtest=TuiToolApproverTest,ToolApprovalViewTest,TuiAppKeyTest,AppStringsTest test
./mvnw -q -Dtest=AcpToolApproverTest,KonductorAgentSessionTest,AcpSessionRuntimeFactoryTest test
./mvnw -q test
node scripts/validate-docs.mjs
git diff --check
```

The implementation PR should also exercise an in-process ACP client/agent permission round trip if the ACP SDK test
harness can do so without Foundry access. No live Foundry validation is required for the authorization state machine.

## Completion

Record the final pull request, resulting behavior, and any excluded follow-ups promoted to focused issues or durable
`future.md` direction. Use `Closes #38` only when both frontends and the shared gate meet this packet.
