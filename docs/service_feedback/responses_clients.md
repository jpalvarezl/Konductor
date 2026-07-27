# Responses convenience clients — SDK feedback

Source-verified feedback from evaluating `ResponsesClient` and `ResponsesAsyncClient` in
`com.azure:azure-ai-agents` **2.2.0** (openai-java **4.14.0**) for Konductor
[#60](https://github.com/jpalvarezl/Konductor/issues/60). See the full
[recommendation matrix](../foundry-responses-evaluation.md).

Legend: **Impact** = what it costs Konductor · **Workaround** = what Konductor does today · **Suggestion** = the SDK
change that would remove the friction. These findings are verified from Azure SDK commit `ee28e96b521`; this spike did
not add live probes.

## 1. The Responses wrappers discard the closeable root OpenAI client

`AgentsClientBuilder.buildResponsesClient()` creates an openai-java `OpenAIClient`, but `ResponsesClient` stores only
`openAIClient.responses()` as a `ResponseService`. `buildResponsesAsyncClient()` and `ResponsesAsyncClient` do the
same with `OpenAIClientAsync` and `ResponseServiceAsync`. Neither wrapper implements or exposes `close()`, and neither
service can recover the root client.

In the embedded openai-java 4.14.0 dependency, both root client types expose `close()`. Their `ClientOptions` own a
sleeper and dedicated cached stream-handler executor, which client closure shuts down. The Azure
`HttpClientHelper.HttpClientWrapper.close()` is a no-op, so this finding is specifically about deterministic
OpenAI executor/sleeper cleanup, not closure of the Azure pipeline's transport. The wrappers hide the close operation
even though the resource-owning root exists during construction.

- **Impact:** Konductor cannot give a `ResponsesClient` or `ResponsesAsyncClient` the same explicit per-session
  lifecycle as its other inference resources. In particular, using async streaming can start the root client's
  stream-handler executor without any wrapper API to shut it down; cleanup is deferred to the underlying SDK's
  idle/phantom-reachability behavior rather than the application lifecycle.
- **Workaround:** build and retain `AgentsClientBuilder.buildOpenAIClient()` or
  `buildAgentScopedOpenAIClient(agentName)`, call `responses()` directly, and call `OpenAIClient.close()` when the
  TUI/ACP session closes.
- **Suggestion:** have both Responses wrappers retain the root client and implement `AutoCloseable`/`close()`, or add
  builder overloads that wrap a caller-owned OpenAI client without taking away its close handle. Document ownership
  explicitly.

## 2. Synchronous streaming hides the only close handle

`ResponsesClient.createStreamingAzureResponse(AzureCreateResponseOptions, ResponseCreateParams.Builder)` converts a
closeable openai-java `StreamResponse<ResponseStreamEvent>` to `IterableStream<ResponseStreamEvent>` through
`StreamingUtils.toIterableStream`. The utility captures the `StreamResponse` privately and closes it only when
`Iterator.hasNext()` observes normal exhaustion. Azure Core's `IterableStream` has no close operation.

The async counterpart is better: `ResponsesAsyncClient.createStreamingAzureResponse(...)` returns a `Flux`, and
`StreamingUtils.toFlux` installs `asyncStream.close()` with `sink.onDispose`.

- **Impact:** a synchronous consumer that exits early, is cancelled while iterating, or fails before exhaustion cannot
  explicitly close the HTTP stream through the returned type. This is unsuitable for Konductor's structured turn
  lifecycle. It also prevents claiming prompt cancellation is prompt resource cleanup; a blocked iterator may remain
  blocked until I/O completes.
- **Workaround:** retain the direct openai-java `StreamResponse` and use `try`/`finally` (`use` in Kotlin) so stack exit
  closes it. Konductor does not adopt Reactor solely to avoid the sync wrapper defect while #1 remains.
- **Suggestion:** return an `AutoCloseable` streaming type, make the returned iterable closeable, or add a documented
  cancellation/close handle that always closes the underlying `StreamResponse`. Add a test for early termination,
  not only full stream consumption.

## 3. Typed Azure calls have no per-call `RequestOptions` overload

The typed methods
`ResponsesClient.createAzureResponse(AzureCreateResponseOptions, ResponseCreateParams.Builder)` and
`createStreamingAzureResponse(...)` have no overload accepting openai-java `RequestOptions`; the async methods have
the same gap. The only wrapper methods that accept `RequestOptions` are raw `BinaryData` protocol methods.

- **Impact:** a caller cannot combine typed `agent_reference` / `structured_inputs` with a per-call timeout or response
  validation setting through the convenience API. It must choose client-wide policy, untyped raw JSON, or direct
  openai-java service calls with manually flattened Azure properties.
- **Workaround:** Konductor keeps timeout/retry policy at its adapter and Azure pipeline boundaries and retains the
  direct OpenAI client. It does not currently require a typed per-call timeout.
- **Suggestion:** add sync and async overloads for both unary and streaming typed methods that accept openai-java
  `RequestOptions`, matching `ResponseService.create` and `createStreaming`.

## 4. `structured_inputs` repeats the `Map<String, BinaryData>` encoding trap

`AzureCreateResponseOptions.setStructuredInputs(Map<String, BinaryData>)` writes each `BinaryData` value directly
through `JsonWriter`. The SDK sample correctly uses `BinaryData.fromObject(...)`. A caller that uses
`BinaryData.fromString(jsonText)` for an object or array gets a JSON string value rather than the intended structured
value—the same source-verified representation trap documented for `FunctionTool` in
item 1 of [`prompt_agents.md`](prompt_agents.md).

- **Impact:** prompt-template/tool bindings can receive a string instead of an object or array with no type-level
  warning. Konductor has no structured-input request field today, but this is a trap for the evaluated follow-up.
- **Workaround:** if structured inputs are added, convert values with `BinaryData.fromObject` and preserve arbitrary
  JSON values at the adapter boundary.
- **Suggestion:** accept a JSON-value/`JsonSerializable` type that distinguishes structured JSON from text, or add
  typed scalar/object helpers and document `fromObject` in `setStructuredInputs` Javadoc.

## What worked well

- `AzureCreateResponseOptions` cleanly flattens `agent_reference` and `structured_inputs` into the OpenAI request.
- `AgentReference` supports explicit version selection per request.
- Pinned sync and async streaming tests cover text, function calls, Azure tools, and structured inputs.
- Async Flux disposal closes the individual `AsyncStreamResponse`.
- `AgentsClientBuilder` routes both wrappers and direct OpenAI clients through the same Azure pipeline and sets
  openai-java retries to zero, avoiding duplicate SDK-layer retry loops.

## Pinned source

- [`ResponsesClient`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/ResponsesClient.java#L33-L91)
- [`ResponsesAsyncClient`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/ResponsesAsyncClient.java#L33-L91)
- [`AgentsClientBuilder`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/AgentsClientBuilder.java#L380-L502)
- [`StreamingUtils`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/implementation/StreamingUtils.java#L27-L104)
- [`HttpClientHelper`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/implementation/http/HttpClientHelper.java#L49-L78)
- [`AzureCreateResponseOptions`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/models/AzureCreateResponseOptions.java#L20-L102)
- [openai-java `ClientOptions`](https://github.com/openai/openai-java/blob/v4.14.0/openai-java-core/src/main/kotlin/com/openai/core/ClientOptions.kt#L505-L614)
