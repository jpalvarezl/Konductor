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
When supported, tool execution is delegated to the harness-supplied `ToolExecutor` so Prompt tools stay local and
cwd-scoped ([tools.md](tools.md)); Hosted declares no client-local tool surface. `AgentProvider` is the
**loop-ownership** seam between Foundry agent execution models; the separate [`FoundryResponsesClient`](architecture.md#two-axes-two-seams) seam represents one Foundry Responses call
beneath the Prompt path and confines SDK types. Neither seam is a non-Foundry backend extension point.

### Agent-kind mapping

| `AgentKind` | Provider | Loop owner | History | Client compaction/model switch/tools | PromptAgent management | Doc |
|-------------|----------|-----------|---------|--------------------------------------|------------------------|-----|
| `Prompt` | `PromptProvider` | provider (client-side) | client-owned transcript | yes / yes / yes | typed binder + lifecycle surface | this doc |
| `Hosted` | `HostedProvider` | server container | server session | no / no / no | none | [hosted-agents.md](hosted-agents.md) |
| `Workflow`, `External` | — | — | — | — | — | deferred, [future.md](../future.md) |

### Selection & construction

Provider construction is the boundary between two-phase startup and executable runtime. Bootstrap configuration can
construct the project/catalog side of `FoundryProjectRuntime` while a Prompt model is unresolved, but it cannot be
passed to a provider. Workspace trust and execution-target resolution finish first; only the final effective
`Configuration`, whose `model` is non-null and non-blank, may reach
[`FoundryProjectRuntime.createProvider`](../../src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt),
`AgentContextFactory`, or an ACP session-runtime factory. No temporary provider is created for inventory lookup or the
pre-provider TUI selector.

Before `createProvider`, a selected resume/continue/load session's strict schema/binding kind must equal the effective
configured kind. Prompt-over-Hosted and Hosted-over-Prompt both fail without discovery, provider construction, service
operations, or writes; this slice neither switches provider kind nor migrates/relabels session metadata.

`createProvider` delegates agent-kind selection to
[`ProviderFactory`](../../src/main/kotlin/com/konductor/provider/ProviderFactory.kt) and returns a `ProviderRuntime`
rather than a bare provider. The runtime exposes the provider's explicit capability contract and a sealed
`ProviderManagement` surface. For `Prompt`, the factory constructs `PromptProvider` with
`SwitchableFoundryResponsesClient`, which selects the ephemeral adapter when no PromptAgent name is bound and the
agent-scoped adapter otherwise, then exposes that binder together with `AzurePromptAgentClient` as
`ProviderManagement.PromptAgents`. For `Hosted`, it constructs `HostedProvider` with `ProviderManagement.None` and does
not query model deployments. Once startup resolves effective Hosted mode, explicit `--model` is invalid; ACP rejects it
before opening the transport. Hosted does not require a process-default model, does not apply Prompt model-source
precedence, and ignores ambient model values. Finalization supplies canonical `hosted` for the shared non-null shape and
new Hosted session header, while a loaded valid Hosted binding may retain any legacy non-blank value as opaque
metadata. Neither is consumed as a client-selected execution model, and provider construction does not rewrite
persisted metadata. All configured clients use the project endpoint and credential captured by the project runtime.

Capabilities are behavioral, not aliases frontend code should reconstruct from `AgentKind`. `AgentLoop` consumes them
to enforce client compaction/model switching, local tools, and client/server history ownership. TUI and ACP composition
consume `ProviderRuntime` directly; `AgentKind` remains only the configuration and factory-selection input.

The Prompt provider receives its `FoundryResponsesClient` by injection, so tests can supply
[`MockFoundryResponsesClient`](../../src/test/kotlin/com/konductor/provider/inference/MockFoundryResponsesClient.kt).
**Scope guard:** retain the interface only for SDK containment, client lifecycle, preview churn, and deterministic
loop tests. The two production implementations represent the Foundry request shapes Konductor needs (ephemeral and
agent-scoped), not interchangeable service vendors.

## Client construction & auth (shared)

Both providers authenticate with `DefaultAzureCredential` (Entra ID) against a Foundry **project endpoint**
(`https://{resource}.ai.azure.com/api/projects/{project}`). The default AAD scope `https://ai.azure.com/.default`
is applied by the builder.

[`FoundryProjectRuntime`](../../src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt) is the focused,
process-scoped composition root for that endpoint and credential. Its finite typed surface is exactly project
`deployments`, project `connections`, and isolated `createProvider(configuration)`; it is not an arbitrary SDK
sub-client lookup or a general service locator. Project identity/catalog construction accepts only the resolved
endpoint and credential portions of bootstrap state, so an interactive Prompt may inspect deployments before the final
provider configuration exists. Production construction centralizes `AIProjectClientBuilder` and `AgentsClientBuilder`
in that boundary, then injects built SDK clients into the deployment, connection, Responses, PromptAgent, and Hosted
adapters. SDK models and clients stop at those composition/adapter files.

Deployments and Connections are GA surfaces and are built without preview opt-in. Ephemeral Responses and PromptAgent
management also use the GA builder path. Agent-scoped PromptAgent Responses and Hosted clients use
`allowPreview(true)` explicitly. A future client documented as a `Beta*Client` must use the builder's `.beta()` path
instead; `allowPreview(true)` is not a substitute.

The generated synchronous Deployments, Connections, and Agents clients are not closeable. The two project catalogs
are shared for the process lifetime, while every `createProvider` call builds fresh closeable OpenAI clients and fresh
Prompt binding or Hosted session state. `ProviderRuntime.close()` continues to close only its provider-owned OpenAI
resources. See [configuration.md](configuration.md) for env vars and credential setup.

The TUI consumes both SDK-free catalogs through its focused `FoundryDiscoveryCommand`: project deployments back
`/model list` and exact-name model validation, while project connections back the credential-safe `/connections`
listing. Production `TuiApp`/`ConversationController` construction must supply that command explicitly; there is no
empty-catalog fallback that can turn missing composition into a valid empty Foundry project. An embedder that
intentionally omits project discovery must select the explicit unavailable composition, whose localized command
responses are distinct from successful empty deployment and connection catalogs. A confirmed absent deployment is
rejected; a discovery outage—or intentionally unavailable discovery for an explicit model name—preserves free-text
selection with a warning that validation was skipped.

Those in-session catalog calls remain background TUI work. Separately, only a Prompt TUI that reaches startup without a
local or matched resumed model makes one pre-provider `ModelDeployment` inventory call and cannot continue if that call
fails; there is no existing usable session/provider to preserve. `--continue` with no workspace-scoped match is a new
session and therefore may take this route. The bootstrap selector consumes at most the first 2,000 canonical
application `CommandOption`s and visibly reports truncation, not provider or SDK types.

ACP has no project-discovery protocol surface. Its process project identity comes only from CLI, real process
environment, or global settings; launch-cwd project configuration is never composed. For Prompt `session/new`, model
source layers remain distinct until cwd trust permits project settings, then finalize as CLI > real process environment
> trusted per-session project > global. That precedence and its provenance tag are Prompt-only. Once process startup
resolves Hosted kind and rejects any explicit `--model`, Hosted `session/new` ignores process-environment,
trusted-project, and global model values and finalizes the effective configuration plus new header with canonical
`hosted`. Runtime preparation and model finalization precede header commit for either kind, with cleanup so failure
leaves no local or Hosted remote orphan. For `session/load`, strict persisted kind must match process kind in both
directions before trust/project merge or provider construction; a matching loaded blank/corrupt model also fails
without ambient substitution. Azure SDK types remain confined to the project adapters and composition boundary; none
reach bootstrap/TUI option state, conversation code, `TuiApp`, or `AppState`.

> **Foundry v2 naming:** older Assistants-era material may refer to Threads, Messages, Runs, and Assistants. The v2
> surface uses Conversations, Items, Responses, and Agent Versions on the `/openai/v1/` routes. Konductor uses the
> v2 names throughout.

### Client ownership — use the closeable OpenAI client

Konductor owns the blocking `OpenAIClient` returned by `buildOpenAIClient()` and closes it with the provider. Azure
Agents 2.2.0's `ResponsesClient` and `ResponsesAsyncClient` wrappers both hide/discard the underlying closeable OpenAI
client; synchronous wrapper streaming also hides the response stream's close handle. The internal Foundry call seam
remains coroutine-shaped: blocking work/stream iteration runs on `Dispatchers.IO`, and direct `StreamResponse.use`
closes the stream when collection unwinds, though a blocked socket read may delay cancellation. The
[2.2.0 convenience API evaluation](../foundry-responses-evaluation.md) therefore retains direct OpenAI-client access
until those lifecycle limitations are resolved and verified in a later SDK version.

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
through the flow, and `StreamResponse.use` closes the in-flight stream when collection unwinds; a blocked iterator may
not observe cancellation until I/O completes. The interface also retains a direct non-streaming `respond(...)` helper
for focused adapter use, but `PromptProvider` production turns use `respondStreaming(...)`.

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
`ToolExecutor` (cwd-scoped), model selection (while no PromptAgent is bound), and compaction are all unchanged. A PromptAgent only supplies the server-side
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
([sessions.md](sessions.md)); empty ⇒ ephemeral (the default). In this bootstrap slice, a PromptAgent binding does not
supply the local fallback model: Konductor does not fetch agent-definition metadata before provider construction, and
shared context/session/compaction state still requires a model. A missing fallback therefore follows the same TUI
inventory resolution (or ACP explicit-model requirement) as ephemeral Prompt, even though the bound definition owns the
actual service request model. Compaction is unaffected — the baked instructions/tool declarations are fixed server-side
overhead counted in `Usage.totalTokens` but outside the client transcript ([compaction.md](compaction.md)). Sharing a
configured agent across clients is a side-benefit.

## Related docs

[architecture.md](architecture.md) · [hosted-agents.md](hosted-agents.md) · [agent-context.md](agent-context.md) ·
[tools.md](tools.md) · [compaction.md](compaction.md) · [configuration.md](configuration.md)

**Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/` — `CreateResponse.java`,
`FunctionCallSync.java`, `CreateResponseWithConversation.java`.
