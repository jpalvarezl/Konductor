# Providers

A **provider** is Konductor's adapter over one Foundry **agent kind**. All providers implement the same
[`AgentProvider`](architecture.md#the-agentprovider-seam) seam, so the agent loop and TUI are identical regardless
of kind. This doc covers the seam and the **Prompt provider**. The **Hosted provider** has its own doc:
[hosted-agents.md](hosted-agents.md).

## The seam recap

The authoritative interface is
[`AgentProvider`](../../src/main/kotlin/com/konductor/provider/AgentProvider.kt).
`AgentProvider.runTurn` runs **one user turn to completion** and emits
[`AgentEvent`](architecture.md#the-agentprovider-seam)s.
Tool execution is delegated to the harness-supplied `ToolExecutor` so tools stay local and cwd-scoped
([tools.md](tools.md)). `AgentProvider` is the **loop-ownership** seam between Foundry agent execution models; the
separate [`FoundryResponsesClient`](architecture.md#two-axes-two-seams) seam represents one Foundry Responses call
beneath the Prompt path and confines SDK types. Neither seam is a non-Foundry backend extension point.

### Agent-kind mapping

| `AgentKind` | Provider | Loop owner | History | Compaction | Doc |
|-------------|----------|-----------|---------|------------|-----|
| `Prompt` | `PromptProvider` | provider (client-side) | client-owned transcript | client-side | this doc |
| `Hosted` | `HostedProvider` | server container | server session | server-managed | [hosted-agents.md](hosted-agents.md) |
| `Workflow`, `External` | — | — | — | — | deferred, [future.md](../future.md) |

### Selection & construction

The provider is chosen from config ([configuration.md](configuration.md)) by
[`ProviderFactory.create`](../../src/main/kotlin/com/konductor/provider/ProviderFactory.kt). For `Prompt`, the factory
constructs `PromptProvider` with `SwitchableFoundryResponsesClient`, which selects the ephemeral adapter when no
PromptAgent name is bound and the agent-scoped adapter otherwise. For `Hosted`, it constructs `HostedProvider`. The
configured clients share the project endpoint and credential.

The Prompt provider receives its `FoundryResponsesClient` by injection, so tests can supply
[`MockFoundryResponsesClient`](../../src/test/kotlin/com/konductor/provider/inference/MockFoundryResponsesClient.kt).
**Scope guard:** retain the interface only for SDK containment, client lifecycle, preview churn, and deterministic
loop tests. The two production implementations represent the Foundry request shapes Konductor needs (ephemeral and
agent-scoped), not interchangeable service vendors.

## Client construction & auth (shared)

Both providers authenticate with `DefaultAzureCredential` (Entra ID) against a Foundry **project endpoint**
(`https://{resource}.ai.azure.com/api/projects/{project}`). The default AAD scope `https://ai.azure.com/.default`
is applied by the builder. Current construction is authoritative in
[`EphemeralFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt),
[`PromptAgentFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt),
and
[`AzureHostedAgentClient`](../../src/main/kotlin/com/konductor/provider/hosted/AzureHostedAgentClient.kt).
The implementations construct clients independently through `AgentsClientBuilder`. The accepted Foundry-first
migration [I055](../iterations/I055-foundry-first-platform-alignment.md) will introduce a focused project composition
boundary spanning `AIProjectClientBuilder` and `AgentsClientBuilder`, including shared endpoint/credential/preview
policy and the `azure-ai-projects` Deployments/Connections surfaces. See [configuration.md](configuration.md) for env
vars and credential setup.

> **Foundry v2 naming:** older Assistants-era material may refer to Threads, Messages, Runs, and Assistants. The v2
> surface uses Conversations, Items, Responses, and Agent Versions on the `/openai/v1/` routes. Konductor uses the
> v2 names throughout.

### Client ownership — use the closeable OpenAI client

Konductor owns the blocking `OpenAIClient` returned by `buildOpenAIClient()` and closes it with the provider. The
Azure `ResponsesAsyncClient` wrapper was deliberately dropped because its builder path hides/discards the underlying
closeable OpenAI client, leaving no reliable way for the harness to release the executor. The internal Foundry call
seam remains coroutine-shaped: blocking work/stream iteration runs on `Dispatchers.IO`, and cancellation propagates
through the collector and closes the stream. Re-evaluate the Azure `ResponsesClient.createAzureResponse` convenience
surface during I055 and retain direct OpenAI-client access only where an SDK limitation requires it.

## Prompt provider

A **Prompt agent** is a Foundry model deployment + system instructions + client-side function tools. `PromptProvider`
owns the client-side tool loop while delegating each Foundry Responses call to
[`FoundryResponsesClient`](architecture.md#two-axes-two-seams). It executes requested tools locally, feeds outputs
back, and repeats until the model produces a final answer. All SDK contact lives in the Foundry adapters
([below](#foundry-responses-adapters-the-prompt-sdk-boundary)).

### Request shape

Konductor re-sends the **reconstructed transcript** as history every turn (never `previousResponseId` /
`Conversation`), so client-side compaction stays authoritative. The provider passes that history in an application
`FoundryResponsesRequest`; mapping it to Foundry SDK input items is the Responses adapter's job (below).

### The harness-owned loop (Foundry SDK-decoupled)

[`PromptProvider.runTurn`](../../src/main/kotlin/com/konductor/provider/PromptProvider.kt) talks only to
[`FoundryResponsesClient`](architecture.md#two-axes-two-seams); no SDK types appear in the loop, so
[`PromptProviderTest`](../../src/test/kotlin/com/konductor/provider/PromptProviderTest.kt) exercises it with the mock
client.

For every model call, the provider collects `FoundryResponsesEvent.TextDelta` events immediately, forwards structured
`Retrying` status, and requires one terminal `Completed` event carrying the aggregated text, tool calls, and usage.
The loop appends `ToolCall`/`ToolResult` entries to the working history and starts another streaming call until a
completion has no tool calls. Everything Foundry-SDK-specific—serializing history to SDK input items, submitting tool
outputs, retry classification, and assembling the terminal result—lives behind `respondStreaming(...)`.

### Foundry Responses adapters (the Prompt SDK boundary)

[`EphemeralFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt)
implements the default ephemeral client and owns its Azure/OpenAI Responses types.
[`PromptAgentFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt)
is the sibling agent-scoped adapter for persisted PromptAgents. Nothing above this seam imports AI SDK types.

`EphemeralFoundryResponsesClient.buildParams` maps a `FoundryResponsesRequest` to `ResponseCreateParams`: model,
instructions, reconstructed transcript, tools, and optional temperature. The shared
[`serializeHistory`](../../src/main/kotlin/com/konductor/provider/inference/ResponsesMapping.kt) function maps entries
to Responses **input items**: `UserEntry`/`AssistantEntry` to messages, `ToolCallEntry` to a function-call item, and
`ToolResultEntry` to a `ResponseFunctionToolCallOutputItem` matched by `callId`.
`EphemeralFoundryResponsesClient.toFunctionTool` maps each `ToolSpec` to an SDK `FunctionTool`. It intentionally sets
`strict=false`: built-in tools have optional properties absent from JSON Schema `required`, which the strict Responses
tool schema rejects.

There is **no auto tool-loop helper** in the SDK, so each `respondStreaming(...)` collection makes exactly one model
call. The shared
[`OpenAIClient.streamFoundryResponse`](../../src/main/kotlin/com/konductor/provider/inference/ResponsesMapping.kt)
emits text deltas as they arrive and maps the SDK terminal completed response to one aggregated
`FoundryResponsesResult`. The terminal mapper reads `ResponseOutputItem.functionCall()` (`callId()`, `name()`,
`arguments()`) and
`Response.usage()` (`inputTokens()`/`outputTokens()`/`totalTokens()`). Tool outputs return on the next request as
`ResponseFunctionToolCallOutputItem`s matched by `callId`. These SDK types originate in **openai-java**
(`com.openai...`) behind the Azure-built `OpenAIClient`.

`EphemeralFoundryResponsesClient` wraps that stream with capped exponential backoff. Before any `TextDelta` or
`Completed` event, a transient 429/5xx/timeout emits `FoundryResponsesEvent.Retrying`, delays, and starts a fresh call.
Once model output has started, a failure is surfaced rather than replaying a partial answer. Cancellation propagates
through the flow and closes/interrupts the in-flight stream. The interface also retains a direct non-streaming
`respond(...)` helper for focused adapter use, but `PromptProvider` production turns use `respondStreaming(...)`.

### Usage & the context window

Every `UsageReported` event updates the [`ContextWindowTracker`](architecture.md#compaction-integration). When
`totalTokens` approaches the model's window, the agent loop compacts before the next turn
([compaction.md](compaction.md)).

## Persisted Prompt agents (PromptAgent)

By default the Prompt provider is **ephemeral**: it sends a bare `model` deployment and the request carries
`instructions` + `tools` every turn (above). Optionally — as an opt-in
[M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in) feature — Konductor can bind the
loop to a **named, versioned Prompt agent** stored in Foundry, whose `instructions` and tool declarations live
**server-side**. This exercises the Foundry **Agents** surface (`PromptAgentDefinition` / `createAgentVersion` plus an agent-scoped
OpenAI client) from the *client-owned* loop, and is **distinct from the Hosted provider**
([hosted-agents.md](hosted-agents.md)), which moves the whole loop into a server container.

**Scope guard — what stays client-side:** the transcript/history, the harness-owned tool **loop**, the local
`ToolExecutor` (cwd-scoped), and compaction are all unchanged. A PromptAgent only supplies the server-side
*definition*; Konductor still drives the loop and executes tools locally. Because Konductor **creates** the agent
from its own [`AgentContext`](agent-context.md) + [`ToolRegistry`](tools.md), the agent's baked tool declarations
mirror the local tool schemas.

[`AzurePromptAgentClient.createAgentVersion`](../../src/main/kotlin/com/konductor/provider/inference/AzurePromptAgentClient.kt)
creates a version from the current stable base instructions and local tool declarations. Per turn,
[`PromptAgentFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt)
builds an agent-scoped client and sends an **input-only** Responses request. The persisted agent supplies model,
instructions, and tool declarations; the service rejects those fields when sent again. The adapter prepends the
dynamic preamble as a developer input item, serializes the reconstructed history, and consumes the stream through the
same `FoundryResponsesEvent.TextDelta` / terminal `Completed` mapping as the ephemeral path.

**Stable vs dynamic instructions.** A baked agent version *freezes* its `instructions`, so only the **stable** base
system prompt + tool declarations belong in the `PromptAgentDefinition`. The **dynamic preamble** — environment
header (cwd/os/date), and eventually discovered context files (`AGENTS.md`) — must stay live, so Konductor sends it
**per turn** as a leading developer input item rather than baking it into the agent
([agent-context.md](agent-context.md)). The committed implementation currently supplies the environment header;
context-file discovery remains pending.

**Selection, session & lifecycle.** The agent name comes from config (`KONDUCTOR_PROMPT_AGENT_NAME` / `provider.promptAgentName`,
[configuration.md](configuration.md)) or the [`/agent`](tui.md#slash-commands) TUI command (`use` / `create`). The
resolved agent name is persisted in the session header and rebound to the latest available version on resume
([sessions.md](sessions.md)); empty ⇒ ephemeral (the default). Compaction is unaffected — the baked
instructions/tool declarations are fixed server-side overhead counted in `Usage.totalTokens` but outside the client
transcript ([compaction.md](compaction.md)). Sharing a configured agent across clients is a side-benefit.

## Related docs

[architecture.md](architecture.md) · [hosted-agents.md](hosted-agents.md) · [agent-context.md](agent-context.md) ·
[tools.md](tools.md) · [compaction.md](compaction.md) · [configuration.md](configuration.md)

**Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/` — `CreateResponse.java`,
`FunctionCallSync.java`, `CreateResponseWithConversation.java`.
