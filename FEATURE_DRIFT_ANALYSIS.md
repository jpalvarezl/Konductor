# Feature Drift Analysis

_Last reviewed: 2026-07-08_

This compares the current Konductor source tree against pi `@earendil-works/pi-coding-agent` 0.80.3 docs/source layout, with Konductor's `docs/burndown.md` used as the local progress baseline. Validation run: `mvn -q test` passed.

## TL;DR

The latest changes **reduce more feature drift than they introduce**. Konductor is still much smaller than pi, but it is now closer to the intended "pi-like coding-agent core in Kotlin/JVM, dogfooding Azure AI Agents / Foundry Projects" target:

- The Prompt path now has a real streamed tool loop with the pi-like built-in tool set (`read`/`write`/`edit`/`bash`/`grep`/`find`/`ls`).
- Tool calls/results are now folded into `AgentLoop.history`, so the earlier top drift item (lost tool history across turns) is resolved for in-memory turns.
- `ProviderFactory` honors `agentKind`, and the Hosted provider is implemented and live-verified, which is an intentional Azure dogfooding extension rather than accidental pi drift.
- ACP remains a useful headless integration story, but its event mapping is still behind pi/RPC-style observability for tools/logs/cancellation.

No latest change looks like harmful product drift away from the hackathon goal. The largest drift remains the expected unimplemented pi product surface: durable JSONL sessions, compaction, slash commands, async/cancelable turns, richer TUI/editor affordances, context-file discovery, project trust, and runtime customization.

Highest-priority drift to address next:

1. **Persistent sessions are now the biggest gap.** In-memory history fidelity is fixed, but there is still no `SessionStore`, JSONL append/load/list/resume, or session CLI/slash-command surface.
2. **Turn concurrency/cancellation remains pi-incompatible.** TUI still blocks in `runBlocking`; ACP `session/cancel` does not cancel a turn job; overlapping prompts can still interleave one `AgentLoop` history.
3. **Context-file discovery is missing.** Pi's coding quality depends heavily on global/ancestor/cwd `AGENTS.md` / `CLAUDE.md` and `SYSTEM` / `APPEND_SYSTEM` files; Konductor only supports configured prompt override/append.
4. **CLI/command UX is still minimal.** `--agent-kind`/`--model` exist, but there is no `--help`, `--version`, `--tools`, `--no-session`, `--continue`/`--resume`, or command router beyond `/quit`/`/exit`.
5. **Docs status drift has reappeared.** `docs/burndown.md` is the most current source, but `AGENTS.md`, `README.md`, and parts of `docs/index.md`/M5 text still understate M2/M5 completion.

## Latest-change drift check

### Drift reduced

- **Tool history fidelity:** `AgentLoop` now consumes `ToolCallStarted`/`ToolCallCompleted` and persists `ToolCallEntry`/`ToolResultEntry` in its in-memory transcript. Tests prove a later turn re-sends prior tool entries.
- **Provider/config honesty:** `Main` now routes through `ProviderFactory`; `agentKind=hosted` no longer silently runs Prompt.
- **Azure dogfood depth:** `HostedProvider` and `AzureHostedAgentClient` exercise the preview hosted-agent version/session/log/agent-scoped Responses path. This is intentionally beyond pi's built-in provider model but aligned with this repository's purpose.
- **M2 core tools:** the pi-like built-in local coding tools are present, cwd-contained, truncated, and tested.
- **First-run config UX:** missing Foundry config now produces a friendlier `ConfigurationException` path instead of an unqualified stack trace.

### New or still notable drift introduced by latest shape

- **Hosted provider creates a second execution model that pi does not have by default.** This is not a problem for the dogfooding goal, but it should remain clearly labeled as `AgentKind.Hosted` so the local pi-like Prompt path does not get entangled with server-owned sessions/logs/tools.
- **Partial M2.5 config exists without behavior.** `Configuration.promptAgentName` / `KONDUCTOR_PROMPT_AGENT_NAME` are parsed, but `AzureInferenceClient` does not bind a persisted PromptAgent yet. That is roadmap-consistent, but it is a repeat of the earlier "parsed but ignored" risk; document it loudly until M2.5 lands.
- **ACP now executes tools but hides tool events.** The executor is wired and tools run headlessly, but ACP only emits assistant text/failure chunks and `end_turn`; no `tool_call`, log, usage, permission, or cancellation updates yet.
- **Docs drift is now the main onboarding risk.** Some root docs still say tools/hosted are pending even though code and burndown say M2/M5 are complete.

## Coverage matrix vs. pi

