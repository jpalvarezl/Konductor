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
`AgentContextFactory`, or an `AcpSessionRuntime`. `ConfigurationAcpSessionRuntimeFactory` is the per-session resolution
boundary and may retain partial process candidates until `session/new` or `session/load` supplies the authoritative cwd
and, for load, header. No temporary provider is created for inventory lookup or the pre-provider TUI selector.

Before TUI `createProvider`, a selected resume/continue session's strict schema/binding kind must equal the effective
TUI kind. Prompt-over-Hosted and Hosted-over-Prompt both fail without discovery, provider construction, service
operations, or writes; this slice neither switches provider kind nor migrates/relabels session metadata. ACP load uses
its separate two-phase rule: persisted model/kind/binding overlay fresh eligible sources and determine the final
provider rather than being cross-validated against process configuration.

`createProvider` delegates agent-kind selection to
[`ProviderFactory`](../../src/main/kotlin/com/konductor/provider/ProviderFactory.kt) and returns a `ProviderRuntime`
rather than a bare provider. The runtime exposes the provider's explicit capability contract and a sealed
`ProviderManagement` surface. For `Prompt`, the factory constructs `PromptProvider` with
`SwitchableFoundryResponsesClient`, which selects the ephemeral adapter when no PromptAgent name is bound and the
agent-scoped adapter otherwise, then exposes that binder together with `AzurePromptAgentClient` as
`ProviderManagement.PromptAgents`. For `Hosted`, it constructs `HostedProvider` with `ProviderManagement.None` and does
not query model deployments. Before ACP transport startup, only an explicit process-level `--agent-kind hosted`
together with `--model` is rejected. If eligible session-cwd sources make a new ACP session Hosted, an explicit
`--model` is rejected during `session/new`; ambient environment/dotenv/settings model values are ignored. ACP load uses
the persisted kind and does not apply this new-session conflict. Hosted does not require a process-default model or
apply Prompt model-source precedence. Finalization supplies canonical `hosted` for the shared non-null shape and
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

[`FoundryProjectRuntime`](../../src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt) is the focused
composition root for one effective endpoint and credential. Main owns one for the cwd-bound TUI; its typed deployment
catalog may resolve an absent Prompt model before final provider construction. ACP owns one per independently resolved
session configuration rather than leaking launch-cwd project inputs between sessions. Its finite typed surface is
exactly project `deployments`, project `connections`, and isolated `createProvider(configuration)`; it is not an
arbitrary SDK sub-client lookup or a general service locator. Production construction centralizes
`AIProjectClientBuilder` and `AgentsClientBuilder` in that boundary, then injects built SDK clients into deployment,
connection, Responses, PromptAgent, and Hosted adapters. SDK models and clients stop at those files.

Deployments and Connections are GA surfaces and are built without preview opt-in. Ephemeral Responses and PromptAgent
management also use the GA builder path. Agent-scoped PromptAgent Responses and Hosted clients use
`allowPreview(true)` explicitly. A future client documented as a `Beta*Client` must use the builder's `.beta()` path
instead; `allowPreview(true)` is not a substitute.

The generated synchronous Deployments, Connections, and Agents clients are not closeable. The TUI project catalogs
live for its runtime; ACP has no catalog protocol surface and does not share session-specific project composition.
Every `createProvider` call builds fresh closeable OpenAI clients and fresh Prompt binding or Hosted session state.
`ProviderRuntime.close()` continues to close its provider-owned OpenAI resources; the owner also releases any
session-scoped ACP composition. See [configuration.md](configuration.md) for env vars and credential setup.

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
fails; there is no existing usable session/provider to preserve. `--continue` with no exact-canonical-cwd match is a new
session and therefore may take this route. The bootstrap selector consumes at most the first 2,000 canonical
application `CommandOption`s and visibly reports truncation, not provider or SDK types.

