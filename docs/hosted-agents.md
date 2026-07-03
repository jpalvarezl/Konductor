# Hosted Agents

The **Hosted provider** targets a **containerized agent** deployed to Foundry. Unlike the
[Prompt provider](providers.md), the container **owns the agent loop and its own context** — Konductor is a client,
orchestrator, and I/O bridge. It deploys/selects an agent version, opens a **server-side session**, invokes the
agent through an **agent-scoped Responses client**, streams **session logs**, and moves **session files** in and
out of the container.

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

Because the server owns state, `HostedProvider.runTurn` sends the user message to the session, relays the response,
and streams logs — it does **not** rebuild `input` from local history and does **not** call the `Compactor`.

## Configuration

| Env var | Purpose |
|---------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | Project endpoint (shared with Prompt) |
| `FOUNDRY_AGENT_CONTAINER_IMAGE` | Container image for hosted sessions |
| `KONDUCTOR_AGENT_NAME` | Logical hosted-agent name to deploy/select |

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
val session: AgentSessionResource = HostedAgentsSampleUtils
    .createAgentAndSession(agentsClient, agentName, image).session          // or agentsClient.createSession(...)
val sessionId = session.agentSessionId
val status = session.status                                                 // AgentSessionStatus
```

Session management: `createSession` / `getSession(agentName, sessionId)` / `listSessions` / `deleteSession` /
`stopSession`. Konductor maps its `SessionRef` to `agentName + agentSessionId`. *Sample:* `hostedagents/SessionsSample.java`.

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
    // holds: AgentsClient (allowPreview), agent-scoped OpenAIClient, agentName, current sessionId
    override fun runTurn(request: TurnRequest, tools: ToolExecutor): Flow<AgentEvent> = flow {
        // 1) ensure session (create/reuse via request.sessionRef)
        // 2) launch log stream -> emit LogFrame
        // 3) openAIClient.responses().create(input=lastUserText, agent_session_id=sessionId)
        // 4) emit TextDelta*/TurnCompleted; close log stream
    }
    override suspend fun close() { /* stopSession/deleteSession as configured */ }
}
```

## Lifecycle & cleanup

Deleting/stopping sessions and agents matters (they consume compute). The provider should `stopSession` on turn
cancel and `deleteSession` on close; deployment cleanup (`deleteAgent`) is a separate admin action. See the
`finally` blocks in the hosted samples.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [tui.md](tui.md) ·
[configuration.md](configuration.md) · [sessions.md](sessions.md)

**Samples:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents/`
(`CodeAgentSample`, `SessionsSample`, `SessionLogStreamSample`, `SessionFilesSample`, + async variants).
