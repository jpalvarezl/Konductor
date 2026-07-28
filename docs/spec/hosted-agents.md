# Hosted Agents

The **Hosted provider** targets a **containerized agent** deployed to Foundry. Unlike the
[Prompt provider](providers.md), the container **owns the agent loop and its own context** — Konductor is a client,
orchestrator, and I/O bridge. It deploys/selects an agent version, opens a **server-side session**, invokes the
agent through an **agent-scoped Responses client** and streams **session logs**. A future I/O bridge can move
**session files** in and out of the container; that bridge is not implemented yet and is tracked in
[issue #37](https://github.com/jpalvarezl/Konductor/issues/37).

> **Preview.** Hosted-agent sessions, log streaming, session files, and code-based agents are preview features —
> build the client with `allowPreview(true)`. APIs and behavior may change. Code blocks are illustrative design
> sketches, not committed implementation.

## Hosted vs Prompt

| Aspect | Prompt ([providers.md](providers.md)) | Hosted (this doc) |
|--------|----------------------------------------|-------------------|
| Loop owner | Provider (client-side) | Server container |
| Tools | Client-side `FunctionTool` (local, cwd) | The container's own tools (+ optional session files) |
| History | Client-owned transcript | Server-side `AgentSessionResource` |
| Compaction | Client-side ([compaction.md](compaction.md)) | Server-managed — **client compaction does not apply** |
| Konductor's job | Drive the loop | Invoke, stream logs, sync files |

Because the server owns state, the shared `AgentLoop` keeps local JSONL entries only as a display/audit transcript and
passes just the current user entry across the server-owned-history boundary. `HostedProvider.runTurn` sends that user
message to the server session, relays the response, and streams logs — it does **not** rebuild `input` from local
history and does **not** call the `Compactor`. A persisted Konductor Hosted session therefore reconnects to one durable
server session; its JSONL entries are not enough to recreate the server-owned conversation.

## Configuration

| Env var | Purpose |
|---------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | Project endpoint (shared with Prompt) |
| `FOUNDRY_AGENT_CONTAINER_IMAGE` | Container image for hosted sessions |
| `KONDUCTOR_HOSTED_AGENT_NAME` | Logical hosted-agent name to deploy/select |

See [configuration.md](configuration.md).

## 1. Deploy a code-based agent

A hosted agent is packaged as a code zip and registered as an agent **version**. `HostedAgentDefinition` carries
the container shape (cpu/memory/env/container/code config).

```kotlin
val agentsClient = AgentsClientBuilder()
    .endpoint(cfg.projectEndpoint)
    .credential(DefaultAzureCredentialBuilder().build())
    .allowPreview(true)
    .buildAgentsClient()

val definition = HostedAgentDefinition(/* cpu = */ "1", /* memory = */ "2Gi")
    .setEnvironmentVariables(mapOf("LOG_LEVEL" to "info"))

val version: AgentVersionDetails = agentsClient.createAgentVersionFromCode(
    agentName,
    definition,
    codeFileDetails(codeZipPath),        // CodeFileDetails wrapping the zip
    "Konductor hosted coding agent",     // description
    metadata,
)
// Retrieve the deployed package if needed:
agentsClient.downloadAgentCodeWithResponse(agentName, downloadPath.toString(), RequestOptions())
```

*Sample:* `hostedagents/CodeAgentSample.java`. For the hackathon, deployment can be a one-time setup step; the
provider then only **selects** an existing version.

## 2. Configure the agent endpoint

Point the agent's endpoint at a version and declare the **Responses** protocol so the agent-scoped OpenAI client
can invoke it.

```kotlin
val endpointConfig = AgentEndpointConfig()
    .setVersionSelector(VersionSelector().setVersionSelectionRules(listOf(
        FixedRatioVersionSelectionRule(100).setAgentVersion(version.version))))
    .setProtocolConfiguration(ProtocolConfiguration().setResponses(ResponsesProtocolConfiguration()))

agentsClient.updateAgentDetails(agentName, UpdateAgentDetailsOptions().setAgentEndpoint(endpointConfig))
```

## 3. Create a server-side session

```kotlin
// Reserve the local Konductor session UUID as the service id before making the network call.
val session: AgentSessionResource = agentsClient.createSession(
    agentName,
    VersionRefIndicator(version.version),
    localSession.id.toString(),
)
val sessionId = session.agentSessionId
val status = session.status                                                 // AgentSessionStatus
```

Session management: `createSession` / `getSession(agentName, sessionId)` / `listSessions` / `deleteSession` /
`stopSession`. Konductor persists `agentName + agentSessionId` in the local Hosted session header and uses `getSession`
to reconnect that exact resource. The caller-provided id makes creation recoverable: a create `409` or ambiguous
transport result is reconciled with bounded `getSession` polling. Creation is not assumed idempotent, and no alternate
id is allocated. *Sample:* `hostedagents/SessionsSample.java`.

## 4. Invoke the agent

Hosted agents are invoked through an **agent-scoped OpenAI client**, passing the session id as an extra body
property so the server threads the conversation.

```kotlin
val openAIClient: OpenAIClient = builder.buildAgentScopedOpenAIClient(agentName)

val response = openAIClient.responses().create(
    ResponseCreateParams.builder()
        .input(userText)
        .putAdditionalBodyProperty("agent_session_id", JsonValue.from(sessionId))
        .build())
```

The provider maps the response's output/text to `AgentEvent.TextDelta` + `AgentEvent.TurnCompleted`, mirroring the
Prompt provider so the [TUI](tui.md) renders both identically.

## 5. Stream session logs

While a turn runs, stream the container's logs as `AgentEvent.LogFrame`s (rendered in a log lane — see
[tui.md](tui.md)). The stream is Server-Sent-Events of `SessionLogEvent` frames.

