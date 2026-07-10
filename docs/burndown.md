# Foundations Burndown (Historical)

Completion record for the M0–M6 and ACP foundations cycle described in
[implementation-roadmap.md](implementation-roadmap.md).

This file is frozen. It preserves what the foundations cycle delivered and which acceptance items were not completed
inside that cycle. Current and ready work lives in [`iterations/`](iterations/index.md); unscheduled follow-ups live
in [`future.md`](future.md). Do not add new work here.

Historical legend: `- [x]` delivered in the foundations cycle · `- [ ]` not delivered in that cycle. Unchecked items
are not active tasks; their canonical disposition is in [`iterations/`](iterations/index.md) or
[`future.md`](future.md).

> _Closed: 2026-07-10 — **M0–M5 foundations are implemented**; M6 and ACP Phase C delivered their core behavior with
> explicitly deferred follow-ups.
> The Prompt path has streamed Foundry inference, the 7 cwd-contained built-in tools, append-as-produced JSONL
> sessions, client-side compaction, and opt-in persisted PromptAgents. The Hosted provider is live-verified behind
> `ProviderFactory`, with session logs visible in both frontends. The TUI has multiline input, markdown/code
> rendering, token/context/cost status, `/model`, transient retry, and `Esc` cancellation. CLI parsing is strict,
> tool gates are discoverable, and CI packages and smoke-tests the shaded jar through Maven Wrapper 3.9.11. ACP
> supports persisted create/load/list, streamed text/tool/log updates, target-safe cancellation, and per-session
> single-flight turns with cwd-bound tools/context and provider-owned Hosted state. Remaining cross-cutting gaps
> Deferred work was promoted to [`iterations/index.md`](iterations/index.md) or [`future.md`](future.md); source and
> tests remain implementation truth._

## Baseline (pre-roadmap scaffold)

- [x] Lanterna TUI scaffold — transcript + status bar + composer, key handling, echo `ConversationController`
- [x] Maven build (Kotlin 2.4.0 / JVM 25); bare `mvn` runs the app; shaded jar on `package`
- [x] `docs/` specification set (architecture, providers, hosted-agents, agent-context, tools, sessions, compaction, tui, configuration, roadmap)

## M0 — Dependencies & provider seam

