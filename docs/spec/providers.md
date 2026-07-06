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
([tools.md](tools.md)).

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
        AgentKind.Prompt -> PromptProvider(cfg)
        AgentKind.Hosted -> HostedProvider(cfg)
    }
}
```

## Client construction & auth (shared)

Both providers authenticate with `DefaultAzureCredential` (Entra ID) against a Foundry **project endpoint**
(`https://{resource}.ai.azure.com/api/projects/{project}`). The default AAD scope `https://ai.azure.com/.default`
is applied by the builder.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
val builder = AgentsClientBuilder()
    .endpoint(cfg.projectEndpoint)     // FOUNDRY_PROJECT_ENDPOINT
    .credential(credential)

val responses: ResponsesClient = builder.buildResponsesClient()          // Prompt
// Hosted also uses builder.allowPreview(true).buildAgentsClient() and buildAgentScopedOpenAIClient(...)
```

`azure-ai-projects` offers the same via `AIProjectClientBuilder(...).buildOpenAIClient()`; Konductor standardizes
on `AgentsClientBuilder` because it exposes both `buildResponsesClient()` and `buildAgentScopedOpenAIClient()`.
See [configuration.md](configuration.md) for env vars and credential setup.

## Prompt provider

A **Prompt agent** is a model deployment + system instructions + client-side function tools. The provider owns the
inference loop: it calls the Responses API, executes any requested tools locally, feeds the outputs back, and
repeats until the model produces a final answer.

### Request shape

Konductor re-sends the **reconstructed transcript** as `input` every turn (never `previousResponseId` /
`Conversation`), so client-side compaction stays authoritative — see the multi-turn decision in
[index.md](../index.md).

```kotlin
val params = ResponseCreateParams.builder()
    .model(cfg.model)                       // FOUNDRY_MODEL_NAME
    .instructions(context.systemPrompt)     // preamble (agent-context.md)
    .input(serializeHistory(history))       // user/assistant/tool entries as Responses input items
    .apply { context.tools.forEach { addTool(it.toFunctionTool()) } }
    .apply { context.temperature?.let { temperature(it) } }

val response = responses.createAzureResponse(AzureCreateResponseOptions(), params)
```

`serializeHistory` maps entries → Responses **input items**: `UserEntry`/`AssistantEntry` → messages,
`ToolCallEntry` → a function-call item, `ToolResultEntry` → a `ResponseFunctionToolCallOutputItem` (matched by
`callId`).

### Tool definition

Konductor `ToolSpec`s become SDK `FunctionTool`s:

```kotlin
fun ToolSpec.toFunctionTool(): FunctionTool =
    FunctionTool(name, parametersSchema.toBinaryData(), /* strict = */ true)
        .setDescription(description)
```

### The harness-owned loop

There is **no auto tool-loop helper** in the SDK — Konductor drives it:

```kotlin
override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
    var input = serializeHistory(request.history)
    while (true) {
        val response = responses.createAzureResponse(AzureCreateResponseOptions(),
            baseParams(request.context).input(input))

        val calls = response.output().mapNotNull { it.functionCall().orElse(null) }
        response.usage().ifPresent { emit(AgentEvent.UsageReported(it.toUsage())) }

        if (calls.isEmpty()) {                       // final answer
            emit(AgentEvent.TurnCompleted(response.toAssistantEntry()))
            return@flow
        }

        for (fc in calls) {                          // service each requested tool
            val call = ToolCall(fc.callId(), fc.name(), fc.arguments())
            emit(AgentEvent.ToolCallStarted(call))
            val result = tools.execute(call)
            emit(AgentEvent.ToolCallCompleted(call, result))
            input = input + fc.asInputItem() +
                ResponseFunctionToolCallOutputItem(fc.callId(), result.output).asInputItem()
        }
    }
}
```

Key SDK types: `ResponseOutputItem.functionCall()` (`callId()`, `name()`, `arguments()`),
`ResponseFunctionToolCallOutputItem(callId, output)`, `Response.usage()` → `ResponseUsage`
(`inputTokens()/outputTokens()/totalTokens()`). These types originate in **openai-java** (`com.openai...`) and are
wrapped by the Azure `ResponsesClient`.

### Streaming variant

For live output, swap the single call for the streaming API and emit deltas as they arrive:

```kotlin
val stream: IterableStream<ResponseStreamEvent> =
    responses.createStreamingAzureResponse(AzureCreateResponseOptions(), params)
for (ev in stream) {
    ev.outputTextDelta()?.let { emit(AgentEvent.TextDelta(it)) }
    ev.functionCallArgumentsDelta()?.let { /* accumulate tool-call args */ }
}
```

`ResponsesAsyncClient.createStreamingAzureResponse(...)` returns a `Flux<ResponseStreamEvent>` for coroutine
interop. Streaming is the target UX (M6); the non-streaming loop above is the M1–M2 starting point
([implementation-roadmap.md](../implementation-roadmap.md)).

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
