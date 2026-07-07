# Konductor Documentation

Konductor is a Kotlin/JVM terminal coding-agent harness that **dog-foods** our team's Azure SDKs —
[`com.azure:azure-ai-agents`](https://central.sonatype.com/artifact/com.azure/azure-ai-agents) and
[`com.azure:azure-ai-projects`](https://central.sonatype.com/artifact/com.azure/azure-ai-projects) (v2) — while
being a genuinely useful local coding tool, in the spirit of [`pi`](https://pi.dev) and Copilot CLI.

> ## 📋 Status: specs written — implementation pending
>
> These `docs/` are the **full specification** for Konductor, written so the team and lower-context agents can
> implement directly from them. **No production code exists yet** — `src/` is still the original TUI scaffold; the
> build is staged in [implementation-roadmap.md](implementation-roadmap.md).
>
> **How to use:** start with the doc map below; [architecture.md](spec/architecture.md) is the keystone that defines the
> shared abstractions. Illustrative Kotlin sketches inside the docs are **design artifacts, not committed code**.

## Documentation map

**Procedural** — setup, planning, and progress (top level):

| Doc | What it covers | Status |
|-----|----------------|--------|
| [development.md](development.md) | Build/run, project layout, pointing at a Foundry project, debugging | spec |
| [distribution.md](distribution.md) | Self-contained per-OS `jpackage` bundles: `dist` profile, release workflow, artifacts | spec |
| [implementation-roadmap.md](implementation-roadmap.md) | Phased hackathon build (M0–M6) with acceptance checks | spec |
| [burndown.md](burndown.md) | Live progress tracker: checkbox status of roadmap milestones + ad-hoc work | living |
| [future.md](future.md) | Living backlog of intentionally deferred ideas | backlog |

**Spec** — the design specification (under [`spec/`](spec/)):

| Doc | What it covers | Status |
|-----|----------------|--------|
| [spec/architecture.md](spec/architecture.md) | Keystone: layers, domain model, `AgentProvider`/`AgentEvent`, one-turn data flow, threading | spec |
| [spec/providers.md](spec/providers.md) | The `AgentProvider` seam + the Azure **Prompt** provider (Responses loop, function tools, opt-in persisted PromptAgents) | spec |
| [spec/hosted-agents.md](spec/hosted-agents.md) | The **Hosted** provider: deploy code agent, server sessions, log streaming, session files | spec |
| [spec/agent-context.md](spec/agent-context.md) | Preamble / system prompt assembly, context files, tool registry surface | spec |
| [spec/tools.md](spec/tools.md) | Built-in tools (read/edit/write/bash/grep/find/ls), execution model, truncation | spec |
| [spec/sessions.md](spec/sessions.md) | Session lifecycle, transcript/entry model, JSONL persistence & resume | spec |
| [spec/compaction.md](spec/compaction.md) | Context-window compaction: triggers, algorithm, summary format | spec |
| [spec/tui.md](spec/tui.md) | Terminal UI: layout, event loop, streaming/log rendering, keybindings, status bar | spec |
| [spec/configuration.md](spec/configuration.md) | Settings & env vars, precedence, provider/agent-kind selection | spec |
| [spec/acp.md](spec/acp.md) | Headless ACP (Agent Client Protocol) mode over stdin/stdout: how to run, mapping, status | partial |

## Finding things fast

These docs are meant to be navigated, not read cover-to-cover. For orientation, read in this order:

1. **[`AGENTS.md`](../AGENTS.md)** (repo root) — spec-vs-code warning, build/run/test, conventions.
2. **[burndown.md](burndown.md)** — live progress (what's built vs pending); read before deriving status from code.
3. **This file** — the documentation map above, plus the confirmed decisions and SDK grounding facts below.
4. **[spec/architecture.md](spec/architecture.md)** — the keystone (layers, domain model, `AgentProvider` / `AgentEvent`).

Every doc opens with a one-line purpose statement. The set is small and precisely termed, so keyword search beats
reading whole files — prefer `rg` (or your agent's grep tool) scoped to `docs/`:

```bash
rg -in "InferenceClient" docs/                  # where is a term / symbol specified?
rg -il "compaction" docs/                       # which doc owns a concept? (file names only)
rg -n  "^#{1,3} " docs/spec/                     # list section headings to locate a subsection
rg -n  "createAzureResponse|buildResponsesAsyncClient" docs/   # trace an SDK symbol through the sketches
rg -n  "^# " docs/ -A1                           # the one-line purpose header of every doc
```

Docs describe the **target** design; confirm what actually exists by reading `src/` + [burndown.md](burndown.md).
Illustrative Kotlin in the docs is a design artifact, not committed code.

## Confirmed decisions

1. **Provider model — client-owned history + client-side compaction.** Use the SDK's **Responses API
   (`ResponsesClient`) for inference only**. Konductor stores the transcript and summarizes when the context
   window fills (pi / Copilot CLI style). *Memory Stores are **not** used for chat-history compaction* — they are
   a possible **future** long-term / per-user persistence mechanism (see [future.md](future.md)).
2. **Product — a local coding agent:** cwd-scoped, with file read/edit/write, shell, and search tools. This is
   why file-I/O and text-truncation are first-class concepts.
3. **First providers — Prompt AND Hosted agent kinds.** One `AgentProvider` seam abstracts **two execution
   models**:
   - **Prompt provider** — model + system prompt + **client-side function tools**, client-managed Responses loop,
     client-owned history + compaction (*the harness owns the loop*).
   - **Hosted provider** — a containerized agent deployed to Foundry, invoked via an **agent-scoped Responses
     client** bound to a **server-side session**, with **log streaming** and **session files** (*the container
     owns its loop/context; the harness is client + orchestrator + I/O bridge*).

   **Workflow** and **External** agent kinds are deferred ([future.md](future.md)).
4. **Multi-turn strategy (Prompt provider):** re-send the reconstructed transcript as `input` each turn; do **not**
   use `previousResponseId` / `Conversation` for the compaction-managed loop (those move state server-side and
   would defeat client compaction).
5. **Persisted Prompt agents (PromptAgent) — opt-in ([M2.5](implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in)).**
   The Prompt loop can optionally bind to a named, versioned Foundry **PromptAgent** (`agent_reference`) whose
   *stable* instructions + tool declarations live server-side, while the transcript, tool **loop**, local execution,
   and compaction stay client-side and the *dynamic* preamble is still sent per turn. Selected by
   `KONDUCTOR_AGENT_NAME` / `/agent`. Ephemeral (no agent) remains the default; this is **distinct from the Hosted
   provider**, which moves the whole loop server-side ([providers.md](spec/providers.md#persisted-prompt-agents-promptagent)).

## Terminology map

| Konductor | pi | Foundry / SDK (v2) |
|-----------|-----|--------------------|
| Provider | Provider / model API | `ResponsesClient` + agent kind |
| Session | Session (JSONL tree) | Client-owned transcript (Prompt) / `AgentSessionResource` (Hosted) |
| Compaction | Compaction | Client-side summary (Memory Stores = future) |
| Agent context | System prompt + context files | `instructions` + `PromptAgentDefinition` |
| Tool | Built-in tool / `registerTool` | client-side `FunctionTool` |
| Turn / Response | Turn | `Response` (+ `ResponseUsage`) |

> **Foundry v2 naming:** the platform renamed the old Assistants concepts — Threads/Messages/Runs/Assistants are
> now **Conversations, Items, Responses, Agent Versions**, on v1 stable routes (`/openai/v1/`).

## SDK grounding facts — Prompt provider

*Verified against the `azure-ai-agents` / `azure-ai-projects` source + samples under `sdk/ai/` in the
[Azure/azure-sdk-for-java](https://github.com/Azure/azure-sdk-for-java) repo, and the Foundry docs.*

- **Project endpoint format:** `https://{resource}.ai.azure.com/api/projects/{project}`.
- **Client + auth:**
  `new AgentsClientBuilder().endpoint(FOUNDRY_PROJECT_ENDPOINT).credential(new DefaultAzureCredentialBuilder().build())`
  → `.buildResponsesClient()` / `.buildResponsesAsyncClient()`. Default AAD scope `https://ai.azure.com/.default`.
  `AIProjectClientBuilder` mirrors this and offers `buildOpenAIClient()`.
- **Env vars in samples:** `FOUNDRY_PROJECT_ENDPOINT`, `FOUNDRY_MODEL_NAME`.
- **Create a response:** `ResponsesClient.createAzureResponse(AzureCreateResponseOptions, ResponseCreateParams.Builder)`.
  `ResponseCreateParams` supports `input`, `instructions`, `model`, `tools`, `temperature`, `text`,
  `conversation`, `previousResponseId`.
- **Function-tool loop (harness-owned — there is no auto-loop helper):**
  1. Define a tool: `new FunctionTool(name, parameters, strict).setDescription(...)`.
  2. Detect calls: `response.output()` → `ResponseOutputItem.functionCall()` (`callId()`, `name()`, `arguments()`).
  3. Execute locally, then submit `ResponseFunctionToolCallOutputItem(callId, output)` on the next request's `input`.
- **Streaming:** `createStreamingAzureResponse(...)` → `IterableStream<ResponseStreamEvent>` (sync) /
  `Flux<ResponseStreamEvent>` (async). Deltas: `outputTextDelta()`, `functionCallArgumentsDelta()`.
- **Token accounting:** `Response.usage()` → `ResponseUsage.inputTokens()/outputTokens()/totalTokens()` — drives
  the context-window tracker and the compaction trigger.
- **Types come from openai-java** (`com.openai...`), wrapped by the Azure `ResponsesClient`.
- **Agent definition (persisted PromptAgent — [M2.5](implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in)):** `new PromptAgentDefinition(model)` with
  `setInstructions`, `setTemperature`, `setTopP`, `setTools`, `setText`, `setStructuredInputs`.

## SDK grounding facts — Hosted provider

*Hosted-agent sessions, log streaming, session files, and code-based agents are **preview** — build the client with
`allowPreview(true)`.*

- **Deploy a code-based hosted agent:** `agentsClient.createAgentVersionFromCode(agentName, HostedAgentDefinition,
  CodeFileDetails(codeZip), description, metadata)`; retrieve code with `downloadAgentCodeWithResponse`.
- **Sessions (hosted-only):** `builder.allowPreview(true).buildAgentsClient()`; then `createSession` /
  `getSession(agentName, sessionId)` / `listSessions` / `deleteSession` / `stopSession`.
  `AgentSessionResource.getAgentSessionId()` / `getStatus()` (`AgentSessionStatus`). Requires a container image
  (env `FOUNDRY_AGENT_CONTAINER_IMAGE`).
- **Invoke a hosted agent:** configure the endpoint (`AgentEndpointConfig` + `VersionSelector` +
  `ProtocolConfiguration.setResponses(ResponsesProtocolConfiguration)`) via `updateAgentDetails`, then
  `builder.buildAgentScopedOpenAIClient(agentName)` and
  `responses().create(ResponseCreateParams… putAdditionalBodyProperty("agent_session_id", …))`.
- **Log streaming:** `agentsClient.getSessionLogStreamWithResponse(agentName, version, sessionId, …)` →
  `Response<BinaryData>` SSE stream of `SessionLogEvent` frames.
- **Session files:** upload / download / list session files to bridge local files to/from the container.
- **Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents/`
  (`CodeAgentSample`, `SessionsSample`, `SessionLogStreamSample`, `SessionFilesSample`, + async variants).

## Current codebase (starting point)

- `Main.kt` → runs the Lanterna `TuiApp`, or the **headless** ACP frontend when launched with `acp`.
- `conversation/ConversationController.submit()` — **the seam** to replace with real agent orchestration.
- `core/AppState`, `core/Message` (`ChatMessage`, `MessageRole`), `core/InputState`.
- `tui/component/*` (`TranscriptView`, `StatusBar`, `PromptInputView`), `tui/style/Theme`, `tui/layout`.
- `acp/KonductorAcpAgent.kt` — headless [ACP](https://agentclientprotocol.com) frontend over stdio (Phase A echo bridge; see [spec/acp.md](spec/acp.md)).
- Build: Maven, Kotlin 2.4.0, JVM 25, `mvn` = `compile exec:java`, shade jar on `package`.

## Key references

- **pi docs** (design inspiration): compaction, sessions, providers, sdk, usage, tui.
- **SDK source:** `sdk/ai/{azure-ai-agents,azure-ai-projects}` in [Azure/azure-sdk-for-java](https://github.com/Azure/azure-sdk-for-java) (clone locally to browse the samples referenced throughout these docs).
- **Foundry docs:** <https://learn.microsoft.com/en-us/azure/foundry/>.
