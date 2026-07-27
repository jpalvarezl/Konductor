# Providers

A **provider** is Konductor's adapter over one Foundry **agent kind**. All providers implement the same
[`AgentProvider`](architecture.md#the-agentprovider-seam) seam, so the agent loop and TUI are identical regardless
of kind. This doc covers the seam and the **Prompt provider**. The **Hosted provider** has its own doc:
[hosted-agents.md](hosted-agents.md).

> Code blocks are illustrative design sketches, not committed implementation.

## The seam recap

```kotlin
interface AgentProvider {
    val kind: AgentKind
    fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent>
    suspend fun close()
}
```

`runTurn` runs **one user turn to completion** and emits [`AgentEvent`](architecture.md#the-agentprovider-seam)s.
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

The provider is chosen from config ([configuration.md](configuration.md)) and built by a small factory. All
providers share the endpoint + credential.

```kotlin
object ProviderFactory {
    fun create(cfg: Config): AgentProvider = when (cfg.agentKind) {
        AgentKind.Prompt -> PromptProvider(
            SwitchableFoundryResponsesClient(
                factory = { name ->
                    if (name == null) EphemeralFoundryResponsesClient(cfg)
                    else PromptAgentFoundryResponsesClient(cfg, name)
                },
                initialAgent = cfg.promptAgentName,
            ),
        )
        AgentKind.Hosted -> HostedProvider(cfg)
    }
}
```

The Prompt provider receives its Foundry Responses call seam (`FoundryResponsesClient`) by injection so tests can
supply a fake. **Scope guard:** retain the interface only for SDK containment, client lifecycle, preview churn, and deterministic
loop tests. The two production implementations represent the Foundry request shapes Konductor needs (ephemeral and
agent-scoped), not interchangeable service vendors.

## Client construction & auth (shared)

Both providers authenticate with `DefaultAzureCredential` (Entra ID) against a Foundry **project endpoint**
(`https://{resource}.ai.azure.com/api/projects/{project}`). The default AAD scope `https://ai.azure.com/.default`
is applied by the builder.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
val builder = AgentsClientBuilder()
    .endpoint(cfg.projectEndpoint)     // FOUNDRY_PROJECT_ENDPOINT
    .credential(credential)

val ephemeral: OpenAIClient = builder.buildOpenAIClient()
val promptAgent: OpenAIClient = builder.allowPreview(true).buildAgentScopedOpenAIClient(agentName)
// Hosted also uses allowPreview(true).buildAgentsClient() plus its own agent-scoped client.
```

The current implementation constructs clients independently through `AgentsClientBuilder`. The accepted Foundry-first
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

`PromptProvider` drives the loop but talks only to
[`FoundryResponsesClient`](architecture.md#two-axes-two-seams) — no SDK types appear here, so it is unit-testable with
a fake client:

```kotlin
class PromptProvider(private val responsesClient: FoundryResponsesClient) : AgentProvider {
    override val kind = AgentKind.Prompt

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        val history = request.history.toMutableList()
        while (true) {
            val result = responsesClient.respond(
                FoundryResponsesRequest(
                    model = request.context.model,
                    systemPrompt = request.context.systemPrompt,
                    history = history,
                    tools = request.context.tools,
                    temperature = request.context.temperature,
                ),
            )
            result.usage?.let { emit(AgentEvent.UsageReported(it)) }

            if (result.toolCalls.isEmpty()) {                 // final answer
                emit(AgentEvent.TurnCompleted(result.toAssistantEntry()))
                return@flow
            }

            for (call in result.toolCalls) {                  // service each requested tool
                emit(AgentEvent.ToolCallStarted(call))
                val toolResult = tools.execute(call)
                emit(AgentEvent.ToolCallCompleted(call, toolResult))
                history += ToolCallEntry(call = call, /* id/parentId/timestamp */)
                history += ToolResultEntry(result = toolResult, /* id/parentId/timestamp */)
            }
        }
    }

    override suspend fun close() = responsesClient.close()
}
```

The loop appends `ToolCall`/`ToolResult` entries to the working history and re-requests until the model returns a
final answer. Everything Foundry-SDK-specific—serializing history to SDK input items, submitting tool outputs, and
reading usage—lives behind `responsesClient.respond(...)`.

### Foundry Responses adapters (the Prompt SDK boundary)

`EphemeralFoundryResponsesClient` implements the default ephemeral `FoundryResponsesClient` and owns its
Azure/OpenAI Responses types. `PromptAgentFoundryResponsesClient` is the sibling agent-scoped adapter for persisted
PromptAgents. Shared Responses/domain mapping lives in `ResponsesMapping.kt`; nothing above the Foundry Responses
seam imports AI SDK types.

**Map `FoundryResponsesRequest` → `ResponseCreateParams`** (the former `serializeHistory` / `toFunctionTool`):

```kotlin
val params = ResponseCreateParams.builder()
    .model(request.model)                       // FOUNDRY_MODEL_NAME
    .instructions(request.systemPrompt)         // preamble (agent-context.md)
    .input(serializeHistory(request.history))   // user/assistant/tool entries as Responses input items
    .apply { request.tools.forEach { addTool(it.toFunctionTool()) } }
    .apply { request.temperature?.let { temperature(it) } }
```

`serializeHistory` maps entries → Responses **input items**: `UserEntry`/`AssistantEntry` → messages,
`ToolCallEntry` → a function-call item, `ToolResultEntry` → a `ResponseFunctionToolCallOutputItem` (matched by
`callId`). `ToolSpec`s become SDK `FunctionTool`s:

```kotlin
fun ToolSpec.toFunctionTool(): FunctionTool =
    FunctionTool.builder()
        .name(name)
        .description(description)
        .parameters(parametersSchema)
        .strict(false)
        .build()
```

`strict=false` is intentional: the built-ins have optional properties that are absent from JSON Schema `required`,
which the strict Responses tool schema rejects.

**Call and map the response back to `FoundryResponsesResult`.** There is **no auto tool-loop helper** in the SDK, so
each `respond(...)` makes exactly one model call and returns the application result the provider loops over:

```kotlin
override suspend fun respond(request: FoundryResponsesRequest): FoundryResponsesResult {
    val response = client.responses().create(buildParams(request))
    val toolCalls = response.output().mapNotNull { it.functionCall().orElse(null) }
        .map { ToolCall(it.callId(), it.name(), it.arguments()) }
    val usage = response.usage().map { it.toUsage() }.orElse(null)
    return FoundryResponsesResult(response.outputText(), toolCalls, usage)
}
```

Key SDK types: `ResponseOutputItem.functionCall()` (`callId()`, `name()`, `arguments()`),
`ResponseFunctionToolCallOutputItem(callId, output)`, `Response.usage()` → `ResponseUsage`
(`inputTokens()/outputTokens()/totalTokens()`). These types originate in **openai-java** (`com.openai...`) and are
wrapped by the Azure `ResponsesClient`.

### Streaming variant

Streaming also lives in each Foundry Responses adapter — `respondStreaming` swaps the single call for the streaming
API and emits application `FoundryResponsesEvent`s that `PromptProvider` relays as `AgentEvent.TextDelta`s. The
adapter owns the closeable blocking `OpenAIClient`, iterates its `StreamResponse` on `Dispatchers.IO`, and maps the terminal
response to tool calls + usage:

```kotlin
override fun respondStreaming(request: FoundryResponsesRequest): Flow<FoundryResponsesEvent> = flow {
    client.responses().createStreaming(buildParams(request)).use { stream ->
        stream.stream().forEach { event -> emit(event.toFoundryResponsesEvent()) }
    }
}.flowOn(Dispatchers.IO)
```

Cancellation propagates through the flow and closes/interrupts the in-flight stream. Transient failures before any
model output are retried with capped exponential backoff; once output has started, failures are surfaced rather than
replaying a partial answer.

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

**Create a version** from the current context (name resolved from config or `/agent`, below):

```kotlin
val def = PromptAgentDefinition(cfg.model)
    .setInstructions(context.baseSystemPrompt)          // STABLE base prompt only (see below)
    .setTemperature(cfg.temperature)
    .setTools(context.tools.map { it.toFunctionTool() })
agentsClient.createAgentVersion(agentName, CreateAgentVersionInput(def))
```

**Invoke it per turn** — build an agent-scoped client and send an **input-only** Responses request. The persisted
agent supplies model, instructions, and tool declarations; the service rejects those fields when sent again:

```kotlin
val client = AgentsClientBuilder()
    .endpoint(projectEndpoint)
    .credential(credential)
    .allowPreview(true)
    .buildAgentScopedOpenAIClient(agentName)

val params = ResponseCreateParams.builder()
    .input(dynamicPreambleDeveloperItem + serializeHistory(request.history))
    .build() // deliberately no model, instructions, or tools
client.responses().create(params)
```

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
