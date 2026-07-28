# Foundry Responses convenience API evaluation

This issue-linked design note records the decision for
[#60](https://github.com/jpalvarezl/Konductor/issues/60) under
[I055](iterations/I055-foundry-first-platform-alignment.md). It evaluates
`com.azure:azure-ai-agents` **2.2.0** at pinned Azure SDK commit
[`ee28e96b521`](https://github.com/Azure/azure-sdk-for-java/tree/ee28e96b521/sdk/ai/azure-ai-agents),
which embeds `com.openai:openai-java` **4.14.0**. It changes no inference behavior.

## Decision

Keep Konductor's caller-owned `OpenAIClient` paths in 2.2.0:

- `buildOpenAIClient()` for ephemeral Prompt;
- `buildAgentScopedOpenAIClient(agentName)` for persisted PromptAgent; and
- `buildAgentScopedOpenAIClient(agentName)` plus `agent_session_id` for Hosted.

`ResponsesClient.createAzureResponse` and `ResponsesAsyncClient.createAzureResponse` can represent ephemeral
requests and persisted PromptAgent references. Their streaming variants also represent persisted-agent streaming,
including structured inputs. They are not a net improvement for Konductor in 2.2.0 because both wrappers discard the
closeable root OpenAI client; synchronous streaming additionally hides the only close handle behind a non-closeable
`IterableStream`. For Hosted, the current live-verified path and pinned SDK samples use an agent-scoped OpenAI client
and an untyped session property; this is evidence for the current recommendation, not proof that other request shapes
are unsupported.

Reconsider the convenience wrappers when they retain and expose deterministic client closure and when synchronous
streaming offers an explicit close/cancel operation. Until then, adopting them would trade away lifecycle control
without removing Konductor's request/response mapping or retry policy.

## Evidence labels

- **Source-verified** means the statement follows from the pinned 2.2.0 implementation, tests, or samples.
- **Konductor-verified** means the current adapter or its existing live evidence establishes the behavior.
- **Unknown** means the pinned source does not establish service behavior; it is not treated as a limitation.

No live service probes were added for this spike. Existing PromptAgent and Hosted live findings remain recorded in
[`prompt_agents.md`](service_feedback/prompt_agents.md) and
[`hosted_agents.md`](service_feedback/hosted_agents.md).

## Recommendation matrix

| Scenario | 2.2.0 convenience surface | Request and lifecycle evidence | Recommendation |
|---|---|---|---|
| Ephemeral Prompt, sync | `ResponsesClient.getResponseService().create(params)` or `createAzureResponse(emptyOptions, builder)` | **Source-verified:** full `ResponseCreateParams` is forwarded. The wrapper retains only `ResponseService`, not its root `OpenAIClient`. | Keep caller-owned `buildOpenAIClient()` and close it with the session runtime. The Azure-specific overload adds no needed field for ephemeral calls. |
| Ephemeral Prompt, streaming | Sync `createStreamingAzureResponse` returns `IterableStream`; async returns `Flux` | **Source-verified:** sync closes its `StreamResponse` only after iterator exhaustion; async closes `AsyncStreamResponse` on Reactor disposal. Neither wrapper can close its root client. | Keep direct `StreamResponse.use` for ownership. Do not introduce Reactor solely to recover per-stream cancellation while losing client closure. |
| Persisted PromptAgent, sync | `createAzureResponse(AzureCreateResponseOptions.setAgentReference(...), builder)` | **Source-verified:** `AgentReference` carries name and optional version; SDK samples send input plus the reference. Existing direct agent-scoped invocation is **Konductor-verified** live with an input-only payload. | Keep the current agent-scoped client in 2.2.0. The wrapper has better per-request version selection, but deterministic closure is required. |
| Persisted PromptAgent, streaming | Both wrapper streaming methods accept the same options | **Source-verified:** sync and async tests cover agent references, text/tool events, and structured inputs. Sync still has the hidden stream-close problem. | Keep direct streaming. Re-evaluate wrappers together rather than split sync and streaming ownership models. |
| Hosted, sync | No Hosted-specific Responses convenience | **Source-verified:** `AzureCreateResponseOptions` contains only `agent_reference` and `structured_inputs`; the Hosted sample builds an agent-scoped client and adds `agent_session_id` manually. Whether project-scoped `agent_reference` plus the session property works is **unknown**. | Continue the current live-verified, sample-backed agent-scoped path. This spike does not establish it as the only supported path. |
| Hosted, streaming | Agent-scoped OpenAI async/sync services are available; the Azure wrappers are project-scoped | **Source-verified:** the builder exposes agent-scoped OpenAI clients, but not an agent-scoped `ResponsesClient`; no pinned Hosted sample proves Responses event streaming. | Leave Hosted response streaming out of this spike. A later issue should verify request shape and cancellation rather than infer either wrapper incompatibility or support. |

## Cross-cutting findings

### Closeability and cancellation

`AgentsClientBuilder.buildResponsesClient()` constructs an `OpenAIClient` and passes only its `ResponseService` into
`ResponsesClient`; the async builder does the same with `OpenAIClientAsync` and `ResponseServiceAsync`. The wrappers
expose those services but have no `close()` and no way to recover the root. In openai-java 4.14.0 both root clients
have `close()`. Their client options own a sleeper and dedicated cached stream-handler executor, which client closure
shuts down. In the Azure bridge, `HttpClientHelper.HttpClientWrapper.close()` is a no-op, so this evidence establishes
deterministic OpenAI executor/sleeper cleanup—not closure of the Azure pipeline's transport.

For a synchronous stream, `StreamingUtils.toIterableStream` captures the closeable `StreamResponse` privately and
closes it only when `hasNext()` reaches end-of-stream. `IterableStream` itself has no close operation. Early loop exit,
coroutine cancellation, or an exception before exhaustion therefore has no wrapper-level close hook. This is weaker
than Konductor's direct `StreamResponse.use`; even the direct blocking iterator may not react immediately while a
socket read is blocked, so the current path provides deterministic cleanup on stack exit, not a claim of immediate
interruptibility.

The async streaming bridge is materially better: `StreamingUtils.toFlux` registers
`AsyncStreamResponse.close()` with `sink.onDispose`, so Reactor cancellation closes the response stream. That does not
solve root-client ownership. Unary sync calls have no call-level cancellation handle on either convenience method.
The async unary method wraps a `CompletableFuture` in `Mono`, but the pinned SDK has no test establishing that Reactor
cancellation aborts an in-flight network request; this remains **unknown**.

The embedded openai-java `ResponseService.cancel(responseId)` contract is narrower than turn cancellation: it sends
`POST /responses/{response_id}/cancel`, and its pinned Javadoc says **only responses created with
`background=true` can be cancelled**. It therefore requires an already obtained response ID and is distinct from
closing or cancelling the local request that creates or streams a foreground response. The sync wrapper makes the
method reachable through `getResponseService()`; the async service has the equivalent operation. Konductor leaves
`background` unset and does not invoke this endpoint. The pinned Azure SDK's sync and async
`cancelBackgroundResponse` test source creates with `background=true` and then calls `cancel`, which establishes the
bridge's intended contract. The pinned repository tree does not include the test-proxy recording or result, however,
and this spike added no live probe. **Azure service conformance in Konductor's environment is unknown**, so this API is
neither a current workaround nor evidence of foreground-call cancellation.

### Retries and request options

All builder-created Responses and OpenAI clients use the Azure `HttpPipeline`. The builder explicitly sets
openai-java `maxRetries(0)` to avoid retrying once in each stack, while the pipeline contains the configured Azure
`RetryPolicy`. The convenience methods add no separate retry behavior.

Konductor's ephemeral adapter deliberately adds application-level retries around that SDK pipeline: at most three
retries for transient failures, and no streaming retry after model output has been emitted. The persisted PromptAgent
and Hosted adapters currently rely on the SDK pipeline rather than that ephemeral whole-call loop. Switching wrappers
would not normalize or remove this policy decision. Any future implementation must distinguish SDK transport retries
from whole-call retries and avoid retrying a partially emitted stream.

The typed convenience methods do not accept openai-java `RequestOptions`; only the raw `BinaryData` protocol methods
do. Therefore per-call timeout or response-validation settings cannot be combined with typed
`AzureCreateResponseOptions` without dropping to `getResponseService()`, raw JSON, or additional body properties.
Konductor currently uses client/application policy, so this is reported as SDK feedback rather than a migration
blocker by itself.

### Azure-specific options and agent references

`AzureCreateResponseOptions` has exactly two 2.2.0 fields:

- `agent_reference`: an `AgentReference` with fixed type `agent_reference`, required name, and optional version;
- `structured_inputs`: `Map<String, BinaryData>` for prompt-template substitution or tool bindings.

Both are flattened into top-level openai-java additional body properties. Pinned sync and async SDK tests cover
streaming with both fields. These are structured **prompt inputs**, not openai-java's typed structured-output response
facility.

#### Source-observed future risk—not encountered dogfooding feedback

Konductor has no structured-input domain field, reproducer, current impact, or adopted workaround. The pinned
`AzureCreateResponseOptions` source exposes each value as `BinaryData`, while the pinned sample constructs values with
`BinaryData.fromObject(...)`. Because Konductor encountered a `BinaryData` representation pitfall on the separate
`FunctionTool` surface, any future structured-input adapter should preserve arbitrary JSON values and verify
`fromObject`/`fromString` wire behavior with a focused test. This is a source-observed compatibility risk for future
work, not an SDK/service feedback finding from #60.

The wrapper reference path can select an explicit agent version per request without rebuilding a client. The current
agent-scoped path selects an endpoint by agent name and cannot attach `AzureCreateResponseOptions`; it is retained for
ownership and for the live-proven PromptAgent/Hosted endpoint contracts, not because the wrapper lacks agent
references.

## Follow-up scope

There is **no immediate Konductor inference-code follow-up** from #60. The direct-client path is justified for
azure-ai-agents 2.2.0 by deterministic root-client and stream ownership. For Hosted, it is the current live-verified
and sample-backed path; the evaluation did not prove that the project-scoped wrapper can or cannot conform to the
Hosted request contract.

A later SDK-upgrade issue may replace it only after verifying all of the following against that exact version:

1. sync and async wrappers expose or own a deterministic close operation;
2. early termination closes synchronous response streams;
3. PromptAgent input-only requests, explicit versions, dynamic developer items, tools, and structured inputs work;
4. Hosted has a typed/scoped session binding or remains explicitly direct-client;
5. coroutine/Reactor cancellation and retry boundaries are tested before and after first output; and
6. the application still owns one closeable resource per TUI/ACP session.

Adding structured inputs, server-managed Prompt conversations, or Hosted response streaming remains separate product
scope; this spike does not create those behaviors implicitly.

## Pinned references

- Azure wrapper implementation: [`ResponsesClient`][responses-client] and
  [`ResponsesAsyncClient`][responses-async-client]
- Builder construction, scoped clients, preview headers, and retry handoff: [`AgentsClientBuilder`][agents-builder]
- OpenAI/Azure HTTP adaptation: [`HttpClientHelper`][http-client-helper]
- Azure request fields: [`AzureCreateResponseOptions`][azure-options] and [`AgentReference`][agent-reference]
- Stream bridges: [`StreamingUtils`][streaming-utils]
- Persisted-agent coverage: [sync streaming tests][streaming-tests],
  [async streaming tests][streaming-async-tests], and [structured-input sample][structured-input-sample]
- Hosted invocation shape: [sync Hosted endpoint sample][hosted-sample] and
  [async Hosted endpoint sample][hosted-async-sample]
- Underlying 4.14.0 lifecycle and cancellation contract: openai-java [`OpenAIClient`][openai-client],
  [`ClientOptions`][openai-options], [`AsyncStreamResponse`][openai-async-stream], and
  [`ResponseService.cancel`][openai-response-cancel]
- Azure SDK intended background-cancellation coverage: pinned [sync test][responses-cancel-test] and
  [async test][responses-async-cancel-test]

[responses-client]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/ResponsesClient.java#L33-L91
[responses-async-client]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/ResponsesAsyncClient.java#L33-L91
[agents-builder]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/AgentsClientBuilder.java#L380-L502
[http-client-helper]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/implementation/http/HttpClientHelper.java#L49-L78
[azure-options]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/models/AzureCreateResponseOptions.java#L20-L102
[agent-reference]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/models/AgentReference.java#L18-L91
[streaming-utils]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/implementation/StreamingUtils.java#L27-L104
[streaming-tests]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/test/java/com/azure/ai/agents/StreamingTests.java#L45-L242
[streaming-async-tests]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/test/java/com/azure/ai/agents/StreamingAsyncTests.java#L47-L267
[structured-input-sample]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/CreateResponseWithStructuredInput.java#L27-L90
[hosted-sample]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents/AgentEndpointSample.java#L51-L69
[hosted-async-sample]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents/AgentEndpointAsyncSample.java#L51-L73
[openai-client]: https://github.com/openai/openai-java/blob/v4.14.0/openai-java-core/src/main/kotlin/com/openai/client/OpenAIClient.kt#L43-L118
[openai-options]: https://github.com/openai/openai-java/blob/v4.14.0/openai-java-core/src/main/kotlin/com/openai/core/ClientOptions.kt#L505-L614
[openai-async-stream]: https://github.com/openai/openai-java/blob/v4.14.0/openai-java-core/src/main/kotlin/com/openai/core/http/AsyncStreamResponse.kt#L14-L46
[openai-response-cancel]: https://github.com/openai/openai-java/blob/v4.14.0/openai-java-core/src/main/kotlin/com/openai/services/blocking/ResponseService.kt#L241-L272
[responses-cancel-test]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/test/java/com/azure/ai/agents/ResponsesTests.java#L61-L84
[responses-async-cancel-test]: https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/test/java/com/azure/ai/agents/ResponsesAsyncTests.java#L65-L88
