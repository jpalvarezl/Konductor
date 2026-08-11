# Hosted agents — SDK/service feedback

Dog-fooding feedback from building Konductor's **Hosted provider** (`provider/hosted/`) against
`com.azure:azure-ai-agents` **2.2.0** (openai-java 4.14.0), with final API/cancellation evidence against
**2.3.0** (openai-java 4.45.0) — the hosted-agent *version → endpoint → session → agent-scoped Responses → session
logs* flow. Cross-checked against the SDK's own
`sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents` samples.

Legend: **Impact** = what it cost us · **Workaround** = what Konductor does today · **Suggestion** = what would
have removed the friction.

> **2.3.0 API recheck:** Hosted version/session/log/file methods and the name-only
> `AgentsClientBuilder.buildAgentScopedOpenAIClient(String)` surface remain compatible. `AzureCreateResponseOptions`
> gained `userSecurityContext` but still has no typed `agent_session_id`. `HostedSessionLifecycleLiveTest` was rerun
> successfully on 2.3.0, covering version/session lifecycle, exact resume, binding preservation after a local cancel
> request, and cleanup. That fast-echo LIVE run did not prove transport-effective cancellation. Delayed fixtures show
> that public async cancellation remains broken at the Azure `HttpClient` subscription seam, while interrupting the
> sync call cancels that subscription and allows the same binding to be invoked again; item #8 records the exact chain.

> _Status: ✅ **Verified live end-to-end** (2026-07-08) against the `foundry-sdk-deployment`/`java`
> `responses-echo-agent` container — version create → poll-to-`ACTIVE`, endpoint config, session create,
> agent-scoped Responses invoke (**echo response received**), and delete-only cleanup all work; version reuse
> across runs confirmed. Items #1, #3, #7 were exercised live; #2 (log stream) is code-verified but not consumed
> in the smoke. The I028 lifecycle was revalidated live on 2026-07-30: deterministic local/server ids,
> second-session isolation, exact resume, binding preservation after a local cancel request, detach-only durable close,
> and delete-only ephemeral cleanup all passed in `HostedSessionLifecycleLiveTest`. This LIVE result is lifecycle and
> binding evidence only, not Azure `HttpClient`, wire, or service-work cancellation evidence._

---

## 1. No "create and wait" for agent versions — you must hand-roll a poll loop

`AgentsClient.createAgentVersion(...)` returns an `AgentVersionDetails` immediately, but the version is
provisioned **asynchronously** and is unusable (create session / configure endpoint / invoke) until its
`getStatus()` is `AgentVersionStatus.ACTIVE`. There is no `createAgentVersionAndWait`, no poller/LRO, and no
event — the only way to know it's ready is to call `getAgentVersionDetails(agentName, version)` in a loop and
inspect `getStatus()` (handling `FAILED` and a timeout). The SDK sample itself does this
(`HostedAgentsSampleUtils.waitForAgentVersionActive`, 60 attempts × 10s).

- **Impact:** every hosted client re-implements the same polling boilerplate; easy to forget and then hit
  confusing "session/invoke fails on a brand-new version" races.
- **Workaround:** `AzureHostedAgentClient.awaitVersionActive` polls `getAgentVersionDetails(...).status` every
  10s up to 60 times.
- **Suggestion:** a `createAgentVersion` overload that returns a `SyncPoller`/`LongRunningOperation`, or an
  `awaitActive()` convenience, or a webhook/event on activation.

## 2. Session log stream is an endless SSE with no completion — and the easy overload can't be closed

`AgentsClient.getSessionLogStream(agentName, version, sessionId)` returns
`com.azure.core.util.IterableStream<SessionLogEvent>`. The stream is a **live server-sent-event feed that never
signals completion** (the service emits keep-alive frames like `"No logs since last 60 seconds"` roughly every
60s and holds the connection open). `IterableStream` is **not `Closeable`**, so once you start iterating there
is no clean way to stop — and the iterator's `hasNext()` blocks on the socket, so **coroutine/thread
cancellation cannot interrupt it**. The only terminable path is the lower-level
`getSessionLogStreamWithResponse(...)` → `Response<BinaryData>` → `.toStream()`, which you must **close
yourself** to break the blocking read (the sample schedules a watchdog thread that closes the stream after a
timeout).

