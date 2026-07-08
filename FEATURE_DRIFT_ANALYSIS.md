# Feature Drift Analysis

_Last reviewed: 2026-07-08_

This compares the current Konductor source tree against pi `@earendil-works/pi-coding-agent` 0.80.3 docs/source layout, with Konductor's `docs/burndown.md` used as the local progress baseline. Validation run: `mvn -q test` passed.

## TL;DR

Konductor is in good shape for a hackathon-sized Java/Kotlin dogfooding project. It already covers the smallest useful pi-like coding-agent loop: real streamed Foundry inference, a client-owned function-tool loop, cwd-scoped built-in tools, a simple TUI, and a headless ACP entrypoint.

It is **not close to pi's product surface yet**, but the missing areas are mostly expected: persistent sessions, compaction, slash commands, rich editor/TUI affordances, model switching, project trust, and pi-style extensions/packages/skills/themes. The important part is that the current seams (`AgentProvider`, `InferenceClient`, `ToolExecutor`/`ToolRegistry`, `Entry` models, shared `AgentLoop`) are pointing in the right direction.

Highest-priority drift to address next:

1. **Session persistence / history fidelity**: tool calls/results are used inside a turn, but `AgentLoop` only persists the user entry and final assistant entry between turns. Before M3, tool entries need to become first-class emitted/persisted history.
2. **Non-blocking turns + cancellation**: the TUI blocks in `runBlocking`; pi's queue/cancel UX depends on the agent running asynchronously.
3. **Command/config surface**: many values are modeled (`agentKind`, tools allow-list), but there is almost no CLI/slash-command control yet.
4. **Docs drift**: `docs/burndown.md` matches the code best; `README.md` and `docs/index.md` still contain older status language.

## Coverage matrix vs. pi

| Area | Pi baseline | Konductor now | Drift / next step |
|---|---|---|---|
| Core coding loop | Streaming model loop with tool use | **Mostly covered**: Prompt provider streams Foundry Responses; function-tool loop works | Good MVP parity for the core agent loop |
| Built-in tools | `read`, `write`, `edit`, `bash`, `grep`, `find`, `ls` | **Covered**: same 7 tools, cwd containment, truncation, allow-list support | Add per-call progress/streaming for long bash; add CLI flags for allow/exclude |
| Provider/model support | Many providers, OAuth/API keys, model selector, thinking levels | **Intentional narrow scope**: Foundry via Azure SDK + `DefaultAzureCredential`; model from env/settings | Fine for dogfooding; add `/model` and hosted/persisted-agent paths when ready |
| TUI | Rich terminal UI, markdown/code/diff rendering, collapsible tool output, footer with cost/context/cache, overlays | **Basic**: transcript, status bar, single-line composer, scroll, streamed assistant text, tool status lines | Improve after sessions: markdown/diff rendering, multi-line editor, overlays/commands, cancellation |
| Editor UX | file refs (`@`), path completion, multiline, external editor, images, `!`/`!!` bash | **Mostly absent** | Not required for MVP, but file refs/path completion are high-leverage |
| Slash commands | `/model`, `/settings`, `/resume`, `/new`, `/tree`, `/compact`, `/export`, etc. | **Only** `/quit` and `/exit` | Add a command router before feature-specific commands |
| Message queue/cancel | Steering/follow-up queues, abort, retry | **Absent** in TUI; ACP cancel stub exists but not wired to a turn job | Requires async `AgentLoop.submit(...): Job` shape |
| Sessions | Auto JSONL sessions, resume, names, tree branching, fork/clone | **Modeled but not implemented**: `Session`/`Entry` types exist; `AgentLoop` is in-memory | M3 is the biggest feature gap and should come next |
| Branching | In-file tree navigation and branch summaries | **Schema groundwork only** via `parentId` | Defer until after basic JSONL persistence |
| Compaction | Auto/manual compaction, overflow recovery, branch summarization | **Schema/spec only**; `AzureInferenceClient` errors if `CompactionEntry` enters history | M4; depends on complete session reconstruction |
| Context files | Loads global/project/ancestor `AGENTS.md`/`CLAUDE.md`, SYSTEM/APPEND | **Partial**: base prompt + cwd/os/date; settings override/append | Add context-file discovery soon; this is important for coding quality |
| Customization | TypeScript extensions, tools, commands, UI, event hooks, packages, skills, prompts, themes | **Internal seams only**; no runtime plugin surface | OK for hackathon. Keep interfaces clean so a plugin layer can be added later |
| Headless/integration | Print, JSON, RPC, SDK | **Different but promising**: ACP agent over stdio using same loop | ACP is strategically useful; still needs tool updates, sessions, cancellation |
| Security/trust | Project trust, resource loading gates, tool allow/exclude CLI, extension security model | **Partial**: cwd containment and settings allow-list; bash unrestricted when enabled | Add CLI tool controls and project trust before loading project-local resources/extensions |
| Distribution | npm package / binary-style distribution | **Covered differently**: shaded jar + jpackage profile + release workflow | Good for JVM app distribution |
| Tests | Broad product tests | **Healthy for current scope**: unit tests around loop, config, tools, TUI text/layout, ACP | Keep tests around session persistence and tool history reconstruction |

