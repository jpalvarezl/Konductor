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
([tools.md](tools.md)). `AgentProvider` is the **loop-ownership** seam; the separate
[`InferenceClient`](architecture.md#two-axes-two-seams) **vendor** seam (one model call, neutral types) sits
*beneath* the Prompt path and is where all SDK types are confined.

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
        AgentKind.Prompt -> PromptProvider(AzureResponsesInferenceClient(cfg))
        AgentKind.Hosted -> HostedProvider(cfg)
    }
}
```

The Prompt provider takes its vendor dependency by injection (the `InferenceClient`), so a fake can be supplied in
tests and a different vendor's client swapped in later. **Scope guard:** one `InferenceClient` interface + one
Azure implementation. No vendor registry, no config-driven vendor selection, no OpenAI/Anthropic stubs — those are
deferred ([future.md](../future.md)). The seam exists for the SDK chokepoint and loop testability, not for a vendor
matrix we don't have.

## Client construction & auth (shared)

Both providers authenticate with `DefaultAzureCredential` (Entra ID) against a Foundry **project endpoint**
(`https://{resource}.ai.azure.com/api/projects/{project}`). The default AAD scope `https://ai.azure.com/.default`
is applied by the builder.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
val builder = AgentsClientBuilder()
    .endpoint(cfg.projectEndpoint)     // FOUNDRY_PROJECT_ENDPOINT
    .credential(credential)

val responses: ResponsesAsyncClient = builder.buildResponsesAsyncClient()   // Prompt (see "Sync vs async" below)
// Hosted also uses builder.allowPreview(true).buildAgentsClient() and buildAgentScopedOpenAIClient(...)
```

`azure-ai-projects` offers the same via `AIProjectClientBuilder(...).buildOpenAIClient()`; Konductor standardizes
on `AgentsClientBuilder` because it exposes both `buildResponsesAsyncClient()` and `buildAgentScopedOpenAIClient()`.
See [configuration.md](configuration.md) for env vars and credential setup.

### Sync vs async client — use async

`AgentsClientBuilder` exposes both `buildResponsesClient()` (blocking) and `buildResponsesAsyncClient()`
(Project Reactor). Konductor uses the **async** client, confined — like all SDK types — to
`AzureResponsesInferenceClient`. The choice is invisible above the `InferenceClient` seam (`respond` is `suspend`,
`respondStreaming` returns `Flow`), so it is reversible in a single class — but async is the better fit:

- **Coroutine-native.** `Mono<Response>.awaitSingle()` and `Flux<ResponseStreamEvent>.asFlow()` bridge directly to
  the `suspend`/`Flow` seam; the blocking client would need `withContext(Dispatchers.IO) { … }` wrappers.
- **Cancellation (the M6 `Esc` goal).** Cancelling the turn coroutine disposes the Reactor subscription and
  **aborts the in-flight HTTP call**. The blocking client parks in an OkHttp `execute()` that coroutine
  cancellation cannot interrupt, so a long generation runs to completion (still billing tokens) before the result
  is discarded.
- **Streaming.** `createStreamingAzureResponse` returns `Flux<ResponseStreamEvent>` → `asFlow()`: one line,
  backpressure-aware. The blocking return (`IterableStream`) means parking a pool thread and hand-rolling a channel.

The sync-vs-async scaling tradeoff is moot here (single user, one turn in flight), so async's only real cost is the
small `kotlinx-coroutines-reactor` bridge dependency (for `awaitSingle()` / `asFlow()`). Both clients wrap
**openai-java** (`com.openai.services.blocking.ResponseService` / `…async.ResponseServiceAsync`).

## Prompt provider

A **Prompt agent** is a model deployment + system instructions + client-side function tools. `PromptProvider`
owns the tool loop but is **vendor-neutral**: it delegates each individual model call to an
[`InferenceClient`](architecture.md#two-axes-two-seams), executes any requested tools locally, feeds the outputs
back, and repeats until the model produces a final answer. All SDK contact lives in the inference client
([below](#azure-inference-client-the-only-sdk-aware-class)).

### Request shape

Konductor re-sends the **reconstructed transcript** as history every turn (never `previousResponseId` /
`Conversation`), so client-side compaction stays authoritative — see the multi-turn decision in
[index.md](../index.md). The provider passes that history in a neutral `InferenceRequest`; mapping it to SDK input
items is the inference client's job (below).

### The harness-owned loop (vendor-neutral)

`PromptProvider` drives the loop but talks only to [`InferenceClient`](architecture.md#two-axes-two-seams) — no SDK
types appear here, so it is unit-testable with a fake client:

```kotlin
class PromptProvider(private val inference: InferenceClient) : AgentProvider {
    override val kind = AgentKind.Prompt

    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        val history = request.history.toMutableList()
        while (true) {
            val resp = inference.respond(
                InferenceRequest(
                    model = request.context.model,
                    systemPrompt = request.context.systemPrompt,
                    history = history,
                    tools = request.context.tools,
                    temperature = request.context.temperature,
                ),
            )
            resp.usage?.let { emit(AgentEvent.UsageReported(it)) }

            if (resp.toolCalls.isEmpty()) {                   // final answer
                emit(AgentEvent.TurnCompleted(resp.toAssistantEntry()))
                return@flow
            }

            for (call in resp.toolCalls) {                    // service each requested tool
                emit(AgentEvent.ToolCallStarted(call))
                val result = tools.execute(call)
                emit(AgentEvent.ToolCallCompleted(call, result))
                history += ToolCallEntry(call = call, /* id/parentId/timestamp */)
                history += ToolResultEntry(result = result, /* id/parentId/timestamp */)
            }
        }
    }

    override suspend fun close() = inference.close()
}
```

The loop appends `ToolCall`/`ToolResult` entries to the working history and re-requests until the model returns a
final answer. Everything vendor-specific — serializing history to SDK input items, submitting tool outputs,
reading usage — lives behind `inference.respond(...)`.

### Azure inference client (the only SDK-aware class)

`AzureResponsesInferenceClient` implements `InferenceClient` and is the **sole owner of SDK types** (`com.azure...`
/ `com.openai...`). Nothing above it imports the SDK.

**Map `InferenceRequest` → `ResponseCreateParams`** (the former `serializeHistory` / `toFunctionTool`):

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
    FunctionTool(name, parametersSchema.toBinaryData(), /* strict = */ true)
        .setDescription(description)
```