```kotlin
val rawStream: Response<BinaryData> = agentsClient.getSessionLogStreamWithResponse(
    agentName, version.version, sessionId, RequestOptions())
parseSseFrames(rawStream.value).forEach { frame -> emit(AgentEvent.LogFrame(frame.text)) }
```

Async: `SessionLogStreamAsyncSample.java` returns a reactive stream you can bridge to a `Flow`.
*Sample:* `hostedagents/SessionLogStreamSample.java`.

## 6. Session files (I/O bridge)

> **Implementation status:** not implemented. The transfer and safety policy is tracked in
> [issue #37](https://github.com/jpalvarezl/Konductor/issues/37).

To let a hosted agent read/write project files, upload local files into the session and download its outputs:

```kotlin
agentsClient.uploadSessionFileWithResponse(agentName, sessionId, "/work/input.txt", body, RequestOptions())
agentsClient.listSessionFiles(agentName, sessionId)                  // -> SessionDirectoryEntry
agentsClient.downloadSessionFileWithResponse(agentName, sessionId, "/work/output.txt", localPath, RequestOptions())
```

*Sample:* `hostedagents/SessionFilesSample.java`. This is the hosted analogue of the Prompt provider's local file
tools ([tools.md](tools.md)).

## Provider shape

```kotlin
class HostedProvider(cfg: Config) : AgentProvider {
    override val kind = AgentKind.Hosted
    override val capabilities = ProviderCapabilities.Hosted // server history; no client compact/model/tools/agents
    // holds: AgentsClient (allowPreview), agent-scoped OpenAIClient, agentName, activated Hosted binding
    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        // 1) use the already-activated local Session binding (never an arbitrary per-turn id)
        // 2) launch log stream -> emit LogFrame
        // 3) openAIClient.responses().create(input=lastUserText, agent_session_id=sessionId)
        // 4) emit TextDelta*/TurnCompleted; close log stream
    }
    // Detach durable bindings; delete-only clean ephemeral bindings; then close clients.
    override suspend fun close() { /* release according to binding ownership */ }
}
```

The capability contract is enforced above the SDK adapter: Hosted receives no local `ToolSpec`s, a rejecting local
`ToolExecutor`, no client compactor, and no client model-switch or PromptAgent-management surface. The container's own
tools and model remain server concerns. TUI commands return typed unsupported outcomes; ACP has no corresponding
protocol command and relies on the same loop guard for automatic behavior.

## Lifecycle & ownership

A persisted local Hosted session **owns the identity** of exactly one Foundry `AgentSessionResource`; Foundry remains
the authority for its conversation state. This is intentionally durable because local JSONL cannot reconstruct Hosted
history. The binding is the configured Hosted agent name plus a caller-provided server id equal to the Konductor
session UUID. Arbitrary service-session ids are not adopted.

| Action | Hosted server-session effect |
|--------|------------------------------|
| TUI/ACP new | Reserve a distinct binding; create it lazily before the first turn. Never reuse the previous binding. |
| TUI resume/continue, ACP load | Validate the configured agent and reconnect with `getSession`; never create a replacement for a missing non-empty session. |
| `/new` or resume away from a persisted session | Detach it without stopping/deleting it, so it remains resumable. |
| Turn cancellation | Cancel local invoke/log collection but retain the binding; the server may already have advanced. |
| Provider, TUI, or ACP connection close | Detach persisted bindings and close clients; do not stop or delete them. |
| `--no-session` replacement/close | Best-effort `deleteSession` because the runtime, not a persisted local session, owns the resource. |
| Future explicit local-session deletion | Delete only that session's owned remote binding, then remove the local record. |

`ACTIVE` and auto-suspended `IDLE` sessions are invocable. Activation boundedly polls transient `CREATING`/`UPDATING`
states. `FAILED`, `DELETING`, `DELETED`, `EXPIRED`, an agent-name mismatch, or a missing binding with existing local
entries fails explicitly; local entries are never replayed into a fresh sandbox. A missing **entry-free** reserved
binding is the sole safe create/retry case. This also makes old Hosted JSONL files without a server id explicitly
non-resumable rather than misleadingly fresh.

Cancellation can leave the local audit trail ending at its persisted user entry while the service has additional
state. The next turn still follows the service-authoritative conversation. Idle sessions auto-suspend and expire 30
days after last activity, bounding detached resource lifetime. Konductor must not sweep unknown `listSessions` results:
they may belong to another install or client.

Cleanup is delete-only. Do not call `stopSession` and `deleteSession` together: live service evidence shows that race
returns `409`, while the SDK sample deletes a running session directly. Deployment cleanup (`deleteAgent`) remains a
separate admin action. The implementation contract and migration detail are in
[I028](../iterations/I028-hosted-session-lifecycle.md).

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [tui.md](tui.md) ·
[configuration.md](configuration.md) · [sessions.md](sessions.md)

**Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents/`
(`CodeAgentSample`, `SessionsSample`, `SessionLogStreamSample`, `SessionFilesSample`, + async variants).
