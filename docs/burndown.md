# Burndown

Single source of truth for **where Konductor stands work-wise** — so you don't have to re-derive
progress from the codebase each session. It covers the milestones in
[implementation-roadmap.md](implementation-roadmap.md); agents and developers are free to add items
and sub-items as the work demands.

**Keep this current.** When you start or finish a piece of work, tick the box (`- [x]`) and adjust the
sub-items. Agents must update it as part of the same change (see [../AGENTS.md](../AGENTS.md)); human
developers should update it by hand. Work that isn't in the roadmap goes under
[Ad-hoc / added work](#ad-hoc--added-work).

Legend: `- [ ]` not started / in progress · `- [x]` done.

> _Last updated: 2026-07-07 — status: **M1 complete** on the core roadmap — the Prompt path now does real
> single-turn inference in the TUI: `PromptProvider.runTurn` drives the vendor-neutral loop over
> `AzureInferenceClient.respondStreaming` (Responses `client.responses().createStreaming` → neutral `InferenceChunk`/`InferenceResponse`), `agent/AgentLoop`
> owns the in-memory transcript, `ConversationController` replaced its echo with a real turn, and the status bar
> shows model + token usage. **Verified live** against Foundry (`gpt-5` → real answer + usage) and offline via a
> fake `InferenceClient` across the full stack. Tools/sessions/compaction remain M2–M4. Assistant text now
> **streams token-by-token** (streaming pulled forward from M6), and `AzureInferenceClient` owns the blocking
> openai client so `close()` disposes it — its threads are daemon, so shutdown is prompt (the `exitProcess`
> guard in `Main` is belt-and-suspenders). The **ACP track** has
> landed **Phase B** — the headless `acp` frontend runs the same real streamed inference as the TUI
> (verified end-to-end: `java -jar … acp` over JSON-RPC → streamed model output). A `jpackage`-based multi-OS
> release pipeline is in as ad-hoc work (its shaded jar also needed the M1 signed-jar fix to launch). The
> provider spec defines an `InferenceClient` vendor seam beneath `PromptProvider`
> (SDK confined to one class); opt-in **M2.5** specs **persisted PromptAgents** — see
> [roadmap](implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in)._

## Baseline (pre-roadmap scaffold)

- [x] Lanterna TUI scaffold — transcript + status bar + composer, key handling, echo `ConversationController`
- [x] Maven build (Kotlin 2.0.21 / JVM 21); bare `mvn` runs the app; shaded jar on `package`
- [x] `docs/` specification set (architecture, providers, hosted-agents, agent-context, tools, sessions, compaction, tui, configuration, roadmap)

## M0 — Dependencies & provider seam

- [x] Add `pom.xml` deps: `azure-ai-agents` (2.2.0), `azure-ai-projects` (2.2.0), `azure-identity`, `kotlinx-coroutines-core`, a JSON lib
- [x] `core/` domain model: `Entry` hierarchy, `Session`, `ToolCall`/`ToolResult`, `Usage`, `AgentContext`, `ToolSpec`
- [x] `provider/` seam: `AgentProvider`, `AgentEvent`, `TurnRequest`, `ToolExecutor`, `AgentKind`
- [x] `inference/` vendor seam: `InferenceClient` + `InferenceRequest`/`InferenceResponse`/`InferenceChunk` (neutral types; the single SDK chokepoint — see [providers.md](spec/providers.md#two-axes-two-seams))
- [x] `config/`: load `Configuration` from env + settings (`Configuration.load`; project/global `settings.json` precedence; compaction deferred to M4)
- [x] Build the Prompt **Responses** async client from a signed-in identity (`buildResponsesAsyncClient()`) inside `AzureInferenceClient` (the only SDK-importing class); hosted `allowPreview(true)` client deferred to M5
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

- [ ] `tool/ToolRegistry` + tools: `read`, `ls`, `find`, `grep`, `bash`, `write`, `edit` (output truncation + cwd containment)
- [ ] Harness-owned tool loop in `PromptProvider` (detect `functionCall` → `ToolExecutor` → submit `ResponseFunctionToolCallOutputItem` → re-request)
- [ ] Render `ToolCallStarted`/`ToolCallCompleted`
- [ ] **Acceptance:** "read X and fix Y" performs real file reads/edits; a read-only run (`--tools read,ls,find,grep`) refuses mutations

## M2.5 — Prompt: persisted agents (PromptAgent) — opt-in (branch off M2)

- [ ] Config: resolve optional `KONDUCTOR_AGENT_NAME` / `provider.agentName` (`Configuration.agentName`); empty ⇒ ephemeral
- [ ] `AzureInferenceClient`: bind `AzureCreateResponseOptions.setAgentReference(...)` + omit request `instructions` when an agent is set (loop/`input`/tools unchanged)
- [ ] Keep the dynamic preamble (env header + context files) as a per-turn leading input item; bake only the stable base prompt + tool declarations into the agent
- [ ] Agent lifecycle: `createAgentVersion(name, PromptAgentDefinition(...))` (create from current context) + select existing by name
- [ ] `/agent` TUI command: `list` / `use <name>` / `create [name]` + status-bar active agent
- [ ] Session: persist `agentReference` (name+version) in the header; reuse on resume, warn on config mismatch (rides on M3)
- [ ] **Acceptance:** `KONDUCTOR_AGENT_NAME=<name>` runs a turn against the persisted agent; `/agent create` mints a version from the current context and switches; a resumed session reuses its agent; session/compaction unchanged

## M3 — Prompt: sessions

- [ ] `session/SessionStore`: `InMemorySessionStore` first, then JSONL persistence + `load`/`listForCwd`
- [ ] Append entries as they are produced; implement `buildInput` reconstruction
- [ ] Wire `/new`, `/resume`, `/name`, `/session`; `--continue`/`--resume`
- [ ] **Acceptance:** a session survives restart and can be resumed with full history; `--no-session` stays in memory

## M4 — Prompt: compaction

- [ ] `compaction/Compactor` + `ContextWindowTracker` fed by `UsageReported`
- [ ] Trigger at `contextTokens > contextWindow - reserveTokens`; write a `CompactionEntry`; rebuild input from summary + kept entries; handle split turns
- [ ] Wire `/compact [instructions]` + settings
- [ ] **Acceptance:** a long session auto-compacts (a compaction entry appears, context % drops) and keeps answering coherently; `/compact` works on demand

## M5 — Hosted provider (parallel track after M0)

- [ ] `provider/hosted/HostedProvider`: select/deploy an agent version, configure the endpoint, create/reuse a session
- [ ] Invoke via `buildAgentScopedOpenAIClient` + `agent_session_id`; emit `TextDelta`/`TurnCompleted`
- [ ] Stream session logs → `LogFrame`; optional session-file upload/download
- [ ] Lifecycle cleanup (`stopSession`/`deleteSession`)
- [ ] **Acceptance:** with `--agent-kind hosted`, a prompt runs inside the container, its logs stream into the TUI, and the session is cleaned up on exit

## M6 — Streaming & polish

- [x] Switch inference to streaming (`AzureInferenceClient.respondStreaming` → `client.responses().createStreaming`; `outputTextDelta` → `InferenceChunk.TextDelta`, terminal event → `InferenceChunk.Completed`) — **pulled forward into M1** for responsiveness; `functionCallArgumentsDelta` accumulation rides on the M2 tool loop
- [ ] Unify the status bar (tokens / context % / cost); non-blocking input during streaming; `Esc` cancellation (the turn still runs under `runBlocking` — input blocks until it finishes)
- [ ] `/model` and `--agent-kind` switching; error/retry polish
- [ ] **Acceptance:** assistant text streams token-by-token ✅ (done in M1); a turn is cancelable; switching model/provider works mid-session

## ACP track — headless mode (added; see [acp.md](spec/acp.md))

Not part of M0–M6. Runs Konductor headless as an ACP agent over stdin/stdout. Phase A is independent of
the roadmap; Phases B/C ride on M1/M2/M3.

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
- [ ] `session/cancel` → cancel the turn `Job` (currently relies on the SDK's default; explicit wiring deferred)

### Phase C — Sessions, tools, permissions (depends on M2/M3)
- [ ] `session/load` + `session/list`/`resume` ↔ `SessionStore`
- [ ] `tool_call` updates + `session/request_permission` for mutating tools
- [ ] *(optional)* delegate `fs/*` and `terminal/*` to the client

### Phase D — Deferred: ACP client role
- [ ] Konductor as an ACP client driving another agent (instance-to-instance / sub-agents)

## Ad-hoc / added work

_Items outside the roadmap — bugs, refactors, spikes, docs. Add sub-bullets as needed._

- [x] Reorganized `docs/`: procedural docs (setup, roadmap, burndown, future) at top under `index.md`; design specs moved to `docs/spec/`
- [x] Distribution: self-contained `jpackage` bundles via a Maven `dist` profile + tag-triggered GitHub Actions release (`.deb` / `.dmg` / zipped Windows app-image); docs in [distribution.md](distribution.md)
- [x] Spec: added the `InferenceClient` vendor seam beneath `PromptProvider` — separates the loop-ownership axis (`AgentProvider`) from the vendor axis, confines all SDK types to one class, and makes the Prompt loop unit-testable ([architecture.md](spec/architecture.md#two-axes-two-seams), [providers.md](spec/providers.md))
- [x] Docs LLM-usability pass: `index.md` gained a "Finding things fast" nav section; fixed an orphan (`distribution.md` was missing from the map) and a stale toolchain line (Kotlin/JVM); sharpened the `AGENTS.md` nav pointer; added a repo-local `docs-nav` Copilot CLI skill (`.github/skills/`, a thin pointer to `docs/index.md`)
- [x] Shaded-jar fix (M1): strip signed dependencies' `META-INF/*.SF/*.RSA/*.DSA/*.EC` + merge `META-INF/services` in the shade plugin, so `java -jar …` (and the jpackage distribution) load — Azure SDK jars are signed and otherwise fail with `SecurityException: Invalid signature file digest`

---

Related: [implementation-roadmap.md](implementation-roadmap.md) · [architecture.md](spec/architecture.md) · [index.md](index.md)
