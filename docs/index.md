# Konductor Documentation

Konductor is a Kotlin/JVM terminal coding-agent harness that **dog-foods** our team's Azure SDKs —
[`com.azure:azure-ai-agents`](https://central.sonatype.com/artifact/com.azure/azure-ai-agents) and
[`com.azure:azure-ai-projects`](https://central.sonatype.com/artifact/com.azure/azure-ai-projects) (v2) — while
being a genuinely useful local coding tool, in the spirit of [`pi`](https://pi.dev) and Copilot CLI.

> ## 🏗️ Status: scaffolding
>
> This `docs/` folder is **scaffolding for a hackathon**. Only this `index.md` is written; every other file is a
> **stub** (outline + `TODO`s). **We author the specs together during the hackathon** — pick a stub, put your name
> on it, and fill it in. This page is the shared foundation: the decisions we've locked, the terminology, and the
> SDK research so you don't have to re-do it.
>
> **How to contribute:** claim a doc in the map below (add your handle next to it in a PR), replace the `TODO`
> sections with real content, and keep the cross-links and terminology consistent with this page.

## Documentation map

| Doc | What it covers | Status |
|-----|----------------|--------|
| [architecture.md](architecture.md) | Keystone: layers, one-turn data flow, threading, the two execution models | stub |
| [providers.md](providers.md) | The `AgentProvider` seam + the Azure **Prompt** provider (Responses loop, function tools) | stub |
| [hosted-agents.md](hosted-agents.md) | The **Hosted** provider: deploy code agent, server sessions, log streaming, session files | stub |
| [agent-context.md](agent-context.md) | Preamble / system prompt assembly, context files, tool registry surface | stub |
| [tools.md](tools.md) | Built-in tools (read/edit/write/bash/grep/find/ls), execution model, truncation | stub |
| [sessions.md](sessions.md) | Session lifecycle, transcript/entry model, JSONL persistence & resume | stub |
| [compaction.md](compaction.md) | Context-window compaction: triggers, algorithm, summary format | stub |
| [tui.md](tui.md) | The GUI: layout, event loop, streaming/log rendering, keybindings, status bar | stub |
| [configuration.md](configuration.md) | Settings & env vars, precedence, provider/agent-kind selection | stub |
| [implementation-roadmap.md](implementation-roadmap.md) | Phased hackathon build (M0–M6) with acceptance checks | stub |
| [future.md](future.md) | Living backlog of intentionally deferred ideas | stub |
| [development.md](development.md) | Build/run, project layout, pointing at a Foundry project, debugging | stub |

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

*Verified against the SDK source + samples in
`C:\Users\josealvar\code\azure_repos\azure-sdk-for-java\sdk\ai\` and the Foundry docs.*

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
- **Agent definition (if registering a Prompt agent):** `new PromptAgentDefinition(model)` with
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

- `Main.kt` → `TuiApp.run()` (Lanterna screen + event loop; renders transcript/status/composer).
- `conversation/ConversationController.submit()` — **the seam** to replace with real agent orchestration.
- `core/AppState`, `core/Message` (`ChatMessage`, `MessageRole`), `core/InputState`.
- `tui/component/*` (`TranscriptView`, `StatusBar`, `PromptInputView`), `tui/style/Theme`, `tui/layout`.
- Build: Maven, Kotlin 2.0.21, JVM 21, `mvn` = `compile exec:java`, shade jar on `package`.

## Key references

- **pi docs** (design inspiration): compaction, sessions, providers, sdk, usage, tui.
- **SDK source:** `C:\Users\josealvar\code\azure_repos\azure-sdk-for-java\sdk\ai\{azure-ai-agents,azure-ai-projects}`.
- **Foundry docs:** <https://learn.microsoft.com/en-us/azure/foundry/>.