- **Impact:** our first implementation used `getSessionLogStream` inside a coroutine `Flow`; cancelling the
  turn couldn't stop the blocking read, so **every hosted turn froze for up to the ~60s keep-alive interval**
  (found in code review). This is fundamentally hostile to structured concurrency.
- **Workaround:** `AzureHostedAgentClient.streamSessionLogs` uses `getSessionLogStreamWithResponse(...).value
  .toStream()` inside a `callbackFlow`, reading frames on an IO job and **closing the stream in `awaitClose`**
  so collector cancellation unblocks the read.
- **Suggestion:** make the log stream naturally completable, expose an `AutoCloseable`/`Closeable` stream (or a
  cancellable reactive `Flux` that completes on unsubscribe), and clearly document that the `IterableStream`
  overload cannot be cancelled. A typed frame model instead of raw `data: …` SSE lines would help too.

## 3. Undocumented "vnext" requirements to actually serve the Responses protocol

Getting a hosted agent to serve the OpenAI **Responses** protocol required two settings that are **not
discoverable from the type signatures** and are only shown in the samples:

1. `HostedAgentDefinition.setProtocolVersions(listOf(ProtocolVersionRecord(AgentEndpointProtocol.RESPONSES,
   "1.0.0")))`, and
2. create-version **metadata** `enableVnextExperience = "true"`.

Omitting either produces a version that provisions but doesn't behave as expected. The endpoint side *also*
needs `AgentEndpointConfig().setProtocolConfiguration(ProtocolConfiguration().setResponses(
ResponsesProtocolConfiguration()))` — three separate places that must agree.

- **Impact:** trial-and-error / sample-archaeology to find a working combination; no compile-time or
  create-time signal that a required knob is missing.
- **Workaround:** `AzureHostedAgentClient` sets `protocolVersions` + `enableVnextExperience=true` on the
  definition and the RESPONSES protocol on the endpoint, matching the samples.
- **Suggestion:** validate the definition/endpoint agreement at `createAgentVersion` time and return a clear
  error; document the `enableVnextExperience` metadata flag (or make it the default for hosted+Responses).

## 4. `agent_session_id` is a stringly-typed "additional body property" on the Responses call

Invoking a hosted agent through the agent-scoped OpenAI client requires smuggling the session id in as an
untyped body property:

```kotlin
openAIClient.responses().create(
    ResponseCreateParams.builder()
        .input(input)
        .putAdditionalBodyProperty("agent_session_id", JsonValue.from(sessionId))
        .build(),
)
```