- [x] Add `pom.xml` deps: `azure-ai-agents` (2.2.0), `azure-ai-projects` (2.2.0), `azure-identity`, `kotlinx-coroutines-core`, a JSON lib
- [x] `core/` domain model: `Entry` hierarchy, `Session`, `ToolCall`/`ToolResult`, `Usage`, `AgentContext`, `ToolSpec`
- [x] `provider/` seam: `AgentProvider`, `AgentEvent`, `TurnRequest`, `ToolExecutor`, `AgentKind`
- [x] `inference/` vendor seam: `InferenceClient` + `InferenceRequest`/`InferenceResponse`/`InferenceChunk` (neutral types; the single SDK chokepoint — see [providers.md](spec/providers.md#two-axes-two-seams))
- [x] `config/`: load `Configuration` from env + settings (`Configuration.load`; project/global `settings.json` precedence; compaction deferred to M4)
- [x] Build the Prompt **Responses** client from a signed-in identity (blocking `buildOpenAIClient()` — see the M1 notes for why the async wrapper was dropped) inside `AzureInferenceClient` (the AI-SDK chokepoint); hosted `allowPreview(true)` client deferred to M5
- [x] **Acceptance:** `mvn` compiles; a construction smoke test builds `AzureInferenceClient` from a `Configuration` (offline, deterministic), and the live `FOUNDRY_PROJECT_ENDPOINT` + `az login` path was verified end-to-end (Responses returned HTTP 200)

## M1 — Prompt: single-turn inference in the TUI

- [x] `PromptProvider.runTurn` (**streaming**, no tools): drive the loop over `InferenceClient.respondStreaming(...)`, emit `TextDelta`/`UsageReported`/`TurnCompleted` (vendor-neutral; SDK mapping in `AzureInferenceClient`)
  - streaming M1 path relays each `InferenceChunk.TextDelta` as `AgentEvent.TextDelta`, then `UsageReported` + `TurnCompleted` from the terminal `InferenceChunk.Completed`; failures surface as `AgentEvent.Failed` via the flow `catch` operator (exception-transparent, doesn't swallow cancellation). Streaming was pulled forward from M6 for a responsive UI (reasoning models emit many tokens)
  - `AzureInferenceClient` **owns** the blocking openai client (`buildOpenAIClient()`) rather than the Azure `ResponsesAsyncClient` wrapper: the wrapper discards the closeable client, and its executor could never be released. `respond`/`respondStreaming` call `client.responses().create/createStreaming`; `respondStreaming` is a plain `flow { emit }` iterating the `AutoCloseable` `StreamResponse`, moved onto `Dispatchers.IO`. Maps `InferenceRequest` → `ResponseCreateParams` and back (output-message text — openai-java 4.14.0 has no `Response.outputText()` — plus `ResponseUsage` tokens). **M2.5 note:** the persisted-agent binding must now attach `agent_reference` to the request, not via the wrapper-only `AzureCreateResponseOptions`
  - `close()` disposes the owned client; the blocking client's threads are daemon (verified: only `main` remains non-daemon after a streamed call + close), so the process exits promptly and the `exitProcess` guard in `Main` is belt-and-suspenders
  - no Reactor→coroutines bridge is needed: `AzureInferenceClient` wraps the blocking client with `Dispatchers.IO` + a plain `flow`. (An interim `kotlinx-coroutines-reactor` dep for an `awaitSingle()` bridge was added, then removed once the client switched to blocking — it had to exclude its pinned `reactor-core:3.4.1` so azure's `3.7.x` won, else `MonoSink.contextView` `NoSuchMethodError`.)
- [x] `agent/AgentLoop`; replace the `ConversationController` echo with a call into it
  - added `agent/AgentContextFactory` (base coding-agent prompt + env header + `systemPromptOverride`/`Append`) and `agent/NoToolExecutor` (M1 advertises no tools)
  - `ConversationController(state, agentLoop)` runs the turn (`runBlocking`) and folds `AgentEvent`s into `AppState`, accumulating `TextDelta`s into a single live assistant message that renders token-by-token; `Main` builds the object graph and closes the loop on exit
- [x] Render assistant text (streamed) + status-bar tokens (`StatusBar` shows model · `N tokens (in/out)` + a working indicator; `AppState` gained `lastUsage`/`isAwaitingResponse`/`modelName`)
- [x] Runtime polish: `.env` overlay so `mvn`/`java -jar` pick up `FOUNDRY_*` from a gitignored file; `simplelogger.properties` (WARN default) silences azure-identity per-request INFO that corrupted the TUI; `Main` disposes the client + `exitProcess` guard for a prompt shutdown
- [x] **Acceptance:** typing a prompt streams a real model answer into the transcript; the status bar shows token usage — **verified live** (Foundry `gpt-5`, streamed token-by-token over both the TUI stack and `java -jar … acp`) and offline via a fake `InferenceClient` (`PromptProviderTest`, `AgentLoopTest`, `ConversationControllerTest`)

## M2 — Prompt: function-tool loop + tools

- [x] `tool/ToolRegistry` + tools: `read`, `ls`, `find`, `grep`, `bash`, `write`, `edit` (output truncation + cwd containment)
  - `tool/` package: `Tool` interface + `ToolContext(cwd)`; `ToolRegistry(tools, allow?)` (allow-list drives both `enabled()` — advertised — and `get()` — resolvable, so read-only mode hides mutating tools both ways); `RegistryToolExecutor` implements the provider `ToolExecutor` seam (resolve enabled tool → parse args → run → convert unexpected failures to error results, rethrowing `CancellationException` → truncate). `ToolSupport.kt` has cwd containment (`resolveInCwd` rejects `..`/absolute escapes), UTF-8 byte truncation (`MAX_TOOL_OUTPUT_BYTES = 16 KB` + `[output truncated: …]` marker), and JSON-schema helpers. `BuiltinTools` = the 7-tool set + default registry factory.
  - each tool parses a `@Serializable` args payload via a shared lenient `Json`; `read` numbers lines + refuses binary/NUL; `find`/`grep` use `PathMatcher` globs relative to cwd (**Java NIO glob note:** `**/*.kt` requires ≥1 dir — it maps to `.*/[^/]*\.kt`; use `*.kt` or drop the leading `**/` for top-level files); `edit` refuses missing/non-unique matches (literal replace); `bash` runs the platform shell (`cmd.exe /c` on Windows, `sh -c` elsewhere) with a wall-clock timeout + a daemon output pump.
- [x] Harness-owned tool loop in `PromptProvider` (already written in M1's scaffold; now exercised): detect `functionCall` → `ToolExecutor` → append `ToolCallEntry`/`ToolResultEntry` → re-request. SDK mapping added in `AzureInferenceClient` (the sole chokepoint): `buildParams` declares `FunctionTool`s (`ToolSpec` → `FunctionTool.builder().parameters(Parameters).strict(false)`, **strict=false** because built-in tools have optional params absent from `required`, which strict mode forbids); `serializeHistory` maps `ToolCallEntry` → `ofFunctionCall` and `ToolResultEntry` → `ofFunctionCallOutput` (matched by `callId`).
  - wiring: `AgentContextFactory.build(…, tools)` populates `AgentContext.tools`; `Main` builds one `ToolRegistry` (honoring `Configuration.toolAllow`) → derives advertised specs + a cwd-scoped `RegistryToolExecutor`, threaded into both the TUI `AgentLoop` and the ACP frontend (`runAcpAgent(provider, context, toolExecutor)`). `NoToolExecutor` retained for no-tool contexts/tests.
- [x] Render `ToolCallStarted`/`ToolCallCompleted` — `ConversationController` renders tool summaries as system lines,
  and ACP maps the same events to `tool_call` / `tool_call_update`.
- [x] **Acceptance:** "read X and fix Y" performs real file reads/edits; a read-only run refuses mutations
  - **Verified live** against Foundry (`gpt-5`): a single turn drove `read` → `edit` (`foo`→`bar`) → `read` (verify) → final answer, editing the real file on disk — validating the full function-tool wire format (declarations, streamed `completed`-event tool-call parsing, and `function_call`/`function_call_output` round-trip the model consumed across turns).
  - Offline: 24 new unit tests — per-tool happy/error/containment (`ReadToolTest`, `WriteEditToolTest`, `LsFindGrepToolTest`), `RegistryToolExecutorTest` (read-only refusal leaves the file untouched, unknown-tool refusal, cwd-escape containment, output cap + callId preservation), a `PromptProviderTest` tool round-trip (asserts the re-request carries the reconstructed tool history), and a `ConversationControllerTest` rendering check. Full suite: 56 tests green.
  - CLI tool gates now expose that allow-list without editing settings: `--tools` selects exactly, `--exclude-tools`
    subtracts from settings/defaults, and `--no-tools` disables all client-side tools.

## M2.5 — Prompt: persisted agents (PromptAgent) — opt-in (branch off M2)

- [x] Config: resolve optional `KONDUCTOR_PROMPT_AGENT_NAME` / `provider.promptAgentName` (`Configuration.promptAgentName`); empty ⇒ ephemeral
- [x] Two sibling inference clients share the pure Responses mapping (`ResponsesMapping.kt`): the **ephemeral**
  `AzureInferenceClient` (`buildOpenAIClient()`, full model+instructions+tools+transcript payload) and the
  agent-scoped `AzurePromptAgentInferenceClient` (`allowPreview(true).buildAgentScopedOpenAIClient(name)`,
  **input-only** payload — the agent supplies model/instructions/tools). Two impls, not one branch, because the
  payloads genuinely differ; neither mutates for binding
- [x] Agent **lifecycle** is a standalone client (`PromptAgentClient` + `AzurePromptAgentClient`, mirroring
  `HostedAgentClient`): `listAgents` + `createAgentVersion(name, model, instructions, tools)` (tools baked via
  `BinaryData.fromObject` — structured JSON, not the `fromString` escaped-string footgun)
- [x] **Hot-swap** the bound agent mid-session via a `SwappableInferenceClient` decorator (rebuilds the delegate —
  `AzurePromptAgentInferenceClient` when bound, else `AzureInferenceClient` — never mutates it) behind a
  `PromptAgentBinder` seam exposed by `PromptProvider.agentBinder`
- [x] `/agent` TUI command: `` / `list` / `use <name>` / `create [name]` — switching via the binder, create/list via
  the lifecycle client (`PromptAgentCommand`, wired through `ConversationController`/`TuiApp`/`Main`; status-bar agent)
- [x] Session: persist the bound agent (`Session.promptAgentName` + `SessionCodec` header + a generic
  `SessionStore.persistHeader`); a fresh/`​/new` session adopts & records the bound agent, and `/resume` + startup
  restore it **from the TUI layer** (`PromptAgentCommand.onFreshSession`/`onResumedSession`), so `AgentLoop` stays
  agent-agnostic. **Volatility-safe:** a resumed session validates the saved agent via `listAgents` and falls back to
  ephemeral + a notice if it was deleted server-side (no data loss — the transcript holds the context)
- [x] cwd-currency: the agent bakes only the **stable** base prompt (`AgentContext.baseSystemPrompt`) and can't take
  request `instructions`, so the per-turn **dynamic preamble** (`AgentContext.dynamicPreamble`: env header + context
  files) rides `input` as a leading `developer` item. The split is **additive** (`systemPrompt` stays the ephemeral
  primary), so M3's call sites are untouched
- [x] **Acceptance:** **offline-tested** (two-client construction; standalone lifecycle; the `/agent` handler +
  session adopt/restore/volatility-fallback over binder + lifecycle fakes; blank-agent-name settings normalization —
  135 tests green) **and live-verified end-to-end** (2026-07-08): create a `PromptAgentDefinition` version + agent-scoped invoke with the dynamic-preamble
  item returns `"pong"` (562 tokens). The agent-bound invoke needs `allowPreview(true)` + an **input-only** payload
  ([service_feedback/prompt_agents.md](service_feedback/prompt_agents.md) #5), and `developer`/`system` input items
  need an explicit `type: "message"` to dodge a service mis-default (#6 — isolated via a live probe matrix)
  (Foundry + `az login`: `KONDUCTOR_PROMPT_AGENT_NAME` turn, `/agent create`→switch). Session persistence and resume
  restoration are implemented through `Session.promptAgentName`.

## M3 — Prompt: sessions

- [x] `session/SessionStore`: `NoOpSessionStore` (`--no-session`/tests) + `JsonlSessionStore` (append-only JSONL under `~/.konductor/sessions/<cwd-hash>/<id>.jsonl`) with `create`/`append`/`load`/`listForCwd`/`mostRecentForCwd`/`rename`/`locate`
  - `session/SessionCodec` serializes each entry line via the domain models' generated `@Serializable` polymorphic serializer (`type` discriminator through `classDiscriminator`, `@SerialName` per subtype; `kotlin.uuid.Uuid`/`kotlin.time.Instant` use kotlinx-serialization's built-in serializers — **bumped to 1.11.0**), with a small local header DTO holding the **absolute-normalized** `cwd` string so `Session` itself stays annotation-free. `Session.cwd` retyped `kotlinx.io` → `java.nio.file.Path` and gained `createdAt` (repo-health #4b)
- [x] Append entries as they are produced; implement `buildInput` reconstruction
  - `AgentLoop` is session-aware: every produced `Entry` is written to the injected `SessionStore`.
    `SessionHistory.reconstructHistory` slices the latest compaction summary + kept entries, and Responses mapping
    sends that summary as a leading developer item.
- [x] Wire `/new`, `/resume`, `/name`, `/session`; `--continue`/`--resume`
  - `ConversationController` dispatches the session slash-commands locally (never reaching the model): `/new`, `/name <label>`, `/session` (id · entries · tokens · file), `/resume` (lists) + `/resume <number|id>` (loads and repopulates the transcript via the shared `sessionEntriesToMessages` mapper). `TuiApp` seeds the transcript + status-bar tokens from a resumed session. `Main` parses `--no-session`, `--continue`/`-c`, `--resume <id>`/`-r`, `--name <n>` and builds the store + initial session for the TUI path
- [x] **Acceptance:** a session survives restart and can be resumed with full history; `--no-session` stays in memory
  - Offline: 33 new tests — codec round-trips (every entry type) + schema-version rejection, `JsonlSessionStore` (persist/reload across fresh store instances, `listForCwd` ordering + cwd isolation, rename, unknown-id), `reconstructHistory` (identity + compaction slice + malformed-`firstKeptEntryId` clamp), `AgentLoop` persistence/`newSession`/`resume`/`rename`/persist-failure/parentId-chaining, and `ConversationController` command behavior. Full suite: **112 tests green** on JDK 25
  - Deferred (flagged): an interactive/modal resume picker (M3 uses a listed `/resume <index|id>`); ACP history
    replay-on-load (functional load/resume is implemented).
  - Post-review hardening (code-review agent + Copilot PR #9): persistence I/O in `AgentLoop.runTurn` is guarded by `.catch` → recoverable `Failed` event (a disk error fails the turn, not the app); `--no-session` + `--resume`/`--continue` now fails fast; persisted `AssistantEntry.parentId` is re-stamped to the real last entry after tool turns; `reconstructHistory` clamps a malformed `firstKeptEntryId`; `decodeHeader` validates the schema `version`

## M4 — Prompt: compaction

- [x] `compaction/Compactor` + `ContextWindowTracker` fed by `UsageReported`
  - `compaction/` package: `CompactionSettings` (enabled/reserveTokens/keepRecentTokens/contextWindow), `TokenEstimator`
    (chars/4 estimate + flat `[Role]:` span serialization with tool-result truncation to ~2 KB), `ContextWindowTracker`
    (holds the latest authoritative `Usage.totalTokens`; `shouldCompact()` = enabled && total > contextWindow−reserve),
    and `Compactor` (cut planning + summarization via a **no-tools** one-shot reuse of the loop's `AgentProvider`).
  - `AgentLoop` owns a `ContextWindowTracker` (updated on every `AgentEvent.UsageReported`) + a `Compactor` built from
    its provider; both default to **disabled** so ACP + existing tests are unchanged (the TUI passes `Configuration.compaction`).
- [x] Trigger at `contextTokens > contextWindow - reserveTokens`; write a `CompactionEntry`; rebuild input from summary + kept entries; handle split turns
  - Auto-trigger runs in `AgentLoop.runTurn` after the `UserEntry` is recorded and before the provider turn. The
    `CompactionEntry` is **inserted at its `firstKeptEntryId` position** (layout `[summarized…, marker, kept…]`, matching
    the M3 `reconstructHistory` contract) and the session is **rewritten** (new `SessionStore.rewrite`, JSONL full-file
    write like `rename`) so the on-disk order survives a reload. `serializeHistory` now maps a `CompactionEntry` to a
    leading `developer` summary message (was the M4 TODO `error(...)`).
  - Cut-point rules: walk back summing token estimates to `keepRecentTokens`, round to a **turn boundary** (user message);
    cut points restricted to user/assistant messages so a tool call and its result are never split; a single oversized
    turn ("split turn") falls back to an assistant-message cut. Iterative compaction re-summarizes from the previous
    marker's `firstKeptEntryId` and folds the previous summary into the new one.
- [x] Wire `/compact [instructions]` + settings
  - `ConversationController` dispatches `/compact [instructions]` locally → `AgentLoop.compact(instructions)` (works even
    when auto-compaction is disabled), shows the working indicator, resets the token readout so context % drops, and
    renders a `🗜 Compacted…` system line; `AgentEvent.Compacted` renders the same on the auto path. `Configuration` +
    `settings.json` parse the `compaction` block (`compaction.contextWindow` added as a knob — no reliable SDK call for
    the window; conservative default 128000).
- [x] **Acceptance:** a long session auto-compacts (a compaction entry appears, context % drops) and keeps answering coherently; `/compact` works on demand
  - Offline: 22 new tests — `TokenEstimatorTest` (estimate + serialize + truncation), `ContextWindowTrackerTest`
    (threshold/reset/disabled), `CompactorTest` (turn-boundary cut, tool call/result never split, split-turn assistant
    cut, iterative previous-summary fold, nothing-to-compact), `AgentLoopCompactionTest` (auto-trigger over threshold,
    disabled = no-op, manual `/compact` insert-marker + reload round-trip), `ResponsesMappingTest` (compaction → input
    item), `SessionCommandsTest` (`/compact`), `ConfigurationTest` (compaction parsing). Full suite: **157 tests green** on JDK 25.
  - Live verification against Foundry is pending (offline-complete); the summarization path reuses the M1 streamed
    inference stack, exercised end-to-end by the fakes.
  - Post-review hardening (Copilot PR #11 + code-review agent): `newSession()`/`resume()` now `reset()` the
    `ContextWindowTracker` so a switched/resumed session can't compact off a stale cross-session token count; the
    split-turn cut adds an upward fallback so a genuinely oversized **single** turn (whose only assistant is the
    trailing entry) is cut at that assistant instead of being left un-compactable — gated by a "region exceeds
    keepRecentTokens" check so short transcripts still no-op; and compaction is now best-effort — the no-tools
    summarizer returns a benign result instead of throwing (a bound PromptAgent may emit a stray tool call), and a
    summarization failure is caught in `AgentLoop` so it can never fail the user's turn. The headless **ACP**
    frontend also receives `Configuration.compaction` and compacts its persisted JSONL sessions.

## M5 — Hosted provider (parallel track after M0)

- [x] `provider/hosted/HostedProvider`: select/deploy an agent version, configure the endpoint, create/reuse a session
  - added a fakeable `HostedAgentClient` seam plus `AzureHostedAgentClient` as the hosted SDK chokepoint
  - selects the latest active version or creates a hosted version from `FOUNDRY_AGENT_CONTAINER_IMAGE`, then updates the endpoint to Responses with a 100% fixed-ratio selector
- [x] Invoke via `buildAgentScopedOpenAIClient` + `agent_session_id`; emit `TextDelta`/`TurnCompleted`
  - `--agent-kind hosted` is wired through `ProviderFactory`; hosted config resolves `KONDUCTOR_HOSTED_AGENT_NAME` and `FOUNDRY_AGENT_CONTAINER_IMAGE`
- [x] Stream session logs → `LogFrame`; optional session-file upload/download
  - session-file upload/download remains optional and is not implemented in this pass
- [x] Lifecycle cleanup (`stopSession`/`deleteSession`)
- [x] **Acceptance (core):** with `--agent-kind hosted`, a prompt runs inside the container and the session is cleaned up on exit — **verified live** 2026-07-08 against the `foundry-sdk-deployment`/`java` `responses-echo-agent` container (echo response received; version create → poll-to-ACTIVE, then reused across runs; delete-only cleanup)
  - offline unit tests (fakes) cover version/session setup, invocation, log relays, session reuse, and cleanup
  - the live run surfaced + fixed a `close()` bug (concurrent `stopSession`+`deleteSession` → `409`; now delete-only, best-effort) and confirmed the version-active polling + `enableVnextExperience`/`protocolVersions` requirements — captured in [service_feedback/hosted_agents.md](service_feedback/hosted_agents.md)
  - `LogFrame` renders in the TUI (`ConversationController` → `📋` system lines) and now also streams over ACP as log-prefixed `agent_message_chunk`s (`📋 …`), so hosted container logs reach an ACP client too (with a test)

## M6 — Streaming & polish

- [x] Switch inference to streaming (`AzureInferenceClient.respondStreaming` → `client.responses().createStreaming`; `outputTextDelta` → `InferenceChunk.TextDelta`, terminal event → `InferenceChunk.Completed`) — **pulled forward into M1** for responsiveness. The M2 tool loop reads tool calls from the terminal `Completed` event's assembled `response.output()` (function-call items), so per-delta `functionCallArgumentsDelta` accumulation was **not needed** and is not implemented.
- [x] Unify the status bar (tokens / context % / cost)
  - `StatusBar` now shows input/output/total tokens, context percentage from `compaction.contextWindow`, and a
    best-effort cost estimate from a small model-pricing map; unknown/custom deployments render `cost n/a` instead
    of guessing.
- [x] Non-blocking input during streaming; `Esc` cancellation — the TUI event loop now polls input non-blockingly and repaints on a tick while the turn runs on a background `CoroutineScope` (its `Job` folds `AppState` under a render lock). Rendering is gated on a `dirty` flag (set on keypress, resize, or a turn update), so an idle prompt no longer re-wraps the whole transcript every tick. `Esc` cancels the `Job` (CancellationException propagates through the exception-transparent flows and stops inference), ending the turn with a `⏹ Turn cancelled.` line. `ConversationController.submitAsync` returns the cancelable `Job`; the synchronous `submit()` stays for tests. **Needs a manual terminal smoke test** (the concurrency isn't unit-testable; `submitAsync` classification + a gated-inference cancel are covered offline).
- [x] `/model` switching
  - `/model <name>` updates the `AgentLoop` context, status bar, and session header for subsequent turns. Provider
    kind switching is deferred because it requires rebuilding the provider stack and lifecycle/binder wiring.
- [x] Error/retry polish
  - `AzureInferenceClient` retries transient HTTP 429/5xx and timeout/retryable failures with capped exponential
    backoff; streaming retries surface a brief system status line before retrying.
- [ ] **Acceptance:** assistant text streams token-by-token ✅ (done in M1); model switching works mid-session ✅; a turn is cancelable ✅ (`Esc`, needs manual terminal smoke test); `--agent-kind` provider switching mid-session is deferred

## ACP track — headless agent mode (co-equal with M6; see [acp.md](spec/acp.md))

Running Konductor headless as a spec-compliant **ACP agent** over stdin/stdout — driven by a client (Zed,
another tool, or another Konductor) — is a **primary goal** of the project, not an afterthought; this track is
**co-equal with the M6 polish**, sitting outside the M0–M6 numbering only because it's a *frontend*, not a
Prompt/Hosted milestone. Roles split two ways ([acp.md](spec/acp.md#two-roles-agent-vs-client)): the **agent
role** (Konductor is driven) is Phases A–C — A/B done and Phase C mostly done (load/list, tool/log visibility,
cancellation; permissions and richer usage/compaction updates remain); the **client role** (Konductor drives *another* agent — agent
orchestration / sub-agents) is Phase D, deferred with scope in
[future.md](future.md#agent-orchestration). Phase A is independent of the roadmap; Phases B/C ride on
M1/M2/M3 (now done).

### Phase A — Transport & headless entry (echo bridge)
- [x] `acp-jvm` (0.24.0) dependency added; Kotlin bumped to 2.2.20; SLF4J stderr backend wired
- [x] Headless entry: `Main` runs the ACP agent on `acp`/`--acp` instead of the TUI
- [x] Agent baseline via the SDK: `initialize`, `session/new`, `session/prompt`, `session/cancel`
- [x] Echo bridge: `session/prompt` streams an `agent_message_chunk` + `end_turn`
- [x] Validated end-to-end over raw JSON-RPC; unit tests for the prompt→event mapping
- [ ] Automated in-process client↔agent (golden-transcript) integration test

### Phase B — Wire to the real AgentLoop (depends on M1)
- [x] Replace the echo bridge with `AgentLoop`/`AgentProvider` (`KonductorAgentSession` runs a real turn; one `AgentLoop` per `session/new`)
- [x] Map `AgentEvent` → `session/update`: assistant **text streams** as per-delta `agent_message_chunk`s (fallback to the full `TurnCompleted` text only if nothing streamed), completion → `end_turn` (M1 scope; `tool_call`/plan/`usage` ride on M2+). Verified end-to-end: `java -jar … acp` over JSON-RPC streamed real model output token-by-token
- [x] `session/cancel` → cancel the turn `Job` — ACP runs the turn as a cancelable `channelFlow` job; `cancel()` cancels it (CancellationException propagates through the exception-transparent AgentLoop/PromptProvider flows) and the turn ends with `StopReason.CANCELLED`
- [x] Per-session single-flight + deterministic overlap: `AgentLoop` rejects concurrent collections before they can mutate shared history; ACP rejects (does not queue) a second prompt, and keeps the active job registered through cancellation unwind so `session/cancel` cannot target the wrong prompt. Covered by direct-loop and overlapping ACP tests.

### Phase C — Sessions, tools, permissions — core agent-role compliance (depends on M2/M3)
- [x] `session/load` + `session/list` ↔ `SessionStore` — the ACP frontend now persists via `JsonlSessionStore` keyed by the client-provided `SessionCreationParameters.cwd`; `listSessions`→`SessionInfo`, `loadSession`→resumed `AgentLoop`, `AgentCapabilities(loadSession=true)` advertised. The Konductor session UUID is the ACP `SessionId` (1:1). _History replay-on-load (re-emitting past turns as `session/update`s) is a follow-up; functional resume works._
- [x] `tool_call` updates — `AgentEvent.ToolCallStarted`/`Completed` → `SessionUpdate.ToolCall` (IN_PROGRESS) / `ToolCallUpdate` (COMPLETED/FAILED + output content), `ToolKind` mapped from the tool name (title is a stub pending the `tool/ToolRendering` swap at consolidation)
- [ ] Replay persisted transcript entries as `session/update`s during `session/load`
- [ ] Map usage/context and compaction events to ACP updates, with a stable protocol shape
- [ ] `session/request_permission` for mutating tools — **deferred** (permissions is its own topic: approval UX, policy, grant persistence)
- [ ] *(optional)* delegate `fs/*` and `terminal/*` to the client

### Phase D — Deferred: ACP client role (agent orchestration)
- [ ] Konductor as an ACP client driving another agent (instance-to-instance / sub-agents) — scope detailed in [future.md](future.md#agent-orchestration)

## Historical ad-hoc / added work

_Items completed or identified outside the original roadmap. This section is frozen._

- [x] Reorganized `docs/`: procedural docs (setup, roadmap, burndown, future) at top under `index.md`; design specs moved to `docs/spec/`
- [x] Distribution: self-contained `jpackage` bundles via a Maven `dist` profile + tag-triggered GitHub Actions release (`.deb` / `.dmg` / zipped Windows app-image); docs in [distribution.md](distribution.md)
- [x] Spec: added the `InferenceClient` vendor seam beneath `PromptProvider` — separates the loop-ownership axis (`AgentProvider`) from the vendor axis, confines all SDK types to one class, and makes the Prompt loop unit-testable ([architecture.md](spec/architecture.md#two-axes-two-seams), [providers.md](spec/providers.md))
- [x] Docs LLM-usability pass: `index.md` gained a "Finding things fast" nav section; fixed an orphan (`distribution.md` was missing from the map) and a stale toolchain line (Kotlin/JVM); sharpened the `AGENTS.md` nav pointer; added a repo-local `docs-nav` Copilot CLI skill (`.github/skills/`, a thin pointer to `docs/index.md`)
- [x] Shaded-jar fix (M1): strip signed dependencies' `META-INF/*.SF/*.RSA/*.DSA/*.EC` + merge `META-INF/services` in the shade plugin, so `java -jar …` (and the jpackage distribution) load — Azure SDK jars are signed and otherwise fail with `SecurityException: Invalid signature file digest`
- [x] Harness drift analysis refreshed against pi 0.80.3 source/docs resolved through `gcm pi`; the old report's
  main gaps (sessions, compaction, TUI cancellation, PromptAgents, ACP load/list/tools) are implemented. The
  standalone report was retired so durable findings could move to canonical specs, backlog/iterations, and focused
  issues instead of becoming another status source.
  - `.github/skills/harness-drift-analysis/SKILL.md` resolves the installed pi package, records its version, reads
    source/docs directly, and persists findings into canonical homes instead of a standalone report.
- [x] Documentation truth sync after M3/M4/M2.5/M5/M6/ACP Phase C: refreshed `AGENTS.md`, `README.md`, index/status,
  provider/PromptAgent request shapes, session JSONL schema, compaction layout, ACP mapping, TUI status, roadmap,
  hero scenarios, and stale source comments. Item-level status remains centralized here.
- [x] Issue #6 runtime/session hardening: [PR #17](https://github.com/jpalvarezl/Konductor/pull/17) rejects
  overlapping per-session turns, makes ACP cancellation target-safe, tests partial failure/cancel persistence, and
  adds stable JSONL goldens; merged.
- [ ] Persist a structured failed/aborted turn entry when resume/audit fidelity warrants a JSONL schema change;
  current behavior (keep user/completed tools, omit partial assistant text) is tested and remains authoritative
- [x] Issue #6 CLI/repo-health hardening: [PR #18](https://github.com/jpalvarezl/Konductor/pull/18) adds config-free
  help/version, strict argument validation, CLI tool gates, Maven Wrapper 3.9.11, package CI, and shaded-jar smoke.
  Merged.
- [x] ACP workspace/provider ownership ([issue #16](https://github.com/jpalvarezl/Konductor/issues/16)): each
  created/loaded ACP session now owns its provider, cwd-bound prompt context and tool executor; loads rebuild from the
  persisted cwd; invalid workspaces fail before runtime creation; Hosted sessions have independent server-session
  state and do not run the Prompt-only client compactor. Multi-cwd and multi-Hosted-session isolation are tested.

---

Current delivery: [iterations](iterations/index.md) · Backlog: [future.md](future.md) ·
Related: [implementation-roadmap.md](implementation-roadmap.md) · [architecture.md](spec/architecture.md) · [index.md](index.md)