**Call and map the response back to `InferenceResponse`.** There is **no auto tool-loop helper** in the SDK, so
each `respond(...)` makes exactly one model call and returns the neutral shape the provider loops over:

```kotlin
override suspend fun respond(request: InferenceRequest): InferenceResponse {
    val response = responses.createAzureResponse(AzureCreateResponseOptions(), buildParams(request))
        .awaitSingle()                                   // Mono<Response> -> suspend
    val toolCalls = response.output().mapNotNull { it.functionCall().orElse(null) }
        .map { ToolCall(it.callId(), it.name(), it.arguments()) }
    val usage = response.usage().map { it.toUsage() }.orElse(null)
    return InferenceResponse(response.outputText(), toolCalls, usage)
}
```

Key SDK types: `ResponseOutputItem.functionCall()` (`callId()`, `name()`, `arguments()`),
`ResponseFunctionToolCallOutputItem(callId, output)`, `Response.usage()` → `ResponseUsage`
(`inputTokens()/outputTokens()/totalTokens()`). These types originate in **openai-java** (`com.openai...`) and are
wrapped by the Azure `ResponsesClient`.

### Streaming variant (M6)

Streaming lives in the inference client too — `respondStreaming` swaps the single call for the streaming API and
emits neutral `InferenceChunk`s that `PromptProvider` relays as `AgentEvent.TextDelta`s. The async client's
`Flux<ResponseStreamEvent>` maps straight onto the seam's `Flow` via `asFlow()`:

```kotlin
override fun respondStreaming(request: InferenceRequest): Flow<InferenceChunk> =
    responses.createStreamingAzureResponse(AzureCreateResponseOptions(), buildParams(request))
        .asFlow()                                        // Flux<ResponseStreamEvent> -> Flow
        .mapNotNull { ev -> ev.outputTextDelta()?.let { InferenceChunk.TextDelta(it) } }
        // functionCallArgumentsDelta chunks accumulate tool-call args (M6 detail)
```

Collecting the flow lazily subscribes; cancelling the collector disposes the subscription and aborts the stream
(the M6 `Esc` path). Streaming is the target UX (M6); the non-streaming `respond(...)` above is the M1–M2 starting
point ([implementation-roadmap.md](../implementation-roadmap.md)). The blocking client's
`IterableStream<ResponseStreamEvent>` equivalent exists but needs a dedicated thread and coarser cancellation.

### Usage & the context window

Every `UsageReported` event updates the [`ContextWindowTracker`](architecture.md#compaction-integration). When
`totalTokens` approaches the model's window, the agent loop compacts before the next turn
([compaction.md](compaction.md)).

## Registering a Prompt agent (optional)

For the client-managed loop you only need the model deployment. If you instead want a **named, versioned** Prompt
agent stored in Foundry (so `instructions`/tools live server-side), create one with `PromptAgentDefinition`:

```kotlin
val def = PromptAgentDefinition(cfg.model)
    .setInstructions(context.systemPrompt)
    .setTemperature(0.2)
    .setTools(context.tools.map { it.toFunctionTool() })
agentsClient.createAgentVersion(agentName, CreateAgentVersionInput(def))
```

This is optional for Konductor's Prompt provider and mainly useful for sharing a configured agent across clients.

## Related docs

[architecture.md](architecture.md) · [hosted-agents.md](hosted-agents.md) · [agent-context.md](agent-context.md) ·
[tools.md](tools.md) · [compaction.md](compaction.md) · [configuration.md](configuration.md)

**Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/` — `CreateResponse.java`,
`FunctionCallSync.java`, `CreateResponseWithConversation.java`.