| Area | Pi baseline | Konductor now | Drift / next step |
|---|---|---|---|
| Core coding loop | Streaming model loop with tool use | **Strong MVP parity:** Prompt provider streams Foundry Responses and loops over function tools | Good for hackathon core; keep Prompt path the canonical pi-like local loop |
| Built-in tools | `read`, `write`, `edit`, `bash`, `grep`, `find`, `ls` available; default UX exposes tool controls | **Covered:** same 7 tools, cwd containment, truncation, settings allow-list | Add CLI `--tools`/`--exclude-tools`/`--no-tools`; consider long-running bash progress/streaming |
| Provider/model support | Many providers, OAuth/API keys, `/model`, scoped model cycling | **Intentional narrow scope:** Foundry via Azure SDK + `DefaultAzureCredential`; `--model`; Prompt + Hosted kinds | Fine for dogfooding; add `/model`, persisted PromptAgent, and clearer provider status |
| Hosted/server-owned agent | Not a default pi concept | **Implemented:** `HostedProvider` dogfoods Azure hosted-agent versions/sessions/logs | Keep isolated behind `AgentKind.Hosted`; do not let server-owned state blur Prompt session/compaction semantics |
| TUI | Rich interactive UI, markdown/code/diff rendering, collapsible tools, footer with cost/context/cache/model | **Basic but functional:** transcript, status bar, composer, scroll, streamed text, tool/log system lines | Add markdown/diff rendering, collapsible tool output, multi-line editor, overlays, cost/context |
| Editor UX | `@` file refs, path completion, multiline, external editor, images, `!`/`!!` bash | **Mostly absent** | File refs/path completion are the highest-leverage next editor features |
| Slash commands | `/model`, `/settings`, `/resume`, `/new`, `/tree`, `/compact`, `/export`, etc. | **Only** `/quit` and `/exit` | Add a command router before feature-specific commands |
| Message queue/cancel | Steering/follow-up queues, abort/retry, Esc cancel | **Absent in TUI; ACP cancel not wired** | Requires async `AgentLoop.submit(...): Job`, mutex/single-flight, cancellation propagation |
| Sessions | Auto JSONL sessions, resume, names, tree branching, fork/clone | **Modeled/in-memory only:** `Entry`/`Session`; no store | M3 remains the biggest pi gap; fix `Session.cwd` type/serialization and add JSONL goldens |
| Branching | Tree navigation and branch summaries | **Schema groundwork only** via `parentId` | Defer until after durable linear JSONL sessions |
| Compaction | Auto/manual compaction, overflow recovery, branch summarization | **Spec/schema only:** `CompactionEntry` exists; `AzureInferenceClient` errors if asked to serialize it | M4; depends on session reconstruction and context token tracking |
| Context files | Loads global/ancestor/cwd `AGENTS.md`/`CLAUDE.md`, `SYSTEM.md`, `APPEND_SYSTEM.md` | **Partial:** base prompt + cwd/os/date + settings override/append | Add context discovery soon; important for coding quality and repo instructions |
| Customization | TS extensions, tools, commands, UI, event hooks, packages, skills, prompts, themes | **Internal seams only**; no runtime plugin/resource surface | OK for hackathon; keep `ToolRegistry`/provider/TUI seams plugin-friendly |
| Headless/integration | Print, JSON, RPC, SDK | **Different but promising:** ACP agent over stdio using same loop | ACP needs tool/log/usage updates, session load/list, cancellation, golden transcript tests |
| Security/trust | Project trust, resource loading gates, tool allow/exclude CLI, extension security model | **Partial:** cwd containment + settings allow-list; no trust prompt; bash enabled when advertised | Add project trust before loading project-local resources/extensions; add CLI tool gates |
| Distribution | npm package / binary-style distribution | **Covered differently:** shaded jar + jpackage profile + release workflow | Good JVM-native distribution; add packaged-jar smoke once `--help`/`--version` exists |
| Tests | Broad product tests | **Healthy for scope:** tools, loop, config, provider, hosted fakes, ACP mapping, TUI pieces | Add session JSONL goldens, concurrency/cancel tests, ACP golden transcript, package smoke |

## What is already strong

### 1. The architecture still points in the right direction

Konductor continues to preserve the important pi-inspired separations:

- TUI/ACP frontends do not call Azure SDKs directly.
- `AgentLoop` owns conversation orchestration and in-memory history.
- `AgentProvider` abstracts execution model (`Prompt` vs `Hosted`).
- `InferenceClient` keeps the Prompt path's OpenAI/Responses SDK details behind a narrow seam.
- `ToolRegistry` and `ToolExecutor` make built-in tools swappable/testable.
- The domain model already has `Entry`, `ToolCallEntry`, `ToolResultEntry`, `CompactionEntry`, and `Usage`.

This is the right foundation for a Java/Kotlin pi-like coding agent without cloning pi feature-for-feature.

### 2. The Prompt MVP coding loop is real

The Prompt path streams model output, declares local tools, executes them, re-submits tool outputs, persists tool interactions in the loop history, and renders tool activity in the TUI. That is the essential pi-like loop and it is the most important hackathon milestone.

