# Hosted Agents

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md) → "SDK grounding facts —
> Hosted provider". **All of this is preview** — build the client with `allowPreview(true)`.

**Purpose:** the **Hosted** provider — a containerized agent deployed to Foundry that owns its own loop. The harness
is a client + orchestrator + I/O bridge (server sessions, log streaming, session files).

## When to use Hosted vs Prompt

TODO: contrast table. Prompt = harness-owned loop, client-side tools, client compaction. Hosted = server-owned loop,
container tools, **no client-side compaction** (server owns context).

## Deploy a code-based agent

TODO: `createAgentVersionFromCode(agentName, HostedAgentDefinition, CodeFileDetails(codeZip), description, metadata)`;
`downloadAgentCodeWithResponse`. Describe the code-zip layout and `HostedAgentDefinition` (cpu/memory/env/container).

## Configure the agent endpoint

TODO: `AgentEndpointConfig` + `VersionSelector` (`FixedRatioVersionSelectionRule`) +
`ProtocolConfiguration.setResponses(ResponsesProtocolConfiguration)` via `updateAgentDetails`.

## Sessions (server-side)

TODO: `createSession` / `getSession` / `listSessions` / `deleteSession` / `stopSession`;
`AgentSessionResource` (`getAgentSessionId`, `getStatus` / `AgentSessionStatus`). Needs `FOUNDRY_AGENT_CONTAINER_IMAGE`.

## Invoke the agent

TODO: `builder.buildAgentScopedOpenAIClient(agentName)` →
`responses().create(ResponseCreateParams… putAdditionalBodyProperty("agent_session_id", …))`.

## Log streaming

TODO: `getSessionLogStreamWithResponse(agentName, version, sessionId, …)` → SSE `SessionLogEvent` frames; how they
render in the TUI (see [tui.md](tui.md)).

## Session files

TODO: upload / download / list session files to bridge local files ↔ container.

## References

- [index.md](index.md) · [providers.md](providers.md) · [tui.md](tui.md) · [configuration.md](configuration.md)
- Samples: `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/hostedagents/`
  (`CodeAgentSample`, `SessionsSample`, `SessionLogStreamSample`, `SessionFilesSample`, + async variants).
