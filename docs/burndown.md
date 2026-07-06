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

> _Last updated: 2026-07-06 — status: **pre-M0** on the core roadmap (`src/` is still the Lanterna TUI
> scaffold). The **ACP track** has landed Phase A: a headless ACP agent over stdio with an echo bridge. A
> `jpackage`-based multi-OS release pipeline has landed as ad-hoc work._

## Baseline (pre-roadmap scaffold)

- [x] Lanterna TUI scaffold — transcript + status bar + composer, key handling, echo `ConversationController`
- [x] Maven build (Kotlin 2.0.21 / JVM 21); bare `mvn` runs the app; shaded jar on `package`
- [x] `docs/` specification set (architecture, providers, hosted-agents, agent-context, tools, sessions, compaction, tui, configuration, roadmap)

## M0 — Dependencies & provider seam

- [X] Add `pom.xml` deps: `azure-ai-agents` (2.2.0), `azure-ai-projects` (2.2.0), `azure-identity`, `kotlinx-coroutines-core`, a JSON lib
- [x] `core/` domain model: `Entry` hierarchy, `Session`, `ToolCall`/`ToolResult`, `Usage`, `AgentContext`, `ToolSpec`
- [ ] `provider/` seam: `AgentProvider`, `AgentEvent`, `TurnRequest`, `ToolExecutor`, `AgentKind`
- [ ] `config/`: load `Config` from env + settings
- [ ] Build SDK clients from a signed-in identity (`buildResponsesClient()`; hosted `allowPreview(true)`)
- [ ] **Acceptance:** `mvn` compiles; a smoke test constructs clients from `FOUNDRY_PROJECT_ENDPOINT` + `az login` without runtime auth errors

## M1 — Prompt: single-turn inference in the TUI

- [ ] `PromptProvider.runTurn` (non-streaming, no tools): build `input` from history, `createAzureResponse`, emit `TextDelta`/`TurnCompleted`/`UsageReported`
- [ ] `agent/AgentLoop`; replace the `ConversationController` echo with a call into it
- [ ] Render assistant text + status-bar tokens
- [ ] **Acceptance:** typing a prompt returns a real model answer in the transcript; the status bar shows token usage

## M2 — Prompt: function-tool loop + tools

- [ ] `tool/ToolRegistry` + tools: `read`, `ls`, `find`, `grep`, `bash`, `write`, `edit` (output truncation + cwd containment)
- [ ] Harness-owned tool loop in `PromptProvider` (detect `functionCall` → `ToolExecutor` → submit `ResponseFunctionToolCallOutputItem` → re-request)
- [ ] Render `ToolCallStarted`/`ToolCallCompleted`
- [ ] **Acceptance:** "read X and fix Y" performs real file reads/edits; a read-only run (`--tools read,ls,find,grep`) refuses mutations

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

- [ ] Switch `PromptProvider` to `createStreamingAzureResponse` (`outputTextDelta`/`functionCallArgumentsDelta`)
- [ ] Unify the status bar (tokens / context % / cost); non-blocking input during streaming; `Esc` cancellation
- [ ] `/model` and `--agent-kind` switching; error/retry polish
- [ ] **Acceptance:** assistant text streams token-by-token; a turn is cancelable; switching model/provider works mid-session

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
- [ ] Replace the echo bridge with `AgentLoop`/`AgentProvider`
- [ ] Map `AgentEvent` → `session/update` (text, `tool_call`, plan, usage, stop reason)
- [ ] `session/cancel` → cancel the turn `Job`

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

---

Related: [implementation-roadmap.md](implementation-roadmap.md) · [architecture.md](spec/architecture.md) · [index.md](index.md)