ACP has no project-discovery protocol surface and never composes launch-cwd project configuration. For Prompt
`session/new`, source layers remain distinct until requested-cwd trust admits project inputs, then model finalization is
CLI > real process environment > trusted cwd dotenv > trusted project settings > global. That precedence and its
process-local provenance tag are Prompt-only. An explicit process-level Hosted selection plus `--model` fails before
transport; otherwise `session/new` resolves final kind from eligible sources and rejects explicit `--model` if that kind
is Hosted. It then ignores ambient model values and finalizes the configuration/header with canonical `hosted`.
Runtime preparation and model finalization precede one `persistNew`, with cleanup so failure leaves no local
or remote orphan. For `session/load`, eligible fresh sources are parsed first and exact persisted model/kind/binding is
overlaid before final validation/provider construction; persisted kind is not compared with a process-scoped kind. A
loaded blank/corrupt model fails without ambient substitution or discovery. Azure SDK types remain confined to project
adapters and composition; none reach bootstrap/TUI option state, conversation code, `TuiApp`, or `AppState`.

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

Prompt assembly has one logical order: **stable base → configured append → rendered path-bearing context block →
environment header → tools**. For the default ephemeral adapter, the first four components are the `instructions`
string and tools remain the structured request tool field. Both Prompt transports use the exact record renderer,
attribute escaping, empty/whitespace behavior, and literal-`"\n\n"` component join in
[agent-context.md](agent-context.md#assembly-order--precedence). Context discovery is trust-independent; its bounds and
observable-race guarantee are defined in [agent-context.md](agent-context.md#context-files). `--no-context-files`
removes only the complete rendered block.

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

### Context-overflow classification

Context overflow is a distinct, non-transient Prompt failure. With the pinned Azure Agents 2.2.0 composition, both
Prompt adapters call the Azure-built openai-java 4.14.0 `OpenAIClient`; the concrete service failure at this seam is
`OpenAIServiceException`. The adapters classify it as overflow only when both conditions hold:

1. `statusCode() == 400`; and
2. the parsed structured accessor `code()` is present and exactly, case-sensitively
   `context_length_exceeded`.

No bare-status, message substring, model-name, token estimate, malformed-body, or missing-code fallback is allowed. A
400 with another code remains its original fatal error, and the same code on 429/5xx is not overflow. The adapter seam
expects `OpenAIServiceException` directly. A defensive, identity-cycle-safe cause walk handles framework or coroutine
wrappers without an arbitrary depth limit and checks cancellation across the chain before classifying the exact service
failure. Unknown failures and future payload variants fail closed. The current Responses path does not emit Azure Core
`HttpResponseException`, so this contract does not add a speculative second payload parser merely because Azure Core is
present elsewhere in the application.

Both `EphemeralFoundryResponsesClient` and `PromptAgentFoundryResponsesClient` apply this mapping to unary and streaming
calls. Classification precedes transient handling: an overflow never enters exponential backoff and never emits
`FoundryResponsesEvent.Retrying`. This does not add transient retries to the PromptAgent adapter. The adapters replace
the SDK exception with an SDK-free `PromptContextOverflowException`, retaining the original only as a diagnostic
cause. `PromptProvider` carries that application type in `AgentEvent.Failed`; code above `provider/inference` neither
imports nor interrogates OpenAI/Azure error types or payloads.

The application type is a signal, not permission to replay. `AgentLoop` alone combines it with client-history
capability and the events already observed in this turn, then applies the one-cycle safety and persistence contract in
[compaction.md](compaction.md#reactive-context-overflow-recovery). Hosted makes no local Responses call, owns no client
context, and is excluded even if a faulty test provider emits the typed error.

The deterministic body corpus and status matrix for this chokepoint live in the
[I095 delivery packet](../iterations/I095-prompt-context-overflow-recovery.md#offline-classifier-fixtures). If a future
SDK/service version changes the concrete structured contract, update this adapter classifier and its fixtures
deliberately; do not move SDK inspection into the provider loop or agent loop.

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
creates a version whose instructions contain the current stable base followed by the resolved configured append, and
whose definition contains the local tool declarations. This continues the existing Konductor PromptAgent shape, so
legacy and newly created versions both retain base + append server-side.

[`PromptAgentFoundryResponsesClient`](../../src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt)
sends an **input-only** Responses request. The persisted agent supplies model, baked instructions, and tool declarations;
the service rejects those fields when sent again. The adapter prepends the dynamic preamble—the exact same rendered path-bearing context block used by ephemeral Prompt,
then environment header (cwd/os/shell/date)—as one developer input item, serializes the reconstructed history, and
consumes the stream through the same `FoundryResponsesEvent.TextDelta` / terminal `Completed` mapping as the ephemeral
path. `--no-context-files` removes only the rendered context block. The transport-role difference from ephemeral `instructions` is
intentional.

The pinned Azure Agents 2.2.0 raw-client path constructs this endpoint with
`AgentsClientBuilder.buildAgentScopedOpenAIClient(name)`. It accepts only an agent name, not an exact version. Although
the lifecycle API can inspect version details, a separate inspection cannot pin the version later selected by the
name-scoped Responses call and can race a new version. Konductor therefore does not add or inspect layout metadata,
classify versions, compare frozen instructions, or warn based on such a comparison. Configured, `/agent use`, newly
created, and resumed bindings remain name-scoped. A base/append configuration change affects ephemeral requests at
once, but a persisted PromptAgent retains its baked values until the user explicitly creates a new version.

This contract preserves exactly one append for old and new PromptAgents while allowing path-attributed
context/environment to remain cwd-correct. Exact rendering and joining rules live in
[agent-context.md](agent-context.md#persisted-agents-stable-vs-dynamic-preamble). Context-file discovery and the shared
dynamic assembly are implemented for both TUI and ACP. This Foundry PromptAgent stable/dynamic split is an intentional
Konductor difference from pi 0.82.1 and is retained because the server-side definition rejects per-request instructions
and tools.

**Selection, session & lifecycle.** The agent name comes from config (`KONDUCTOR_PROMPT_AGENT_NAME` / `provider.promptAgentName`,
[configuration.md](configuration.md)) or the [`/agent`](tui.md#slash-commands) TUI command (`use` / `create`). The
resolved agent name is persisted in the session header and rebound through the same name-scoped endpoint on resume;
no version or layout field is added to the v1 session header ([sessions.md](sessions.md)); empty ⇒ ephemeral (the
default). A PromptAgent binding does not satisfy local model resolution: Konductor does not inspect definition metadata
to infer a model, so an absent local model follows the same TUI deployment bootstrap or ACP `session/new` failure as
ephemeral Prompt even though the definition owns the service-side request model. Compaction is unaffected — the baked
instructions/tool declarations are fixed server-side overhead counted in `Usage.totalTokens` but outside the client
transcript ([compaction.md](compaction.md)). Sharing a configured agent across clients is a side-benefit.

### Binding prepare and commit

`PromptAgentBinder.prepareBinding(rawName)` trims the name, maps blank to `null`, and constructs an unpublished
candidate Responses delegate without mutating the committed `(agentName, delegate)` holder, which remains routable. Its
one-shot prepared handle exposes that normalized name, can be aborted with best-effort candidate cleanup, and commits
through one non-throwing immutable-holder swap. Preparing the current normalized name is a
no-allocation/no-close operation. Superseded-client close occurs only after commit and is best-effort, so close failure
cannot turn an effective provider swap into a reported rejection.

For an already published session, `AgentLoop` serializes the prepared handle with turns and session changes, persists
immutable metadata first, then commits provider and live session state. Provider commit performs no allocation, SDK or
service request, callback, or fallible cleanup. This is a narrow PromptAgent contract, not a provider transaction
framework. Exact ordering and persistence/restart behavior live in
[sessions.md](sessions.md#persisted-agents--resume).

Resume prepares the exact persisted name. An omitted `promptAgentName` header field decodes to in-memory `null` and
prepares the ephemeral adapter. It does not use `listAgents` as a binding precondition or silently unbind when a list
snapshot omits the name: listing can race deletion or version
creation and cannot prove which version the later name-scoped Responses call selects. A deleted or unusable name fails
when preparation or an authoritative service request proves it unusable; Konductor keeps the persisted name for retry
instead of inferring or recreating a version.

`/agent create` remains two operations: Foundry version creation followed by local name adoption. The returned version
may be displayed as creation information but is never converted into execution identity. A create transport failure may
be ambiguous and leaves the current local binding unchanged; a successfully created version whose local adoption later
fails is not deleted and is reported as created but not adopted. Retrying `/agent use <name>` adopts by name and still
does not promise which exact version Azure Responses selects.

## Related docs

[architecture.md](architecture.md) · [hosted-agents.md](hosted-agents.md) · [agent-context.md](agent-context.md) ·
[tools.md](tools.md) · [compaction.md](compaction.md) · [configuration.md](configuration.md)

**Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/` — `CreateResponse.java`,
`FunctionCallSync.java`, `CreateResponseWithConversation.java`.
