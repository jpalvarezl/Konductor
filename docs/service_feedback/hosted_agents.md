# Hosted agents — SDK/service feedback

Dog-fooding feedback from building Konductor's **Hosted provider** (`provider/hosted/`) against
`com.azure:azure-ai-agents` **2.2.0** (openai-java 4.14.0) — the hosted-agent *version → endpoint → session →
agent-scoped Responses → session logs* flow. Cross-checked against the SDK's own
`sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents` samples.

Legend: **Impact** = what it cost us · **Workaround** = what Konductor does today · **Suggestion** = what would
have removed the friction.

> _Status: ✅ **Verified live end-to-end** (2026-07-08) against the `foundry-sdk-deployment`/`java`
> `responses-echo-agent` container — version create → poll-to-`ACTIVE`, endpoint config, session create,
> agent-scoped Responses invoke (**echo response received**), and delete-only cleanup all work; version reuse
> across runs confirmed. Items #1, #3, #7 were exercised live; #2 (log stream) is code-verified but not consumed
> in the smoke. The I028 lifecycle was revalidated live on 2026-07-30: deterministic local/server ids,
> second-session isolation, exact resume, cancellation preservation, detach-only durable close, and delete-only
> ephemeral cleanup all passed in `HostedSessionLifecycleLiveTest`._

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
