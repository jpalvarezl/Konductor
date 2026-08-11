---
id: I101
title: Hosted Responses transport-cancellation evidence
issue: https://github.com/jpalvarezl/Konductor/issues/101
created: 2026-08-11
---

# I101 — Hosted Responses Transport-Cancellation Evidence

Issue [#101](https://github.com/jpalvarezl/Konductor/issues/101) owns coordination. This packet commits the final
feasibility evidence and the resulting minimal implementation for `com.azure:azure-ai-agents:2.3.0` and its
transitive `com.openai:openai-java-core:4.45.0`. The proof observes Azure transport `onCancel`; it does not infer
success from outer coroutine cancellation.

## Result

**Feasible through the existing synchronous agent-scoped binding.** The public async unary future and pre-header
stream close still lose cancellation, but Kotlin `runInterruptible(Dispatchers.IO)` can interrupt the thread blocked
in Azure's synchronous `HttpPipeline.sendSync`. Reactor then cancels the active `HttpPipeline.send` subscription.
`AzureHostedAgentClient.invoke` now uses that path and restores `CancellationException` semantics when Azure/Reactor
wraps the carrier-thread `InterruptedException`.

No client or request shape changes: the implementation retains the same
`buildAgentScopedOpenAIClient(agentName)`, agent-scoped URL, and `agent_session_id` body property.

## Local artifact and API evidence

The Maven-resolved release artifacts inspected for this re-check were:

| Artifact | SHA-256 |
|---|---|
| `azure-ai-agents-2.3.0.jar` | `0b5d59ed10668781f480b502266278dab129a83ed7c044b7bd9a057a21d918b5` |
| `azure-ai-agents-2.3.0-sources.jar` | `637c841f748323b017081222028c43477726d83e0a12f2f869cc6ec13fca8d41` |
| `openai-java-core-4.45.0.jar` | `8a9de38de5218f48f383b75cfb0bb758f00f8d40769bcf1f50677a25d3e2ddd7` |
| `openai-java-core-4.45.0-sources.jar` | `366c471a772b6fcf1f3818eb061f60822ebfa1481c0a7eb9fa201eca2669e344` |

`javap` and the matching source JARs establish these exact chains:

- **Sync unary:** `AgentsClientBuilder.buildAgentScopedOpenAIClient(String)` →
  `OpenAIClient.responses().create(ResponseCreateParams)` → OpenAI `HttpClient.execute(...)` →
  Azure `HttpClientHelper.HttpClientWrapper.execute(...)` → `HttpPipeline.sendSync(...)`. `sendSync` blocks on the
  reactive pipeline. Interrupting that blocking thread cancels the subscription; plain `withContext(IO)` does not
  interrupt it, while `runInterruptible(IO)` does.
- **Async unary:** `buildAgentScopedOpenAIAsyncClient(String)` exposes
  `ResponseServiceAsync.create(...): CompletableFuture<Response>`. `ResponseServiceAsyncImpl.create` adds
  `thenApply`; its raw create uses `prepareAsync(...).thenComposeAsync { httpClient.executeAsync(...) }.thenApply`.
  Authentication/logging/retry decorators add further dependent stages. The Azure adapter itself ends in
  `HttpPipeline.send(...).map(...).publishOn(...).toFuture()`, which is cancellable, but that inner future is not
  returned. `CompletableFuture.cancel` on the public dependent stage does not cancel its parent.
- **Async streaming before headers:** `createStreaming(...)` uses the same dependent transport chain and wraps it
  with `CompletableFuture<StreamResponse<T>>.toAsync`. `AsyncStreamResponse.close()` installs
  `whenComplete { streamResponse?.close() }`; before headers it neither cancels that future nor the transport.
- **Sync streaming after headers:** `StreamHandler` reads through a synchronized `BufferedReader`. A concurrent
  `StreamResponse.close()` blocks acquiring the reader monitor held by `readLine`, before it can close Azure's
  `FluxInputStream`; the active body subscription is therefore not cancelled. This is not a safe replacement path.
- **Client close:** Azure `HttpClientHelper.HttpClientWrapper.close()` is a no-op, so closing the root OpenAI client
  is not a per-call cancellation handle.

The Responses service `cancel(responseId)` API is also distinct: it is a second request for an already-created
`background=true` response and cannot abort an in-flight foreground create before a response id exists.

## Executable delayed-transport proof

`AgentScopedResponsesCancellationEvidenceTest` injects deterministic Azure `HttpClient` publishers that record
`onCancel`. It exercises the actual 2.3.0 adapter, public sync/async agent-scoped clients, and production
`AzureHostedAgentClient.invoke`.

| Case | Operation | Transport result |
|---|---|---|
| Adapter control | cancel `HttpClientHelper`'s direct `Mono.toFuture()` | `onCancel` within 2 seconds |
| Public async unary | cancel `responses().create(params)` after subscription | outer future cancelled; no `onCancel` in 2 seconds |
| Public async streaming | `createStreaming(params).close()` after subscription, before headers | no `onCancel` in 2 seconds |
| Public sync streaming | close after body read starts | close remains blocked and no body `onCancel` for 2 seconds; fixture completion releases both threads |
| Production sync unary | cancel coroutine in `AzureHostedAgentClient.invoke` | transport `onCancel` within 2 seconds |
| Same-binding reuse | complete a second invoke normally on the same client and Hosted session id | second request carries the same `agent_session_id` and returns the expected response |

Both production requests assert
`/agents/{agentName}/endpoint/protocols/openai/responses?api-version=v1` and the exact Hosted session property. The
first cancellation produces exactly one transport send (no hidden retry); the second call then completes normally on
the same client. No Agents lifecycle call occurs between them, so no replacement session is created or deleted. The
test releases every negative fixture after its assertion. This is transport evidence, not an outer-coroutine-only fake.

## Implementation decision

`AzureHostedAgentClient.invoke` keeps the live-verified synchronous OpenAI client and changes only the blocking
boundary from `withContext(Dispatchers.IO)` to `runInterruptible(Dispatchers.IO)`. Cancellation interrupts Reactor's
blocking subscriber, which cancels the Azure transport subscription. Azure's retry policy may wrap the interrupt in a
`ReactiveException`; the catch path calls `currentCoroutineContext().ensureActive()` so coroutine-caused interruption
remains `CancellationException`, while genuine failures are rethrown unchanged.

The provider continues to retain the durable Hosted binding on cancellation. A subsequent turn uses the same binding;
server-side advancement before transport cancellation remains possible, so Foundry history is still authoritative.

No genuine long-running Hosted service response was available in the configured echo-agent resource during this
local artifact re-check. The deterministic proof covers the transport contract and same-binding reuse; a future live
acceptance run must use a deliberately delayed Hosted response rather than treating a fast echo as cancellation proof.

## Validation

```bash
./mvnw -q -Dtest=AgentScopedResponsesCancellationEvidenceTest,HostedProviderTest test
node scripts/validate-docs.mjs
git diff --check
```