## What is already strong

### 1. The architecture is extensible in the right places

Konductor has a clean split that matches the intended pi-inspired growth path:

- TUI/ACP frontends do not call the SDK directly.
- `AgentLoop` owns conversation orchestration.
- `AgentProvider` abstracts the execution model (`Prompt` now, `Hosted` later).
- `InferenceClient` keeps Azure/OpenAI SDK types behind one chokepoint.
- `ToolRegistry` and `ToolExecutor` make built-in tools swappable/testable.
- The domain model already has `Entry`, `Session`, `ToolCallEntry`, `ToolResultEntry`, `CompactionEntry`, and `Usage`.

That is exactly the kind of scaffolding needed to grow toward pi without hard-coding Lanterna or Azure SDK details into every layer.

### 2. The MVP coding loop is real

The project is past a scaffold: the Prompt path streams model output, declares tools as function tools, executes local tools, re-submits tool outputs, and renders tool events in the TUI. The same provider/loop also powers ACP mode.

For a hackathon project, this is the most important feature milestone: users can ask it to inspect and edit files, and the implementation dogfoods the Azure SDK path.

### 3. Tooling is small but pragmatic

The built-in tool set matches pi's core tool names. The implementation includes cwd containment, UTF-8/binary checks for reads, literal exact edit replacement, output truncation, and read-only allow-list behavior. This is enough for useful local coding tasks.

## Important drift / risks

### 1. Tool history is not yet durable across turns

`PromptProvider` appends `ToolCallEntry` and `ToolResultEntry` to a working history while servicing a single turn, so the re-request sees the tool output. But `AgentLoop` currently only appends `TurnCompleted.assistant` to its long-lived `entries` list. It does not receive/persist the intermediate tool entries.

Impact:

- Future turns do not have the full tool-call/tool-result transcript.
- M3 JSONL sessions would be incomplete unless this is fixed first.
- Compaction and branch summaries would lose file-operation evidence.

Recommended fix: make tool entries part of the provider event stream or move tool-loop entry creation into `AgentLoop`, then append them as they happen.

### 2. `agentKind` is parsed but not honored

`Configuration` resolves `provider.agentKind`, but `Main.kt` always constructs `PromptProvider(AzureInferenceClient(...))`. This is fine before M5, but it is a small source of config drift and should fail clearly or route through a provider factory.

### 3. TUI is synchronous

`ConversationController.submit()` uses `runBlocking`, so input is blocked until the turn finishes. Pi's richer behavior (Esc cancel, steering/follow-up queues, responsive command UI) requires a coroutine job per active turn plus cancellation propagation into provider/tool execution.

### 4. Context-file loading is missing

Pi's quality heavily depends on loading `AGENTS.md`/`CLAUDE.md` and system append files. Konductor has prompt override/append settings, but it does not yet discover repo instructions. Given this repo already uses `AGENTS.md`, adding context-file discovery would improve real-world behavior quickly.

### 5. Docs/status drift inside Konductor

`docs/burndown.md` says M2 is complete and matches the code. Some other docs lag:

- `README.md` says tools are still being built out, but the 7 built-ins are present.
- `docs/index.md` status banner still says M0 complete and says the interactive path is still a scaffold.

Not a code blocker, but future agents should trust `docs/burndown.md` until those are refreshed.

## Suggested next slices

1. **M3 foundation: complete in-memory entry fidelity**
   - Persist tool call/result entries in `AgentLoop.history`.
   - Add tests proving a second user turn includes prior tool entries.

2. **JSONL session store**
   - Append entries as they occur.
   - Implement load/list/resume for cwd.
   - Add `/session`, `/new`, `/resume`, `/name` minimally.

3. **Async TUI turn execution**
   - Replace `runBlocking` submit path with a coroutine `Job`.
   - Wire Esc to cancellation.
   - Then add steering/follow-up queue if desired.

4. **Context discovery**
   - Load global/ancestor/cwd `AGENTS.md` initially.
   - Add `.konductor/SYSTEM.md` / `APPEND_SYSTEM.md` or align with existing settings.

5. **Provider factory / config honesty**
   - Route on `agentKind`, even if hosted returns a clear "not implemented yet" error.
   - Add CLI flags for model, tools allow-list, no-session, and eventually agent kind.

## Bottom line

Konductor has meaningful feature drift from pi as a full product, but not in a worrying way for the stated goal. The project has already captured pi's core architectural lesson: keep the UI, loop, provider, SDK, and tools separated. If the next milestone focuses on session/history correctness, Konductor will be on a solid path toward richer pi-like behavior without needing to clone pi feature-for-feature.