The [#60 Responses evaluation](../foundry-responses-evaluation.md) confirms that the convenience wrapper is not a
typed Hosted alternative in 2.2.0: `AzureCreateResponseOptions` contains only `agent_reference` and
`structured_inputs`, and `AgentsClientBuilder` has no agent-scoped `ResponsesClient`. The pinned Hosted sync/async
samples both use `buildAgentScopedOpenAIClient` / `buildAgentScopedOpenAIAsyncClient` and the same additional property.
Whether a project-scoped agent reference plus this property works is unverified. The agent-scoped client is therefore
the current live-verified, sample-backed evidence—not an established requirement or proof that another shape is
unsupported.

- **Impact:** the key `"agent_session_id"` is a magic string (typo-prone, not discoverable via autocomplete);
  nothing links the agent-scoped client to the session type, and the Azure convenience options do not model it.
- **Workaround:** we hard-code the property name in one place (`AzureHostedAgentClient.invoke`) on the live-verified
  agent-scoped client.
- **Suggestion:** a first-class `.agentSessionId(...)` on the agent-scoped Responses params, an agent-scoped
  `ResponsesClient` that models Hosted sessions, or bind the session to the agent-scoped client so it is implicit.

## 5. Two ways to create a session, and the sample prefers the untyped one

There is a typed `createSession(agentName, VersionRefIndicator(version))` **and** the sample uses the raw
`createSessionWithResponse(agentName, BinaryData.fromObject({version_indicator: {agent_version, type:
"version_ref"}}), RequestOptions)`. A second generated typed overload,
`createSession(agentName, versionIndicator, agentSessionId)`, accepts a caller-provided id that must be unique within
the agent endpoint, but the 2.2.0 samples do not demonstrate it. Having all three shapes, with the official sample
choosing the untyped `Map`/`BinaryData` form, makes the canonical path and the recoverable caller-id capability easy to
miss.

- **Impact:** Konductor initially picked the two-argument typed convenience. Durable Hosted resume needs the caller-id
  overload because the local UUID can be reserved before network creation, but the API promises uniqueness, not
  idempotent create. Azure Core's default retry policy may also retry the POST after a lost response, so even a surfaced
  `409` can follow a successful first request; a conflict or ambiguous result still requires `getSession`
  reconciliation.
- **Workaround:** `AzureHostedAgentClient.createSession` now uses the three-argument overload for persisted sessions and
  classifies `409`, transport failures, and retryable HTTP results as reconciliation cases. `HostedProvider` performs
  up to 30 scoped `getSession` checks at two-second intervals for the one reserved id; it never retries the POST or
  allocates an alternate id. Ephemeral `--no-session` still uses the two-argument overload.
- **Suggestion:** use the typed overloads in samples, demonstrate caller-provided identity and conflict reconciliation,
  and document when the raw protocol form is necessary.

## 6. `AgentsClient` is not `Closeable`/`AutoCloseable`

Minor, but the agent-scoped `OpenAIClient` **is** `Closeable` while `AgentsClient` is not, so you can't
uniformly `use {}` / dispose the SDK clients. (Not a leak in practice — `AgentsClient` holds no obviously
disposable state — but it's an inconsistency and a papercut for resource-management code.)

- **Workaround:** `close()` disposes only the openai client.
- **Suggestion:** make `AgentsClient` `AutoCloseable` (even if a no-op) for consistency.

---

---

## 7. Deleting a session that is being stopped → `409` (found live)

`stopSession(...)` and `deleteSession(...)` **run concurrently** rejected the delete with
**`409 invalid_request_error`** — you cannot delete a session that is being stopped. Our first `close()`
launched both concurrently and 409'd on **every** turn (after an otherwise-successful echo response). The SDK
sample cleans up with **`deleteSession` alone** (no stop) on a still-running session, which succeeds.

- **Impact:** every hosted turn threw during teardown despite a correct response; only surfaced by running live
  against the real container — a pure unit/fake test cannot reproduce it.
- **Workaround:** ephemeral `HostedProvider` cleanup does **delete-only, best-effort** (`runCatching`), matching the
  sample; the Konductor Hosted client seam no longer exposes `stopSession`. Durable switch/close only detaches and
  performs no session cleanup call.
- **Suggestion:** make `deleteSession` tolerate a stopping session (idempotent teardown), document the required
  order (delete a running session — don't stop-then-delete), or provide one `endSession` that does the right
  thing.

## 8. Public async cancellation is lost, but interrupting sync reaches the Azure HttpClient subscription (2.3.0)

`AgentsClientBuilder.buildAgentScopedOpenAIAsyncClient(String)` returns openai-java 4.45.0's `OpenAIClientAsync`.
Its `responses().create(ResponseCreateParams)` returns a public `CompletableFuture<Response>`, but cancellation of
that future does **not** cancel the injected Azure `HttpClient` publisher subscription within the two-second
observation bound. The adapter itself is not the blocker:
`HttpClientHelper.HttpClientWrapper.executeAsync` terminates in
`HttpPipeline.send(...).map(...).publishOn(...).toFuture()`, and cancelling that direct future records subscription
`onCancel`. OpenAI hides it behind `prepareAsync(...).thenComposeAsync(executeAsync).thenApply` and another
`thenApply`; cancelling a dependent future does not cancel its parent.

Streaming is not a fallback. Async `createStreaming(...).close()` before headers only registers a callback to close
an eventual response and does not cancel the pending future. After headers, openai-java's sync `StreamHandler` reads
through a synchronized `BufferedReader`; concurrent `StreamResponse.close()` blocks behind `readLine` before it can
close Azure's `FluxInputStream`, so no body-subscription cancellation is observed during the same bound. Root client
close reaches the Azure adapter's no-op `close()`. The Responses `cancel(responseId)` operation also requires an
already-created `background=true` response and cannot abort this foreground create.

The existing synchronous unary path is usable with an explicit interruption boundary. It blocks in
`HttpPipeline.sendSync`; `runInterruptible(Dispatchers.IO)` interrupts Reactor's blocking subscriber on coroutine
cancellation, and Reactor cancels the Azure `HttpClient` publisher subscription. Konductor carries the blocking
outcome across the dispatcher as data, then checks `currentCoroutineContext().ensureActive()` so a coroutine-caused
interrupt remains `CancellationException` while an active failure is rethrown as the same instance.

- **Impact:** switching to the obvious async or streaming APIs would still fake request cancellation. The workable
  sync path needs Kotlin-specific interruption plumbing because the SDK exposes no call handle.
- **Deterministic evidence:** `AgentScopedResponsesCancellationEvidenceTest` injects delayed Azure publishers whose
  readiness signals occur only after subscription and `onCancel` registration. Direct adapter future cancellation and
  production interruptible sync cancellation each record subscription `onCancel` within two seconds. The negative
  async/stream observations mean only that no cancellation arrived during the stated two-second windows. The
  production invoke runs through `HostedProvider`: its caller receives `CancellationException`, a second turn uses the
  identical binding, and no create/delete occurs. Under the builder's default retry policy the delayed first call has
  one `HttpClient.send` and one subscription, with no additional request during the post-cancel bound; because the
  fixture emits no retry-triggering response/error, this is not a general no-retry claim. See
  [I101](../iterations/I101-hosted-response-cancellation-evidence.md) for artifact hashes and full results.
- **Workaround:** `AzureHostedAgentClient.invoke` retains `buildAgentScopedOpenAIClient(agentName)` and the same request,
  but runs blocking `responses().create(...)` in `runInterruptible(Dispatchers.IO)`. `HostedProvider` retains the
  durable binding after cancellation; the next turn uses that same service session. This proves cancellation only at
  the injected Azure `HttpClient` subscription seam, not at a production socket or the service.
- **Suggestion:** still expose an agent-scoped cancellable `Mono`/call handle or preserve cancellation from the public
  unary future and stream close through all stages. This would remove dependence on interrupting a blocking call and
  the noisy wrapped-interrupt path. Use the delayed injectable `HttpClient`/`onCancel` regression shape from I101.

## What worked well

- `AgentsClientBuilder().allowPreview(true).buildAgentsClient()` + `buildAgentScopedOpenAIClient(agentName)` is a
  clean split, and the endpoint/version-selector config (`AgentEndpointConfig` + `FixedRatioVersionSelectionRule`
  + `ResponsesProtocolConfiguration`) is expressive.
- Reusing an existing **ACTIVE** version (`listAgentVersions(...).firstOrNull { ACTIVE }`) avoids re-provisioning
  and makes warm reuse easy.
- `stopSession` / `deleteSession` give clean lifecycle control.

## Live-validation resource

Verified-runnable infra (via the `work-resource-index` skill): resource **`foundry-sdk-deployment`** / flavor
**`java`** ships a prebuilt `responses-echo-agent` container in the project ACR, exposing
`FOUNDRY_PROJECT_ENDPOINT` + `FOUNDRY_AGENT_CONTAINER_IMAGE`. Load with
`wr-load -Resource foundry-sdk-deployment -Flavor java`, `az login`, set `KONDUCTOR_HOSTED_AGENT_NAME`, run with
`--agent-kind hosted`.
