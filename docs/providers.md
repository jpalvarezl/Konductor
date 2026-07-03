# Providers

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md) for confirmed decisions
> and the SDK grounding facts (client/auth, Responses loop, function tools, streaming, `ResponseUsage`).

**Purpose:** the `AgentProvider` seam ("API provider / HTTP factory") and the Azure **Prompt** provider. The Hosted
provider has its own doc: [hosted-agents.md](hosted-agents.md).

## The `AgentProvider` interface

TODO: define the interface — start a turn from the current transcript + agent context, return the **unified event
stream** (text deltas, tool calls, tool results, usage, turn-complete, errors). Keep it agnostic of who owns the loop.

## Agent-kind mapping

TODO: how provider implementations map to Foundry agent kinds (Prompt now, Hosted in its own doc; Workflow/External
in [future.md](future.md)).

## Configuration & resolution

TODO: how a provider is selected and configured. See [configuration.md](configuration.md).

## Azure Prompt provider

### Client & auth
TODO: `new AgentsClientBuilder().endpoint(FOUNDRY_PROJECT_ENDPOINT).credential(DefaultAzureCredential).buildResponsesClient()`;
AAD scope `https://ai.azure.com/.default`. (`AIProjectClientBuilder.buildOpenAIClient()` is the projects-SDK alternative.)

### Inference loop
TODO: `ResponsesClient.createAzureResponse(AzureCreateResponseOptions, ResponseCreateParams.Builder)`; set
`model`, `instructions`, `input` (full transcript — see multi-turn decision in index), `tools`, `temperature`.

### Function-tool loop (harness-owned)
TODO: `FunctionTool(name, params, strict)` → detect `response.output()` `ResponseOutputItem.functionCall()`
(`callId/name/arguments`) → execute via [tools.md](tools.md) → submit `ResponseFunctionToolCallOutputItem` next turn.

### Streaming
TODO: `createStreamingAzureResponse(...)` → `ResponseStreamEvent` (`outputTextDelta`, `functionCallArgumentsDelta`).

### Usage / tokens
TODO: `Response.usage()` → `ResponseUsage` feeds the context tracker + [compaction.md](compaction.md).

## References

- [index.md](index.md) · [hosted-agents.md](hosted-agents.md) · [agent-context.md](agent-context.md) · [tools.md](tools.md)
- Samples: `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/` (`CreateResponse`, `FunctionCallSync`, `CreateResponseWithConversation`).
