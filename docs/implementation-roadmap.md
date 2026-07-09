# Implementation Roadmap

A phased, hackathon-sized build. Each milestone is independently demoable and has an **acceptance check**. After
**M0**, the **Prompt track (M1–M4)** and the **Hosted track (M5)** can proceed in parallel across contributors;
**M6** polishes both. **M2.5** (persisted PromptAgents) is an **opt-in** branch off M2.

```
M0 ── M1 ── M2 ── M3 ── M4 ─┐
  │          └── M2.5 ──────┤
  └────────── M5 ───────────┼── M6

Prompt track: M1→M2→M3→M4     Hosted track: M5 (parallel after M0)
Opt-in: M2.5 (persisted PromptAgents) branches off M2; its session-persistence rides on M3.
```

> **Parallel track — headless / ACP (a primary goal, co-equal with M6).** Running Konductor headless as a
> spec-compliant [ACP](https://agentclientprotocol.com) *agent* over stdio — so any ACP client (Zed, another
> tool, or another Konductor) can drive it — is a **core motivation**, tracked outside M0–M6 only because it's a
> frontend. Status in [burndown.md](burndown.md) (ACP track), design in [acp.md](spec/acp.md). The **agent role**
> (Konductor is driven) is Phases A/B (done) + **Phase C — core agent-role compliance** (tool-call visibility,
> permissions, session load/list), riding on M2/M3 (now done). The ACP **client** role (agent orchestration /
> sub-agents) is Phase D, deferred — see [future.md](future.md#agent-orchestration).

Design references live in [architecture.md](spec/architecture.md), [providers.md](spec/providers.md),
[hosted-agents.md](spec/hosted-agents.md), [sessions.md](spec/sessions.md), [compaction.md](spec/compaction.md),
[tools.md](spec/tools.md), [tui.md](spec/tui.md).

---

## M0 — Dependencies & provider seam

**Tasks**
- Add to `pom.xml`: `azure-ai-agents` (2.2.0), `azure-ai-projects` (2.2.0), `azure-identity`,
  `kotlinx-coroutines-core`, a JSON lib (`kotlinx-serialization` or Jackson).
- Add `core/` domain model: `Entry` hierarchy, `Session`, `ToolCall/ToolResult`, `Usage`, `AgentContext`, `ToolSpec`.
- Add the `provider/` seam: `AgentProvider`, `AgentEvent`, `TurnRequest`, `ToolExecutor`, `AgentKind`.
- Add `config/`: load `Config` from env + settings ([configuration.md](spec/configuration.md)).
- Build clients from a signed-in identity: `AgentsClientBuilder(...).buildResponsesClient()` (and, for hosted,
  `.allowPreview(true).buildAgentsClient()` / `buildAgentScopedOpenAIClient(...)`).

**Acceptance:** `mvn` compiles; a smoke test constructs the clients from `FOUNDRY_PROJECT_ENDPOINT` + `az login`
without runtime auth errors.

## M1 — Prompt: single-turn inference in the TUI

**Tasks**
- Implement `PromptProvider.runTurn` (no tools yet): build `input` from history, call the Responses API, emit
  `TextDelta`/`TurnCompleted`/`UsageReported`. _(Shipped **streaming** — `client.responses().createStreaming` →
  `InferenceChunk` — pulled forward from M6 for a responsive UI; see [burndown.md](burndown.md).)_
- Add `agent/AgentLoop`; replace `conversation/ConversationController` echo with a call into it.
- Render assistant text + status-bar tokens.

**Acceptance:** typing a prompt returns a real model answer in the transcript; the status bar shows token usage.

## M2 — Prompt: function-tool loop + tools

**Tasks**
- Implement `tool/` `ToolRegistry` + first tools: `read`, `ls`, `find`, `grep`, `bash`, `write`, `edit`
  ([tools.md](spec/tools.md)) with output truncation and cwd containment.
- Complete the harness-owned tool loop in `PromptProvider` (detect `functionCall`, execute via `ToolExecutor`,
  submit `ResponseFunctionToolCallOutputItem`, re-request).
- Render `ToolCallStarted`/`ToolCallCompleted`.

**Acceptance:** "read X and fix Y" performs real file reads/edits via tool calls; a read-only run (`--tools
read,ls,find,grep`) refuses mutations.

## M2.5 — Prompt: persisted agents (PromptAgent) — opt-in

Depends on M2 (needs `AgentContext` + `ToolRegistry` to define the agent and the tool loop to run it); the
session-persistence piece rides on M3. **Ephemeral Prompt inference stays the default** — this milestone is opt-in
and exercises the Foundry **Agents** surface (`PromptAgentDefinition` / `createAgentVersion` / `agent_reference`)
from the *client-owned* loop, distinct from the container-owned Hosted provider (M5).

**Tasks**
- Config: resolve an optional persisted-agent name — `KONDUCTOR_PROMPT_AGENT_NAME` (env) / `provider.promptAgentName` (settings);
  empty ⇒ ephemeral (unchanged). Version defaults to latest ([configuration.md](spec/configuration.md)).
- `AzureInferenceClient`: when an agent name is set, bind `AzureCreateResponseOptions().setAgentReference(new
  AgentReference(name).setVersion(...))` and **omit request `instructions`** (the agent supplies them). The
  transcript `input`, tool declarations, harness-owned loop, and local `ToolExecutor` are unchanged
  ([providers.md](spec/providers.md#persisted-prompt-agents-promptagent)).
- Keep the **dynamic preamble** (environment header + context files) live: send it per turn as a leading developer
  input item, so only the *stable* base prompt + tool declarations are baked into the agent
  ([agent-context.md](spec/agent-context.md#persisted-agents-stable-vs-dynamic-preamble)).
- Agent lifecycle: create a version from the current context —
  `createAgentVersion(name, new CreateAgentVersionInput(new PromptAgentDefinition(model).setInstructions(base).setTools(specs)))`;
  select an existing one by name.
- `/agent` TUI command: `list` / `use <name>` / `create [name]` + show the active agent
  ([tui.md](spec/tui.md#slash-commands)).
- Session: persist the resolved `agentReference` (name + version) in the session header; on resume reuse it and warn
  if config now names a different agent ([sessions.md](spec/sessions.md)).

**Acceptance:** with `KONDUCTOR_PROMPT_AGENT_NAME=<name>` a turn runs referencing the persisted agent (no request-side
`instructions`); `/agent create` mints a versioned agent from the current context and switches to it; a resumed
session reuses the agent it was created with. Session and compaction behavior are unchanged (transcript, tools, and
compaction stay client-side).

## M3 — Prompt: sessions

**Tasks**
- Add `session/` `SessionStore`: `NoOpSessionStore` (ephemeral) + JSONL persistence + `load`/`listForCwd`
  ([sessions.md](spec/sessions.md)).
- Append entries as they are produced; implement `buildInput` reconstruction.
- Wire `/new`, `/resume`, `/name`, `/session`; `--continue`/`--resume`.

**Acceptance:** a session survives restart and can be resumed with full history; `--no-session` stays in memory.

## M4 — Prompt: compaction

**Tasks**
- Add `compaction/` `Compactor` + `ContextWindowTracker` fed by `UsageReported`.
- Trigger at `contextTokens > contextWindow - reserveTokens`; write a `CompactionEntry`; rebuild input from summary
  + kept entries ([compaction.md](spec/compaction.md)). Handle split turns.
- Wire `/compact [instructions]` and settings.

**Acceptance:** a long session auto-compacts (a compaction entry appears; context % drops) and keeps answering
coherently; `/compact` works on demand.

## M5 — Hosted provider (parallel track)

**Tasks**
- Implement `provider/hosted/HostedProvider` ([hosted-agents.md](spec/hosted-agents.md)): select/deploy an agent version,
  configure the endpoint, create/reuse a session.
- Invoke via `buildAgentScopedOpenAIClient` + `agent_session_id`; emit `TextDelta`/`TurnCompleted`.
- Stream session logs → `LogFrame` (rendered in the log lane); optional session-file upload/download.
- Lifecycle cleanup (`stopSession`/`deleteSession`).

**Acceptance:** with `--agent-kind hosted`, a prompt runs inside the container, its logs stream into the TUI, and the
session is cleaned up on exit.

## M6 — Streaming & polish

**Tasks**
- Switch `PromptProvider` to `createStreamingAzureResponse` (`outputTextDelta`/`functionCallArgumentsDelta`).
- Unify the status bar (tokens/context %/cost); non-blocking input during streaming; `Esc` cancellation.
- `/model` and `--agent-kind` switching; error/retry polish.

**Acceptance:** assistant text streams token-by-token; a turn is cancelable; switching model/provider works
mid-session.

---

## Out of scope

Everything in [future.md](future.md) — Workflow/External kinds, server Conversations/Memory Stores, server-side
tools, branching, MCP, sub-agents, evaluations/tracing.

## Related docs

[index.md](index.md) · [architecture.md](spec/architecture.md) · [development.md](development.md) · [future.md](future.md)
