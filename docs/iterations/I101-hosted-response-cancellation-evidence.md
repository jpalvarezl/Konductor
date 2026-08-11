---
id: I101
title: Hosted Responses Azure HttpClient subscription-cancellation evidence
issue: https://github.com/jpalvarezl/Konductor/issues/101
created: 2026-08-11
---

# I101 — Hosted Responses Azure HttpClient Subscription-Cancellation Evidence

Issue [#101](https://github.com/jpalvarezl/Konductor/issues/101) owns coordination. This packet commits the final
feasibility evidence and the resulting minimal implementation for `com.azure:azure-ai-agents:2.3.0` and its
transitive `com.openai:openai-java-core:4.45.0`. The deterministic proof observes `onCancel` at the injected Azure
`HttpClient` publisher subscription. It does not infer success from outer coroutine cancellation and does not claim
that a production HTTP provider closed a socket or that the service stopped work.

## Result

**Feasible through the existing synchronous agent-scoped binding.** Cancellation from the public async unary future
and pre-header stream close was not observed at the Azure `HttpClient` subscription seam during the two-second
observation windows, but Kotlin `runInterruptible(Dispatchers.IO)` can interrupt the thread blocked in Azure's
synchronous `HttpPipeline.sendSync`. Reactor then cancels the active Azure `HttpClient` publisher subscription.
`AzureHostedAgentClient.invoke` uses that path and restores caller `CancellationException` semantics when
Azure/Reactor wraps the carrier-thread `InterruptedException`.

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
  reactive pipeline. Interrupting that blocking thread cancels the Azure `HttpClient` publisher subscription; plain
  `withContext(IO)` does not interrupt it, while `runInterruptible(IO)` does.
- **Async unary:** `buildAgentScopedOpenAIAsyncClient(String)` exposes
  `ResponseServiceAsync.create(...): CompletableFuture<Response>`. `ResponseServiceAsyncImpl.create` adds
  `thenApply`; its raw create uses `prepareAsync(...).thenComposeAsync { httpClient.executeAsync(...) }.thenApply`.
  Authentication/logging/retry decorators add further dependent stages. The Azure adapter itself ends in
  `HttpPipeline.send(...).map(...).publishOn(...).toFuture()`, whose cancellation reaches the injected `HttpClient`
  subscription, but that inner future is not returned. After cancelling the public dependent future, subscription
  `onCancel` was not observed during the two-second window.
- **Async streaming before headers:** `createStreaming(...)` uses the same dependent transport chain and wraps it
  with `CompletableFuture<StreamResponse<T>>.toAsync`. `AsyncStreamResponse.close()` installs
  `whenComplete { streamResponse?.close() }` rather than calling `cancel` on the pending future; subscription
  `onCancel` was not observed during the two-second pre-header window.
- **Sync streaming after headers:** `StreamHandler` reads through a synchronized `BufferedReader`. A concurrent
  `StreamResponse.close()` blocks acquiring the reader monitor held by `readLine`, before it can close Azure's
  `FluxInputStream`; no body-subscription cancellation is observed within the bound. This is not a safe replacement.
- **Client close:** Azure `HttpClientHelper.HttpClientWrapper.close()` is a no-op, so closing the root OpenAI client
  is not a per-call cancellation handle.

The Responses service `cancel(responseId)` API is also distinct: it is a second request for an already-created
`background=true` response and cannot abort an in-flight foreground create before a response id exists.

## Executable delayed-subscription proof

`AgentScopedResponsesCancellationEvidenceTest` injects deterministic Azure `HttpClient` publishers. Every delayed
fixture reports readiness only after its `Mono` has been subscribed and its `onCancel` handler has been registered.
The test exercises the actual 2.3.0 adapter, public sync/async agent-scoped clients, production
`AzureHostedAgentClient.invoke`, and a `HostedProvider` using that production invoke boundary.

| Case | Operation | Azure `HttpClient` subscription observation |
|---|---|---|
| Adapter control | cancel `HttpClientHelper`'s direct `Mono.toFuture()` | `onCancel` within 2 seconds |
| Public async unary | cancel `responses().create(params)` after readiness | outer future cancelled; no `onCancel` during the following 2 seconds |
| Public async streaming | close after readiness, before headers | no `onCancel` during the following 2 seconds |
| Public sync streaming | close after body read starts | close remains blocked and no body `onCancel` during 2 seconds; fixture completion releases both threads |
| Production sync unary through `HostedProvider` | cancel the collecting caller, await subscription `onCancel`, then join with the remainder of one 2-second deadline | Azure `HttpClient` `onCancel` and caller unwind complete within the single deadline; caller receives `CancellationException` |
| Same-binding reuse | complete a second provider turn | second request carries the same `agent_session_id` and returns the expected response |
| Active production-boundary failure | throw while the coroutine remains active | the caller receives the exact same failure instance |

The negative results are bounded observations, not claims that cancellation could never occur later. Each negative
fixture is explicitly released after its two-second observation. A loopback test with the production HTTP provider
was not added: portable server-side detection would depend on a later socket write failing, whose timing and buffering
vary by OS and do not deterministically establish Foundry stopped work. Accordingly, all executable claims stop at the
Azure `HttpClient` publisher-subscription boundary; no wire-level claim is made.

The production/provider case asserts both requests use
`/agents/{agentName}/endpoint/protocols/openai/responses?api-version=v1` and the exact Hosted session property. Its
positive cancellation assertion starts one monotonic two-second deadline immediately before caller cancellation,
awaits the transport `onCancel` latch first, and then joins the caller with only the remaining budget. With
`AgentsClientBuilder`'s default retry pipeline, the delayed first call produces one `HttpClient.send` invocation and
one publisher subscription, and no additional request is observed during the separate two-second post-cancellation
bound. The fixture emits neither a response nor an error before cancellation, so it does not trigger a retry condition;
this is not a no-retry configuration or a claim that every failure mode is single-send.

The integration wrapper delegates invocation to production `AzureHostedAgentClient` while exposing lifecycle calls to
`HostedProvider`. It proves one scoped `getSession`, no create/delete/replacement, two invokes with the same binding,
and successful reuse after cancellation. This connects SDK cancellation behavior to provider binding retention rather
than testing those properties only in separate fakes.

## Implementation decision

`AzureHostedAgentClient.invoke` keeps the live-verified synchronous OpenAI client and changes the blocking boundary
from `withContext(Dispatchers.IO)` to `runInterruptible(Dispatchers.IO)`. Cancellation interrupts Reactor's blocking
subscriber, which cancels the Azure `HttpClient` publisher subscription. The blocking operation carries its outcome
across the dispatcher as data: after `ensureActive()` restores coroutine-caused interruption as
`CancellationException`, an active failure is rethrown as the same instance instead of being copied by coroutine
stack-trace recovery.

`HostedProvider` retains the durable Hosted binding on cancellation. A subsequent turn uses that same binding;
server-side advancement before subscription cancellation remains possible, so Foundry history is still authoritative.

The 2.3.0 LIVE run used the configured fast echo agent. It revalidated Hosted lifecycle and binding preservation after
a local cancellation request only; it did not prove cancellation reached the Azure `HttpClient`, a socket, or the
service. The deterministic delayed fixtures provide the subscription-level cancellation evidence.

## Validation

```bash
./mvnw -q -Dtest=AgentScopedResponsesCancellationEvidenceTest,HostedProviderTest test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```