### 3. Azure dogfooding is stronger than before

The Hosted provider adds meaningful dogfooding coverage for Azure AI Agents preview surfaces: hosted agent versions, endpoint configuration, server sessions, session log streaming, and agent-scoped Responses invocation. That is product-specific and not pi parity, but it is exactly the differentiator this project is meant to explore.

## Important drift / risks

### 1. Durable sessions are now the dominant gap

The earlier in-memory tool-history issue is fixed, but pi's session model is much richer:

- automatic JSONL persistence,
- append-as-produced crash tolerance,
- load/list/resume,
- session names and info,
- tree branching/fork/clone,
- export/import/share.

Konductor still has only in-memory `AgentLoop.history`. Before M3, also fix the serialization readiness problems: `Session.cwd` is `kotlinx.io.files.Path`, `Entry` subtypes are not yet a sealed serializable hierarchy, and there are no JSONL golden tests.

### 2. Turn execution is still synchronous and not single-flight

`ConversationController.submit()` runs a turn with `runBlocking`, so the Lanterna loop cannot accept steering/follow-up messages or cancel with Esc during a turn. `AgentLoop.runTurn()` returns a cold `Flow` that mutates shared `entries` when collected; concurrent ACP prompts for one session can interleave unless a mutex/single-flight guard is added.

This is the main blocker for pi-like queue/cancel UX.

### 3. ACP observability lags the real loop

ACP mode shares the loop and can execute tools, but the protocol only receives assistant chunks/failure text/end-turn today. That means an ACP client cannot show tool calls, tool results, hosted logs, usage, permission prompts, or cancellations. For headless/editor integration, this is a significant feature gap even though the underlying work is happening.

### 4. Context discovery is missing

Pi loads `AGENTS.md` / `CLAUDE.md` from global, ancestor, and cwd locations; Konductor's `AgentContextFactory` explicitly defers this. Since Konductor is a coding agent and this repository itself relies on `AGENTS.md`, this is a high-value next slice after session fidelity.

### 5. CLI and command surface is underbuilt

`--agent-kind` and `--model` landed, but unknown flags are ignored, `--help`/`--version` do not exist, and there is no command router for `/model`, `/tools`, `/resume`, `/compact`, `/settings`, etc. Pi's product surface relies heavily on commands and CLI mode switches.

### 6. Docs drift is currently more dangerous than code drift

The code is ahead of several docs:

- `AGENTS.md` still describes M1-era current architecture and says real tools/hosted remain pending.
- `README.md` says tools and hosted agents are still being built out.
- `docs/index.md` status says M2 complete but also says Hosted/M5 remains.
- `docs/burndown.md` is mostly current but has a stale M5 "Remaining" note that still mentions TUI `LogFrame` rendering even though a test exists.

Future agents should continue to trust `docs/burndown.md` first, but these root docs should be synced to avoid onboarding mistakes.

## Suggested next slices

1. **M3 session foundation**
   - Decide persisted `Session.cwd` representation.
   - Make `Entry` serialization explicit and add JSONL golden tests.
   - Implement append-only `SessionStore` and load/list/resume for cwd.
   - Wire `/session`, `/new`, `/resume`, `/name`, `--continue`, `--resume`, `--no-session`.

2. **Async/single-flight turn runtime**
   - Replace TUI `runBlocking` turn execution with a coroutine `Job` owned by the app/session.
   - Add `Mutex`/single-flight semantics to `AgentLoop` or ACP session.
   - Wire Esc and ACP `session/cancel` to cancellation, preserving history decisions for partial turns.

3. **Context-file discovery**
   - Load global/ancestor/cwd `AGENTS.md` or `CLAUDE.md`.
   - Add `.konductor/SYSTEM.md` / `APPEND_SYSTEM.md` or align deliberately with pi's `.pi/` naming.
   - Add trust gating before loading project-local executable resources later.

4. **CLI/command router**
   - Add `--help` and `--version` first so package smoke tests are possible.
   - Add `--tools`/`--exclude-tools`/`--no-tools` and basic `/model`/`/settings`/`/compact` command plumbing.
   - Stop silently ignoring unknown flags.

5. **ACP Phase C visibility**
   - Emit tool/log/usage `session/update`s.
   - Add permission request shape for mutating tools.
   - Add an automated golden-transcript integration test.

## Bottom line

The latest changes do **not** introduce worrying feature drift. They move Konductor closer to the intended core: a pi-inspired local coding-agent harness implemented on the JVM while dogfooding Azure AI Agents / Foundry SDKs. The main caution is to keep Azure-specific Hosted behavior isolated behind the provider seam, while prioritizing the pi fundamentals next: durable sessions, cancelable async turns, context-file loading, and a real command/CLI surface.
