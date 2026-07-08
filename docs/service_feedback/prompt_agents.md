# Persisted Prompt agents (PromptAgent) — SDK/service feedback

Dog-fooding feedback from building Konductor's opt-in **persisted PromptAgent** path (M2.5,
`provider/inference/AzureInferenceClient.kt`) against `com.azure:azure-ai-agents` **2.2.0** (openai-java 4.14.0)
— the *create a `PromptAgentDefinition` version → reference it for inference → list/switch* flow driven from the
**client-owned** Responses loop (distinct from the container-owned Hosted provider).

Legend: **Impact** = what it cost us · **Workaround** = what Konductor does today · **Suggestion** = what would
have removed the friction.

> _Status: ⚠️ **Code-complete, not yet verified live end-to-end.** Items below are grounded in the
> `azure-ai-agents` 2.2.0 sources + serialization behavior reproduced in isolation; the full
> `createAgentVersion` → agent-scoped `responses()` round-trip still needs a live Foundry project + `az login`
> (see burndown M2.5). #1 in particular was proven via SDK-source + isolated serialization; #4 is an open
> question to confirm live._

---

## 1. `PromptAgentDefinition.setTools` takes `FunctionTool(Map<String, BinaryData>)` — a silent serialization footgun

Baking tools into a persisted agent uses the Agents `FunctionTool(String name, Map<String, BinaryData> parameters,
Boolean strict)`, where `parameters` is the JSON-schema flattened to one `BinaryData` **per top-level key**
(`type`, `properties`, `required`). The obvious construction — `BinaryData.fromString(schemaJsonText)` — is
**silently wrong**: `FunctionTool.toJson` writes each value via `element.writeTo(jsonWriter)`, and a
`fromString`-backed `BinaryData` resolves to `StringContent.writeTo` → `JsonWriter.writeString(...)`, so the value
is emitted as an **escaped JSON string** rather than structured JSON:

```jsonc
// BinaryData.fromString(...)  (WRONG — what you naturally reach for):
"parameters": { "type": "\"object\"", "properties": "{\"path\":{\"type\":\"string\"}}", "required": "[\"path\"]" }
// BinaryData.fromObject(...)  (correct — raw JSON via SerializableContent.writeTo → writeRawValue):
"parameters": { "type": "object", "properties": { "path": { "type": "string" } }, "required": ["path"] }
```

- **Impact:** a call that compiles and *looks* right bakes a structurally-invalid tool schema into the agent
  version. There's no compile-time or obvious runtime signal — it surfaces only as a rejected `createAgentVersion`
  or a broken persisted definition. It cost us a real bug (caught in code review, after the offline tests passed,
  because the SDK boundary isn't unit-tested).
- **Workaround:** `parameters.mapValues { BinaryData.fromObject(it.value.toPlainValue()) }` — raw JSON, mirroring
  the per-request openai-java mapping.
- **Suggestion:** accept a structured schema type (a `JsonSerializable`, or a typed builder like openai-java's
  `FunctionTool.Parameters`) instead of `Map<String, BinaryData>`; and/or make `writeMapField` emit valid-JSON
  `fromString` values as raw JSON. At minimum, document that map values must be `fromObject`.

## 2. Two different `FunctionTool` types for the same tool — the schema is mapped twice

Per-request tools use openai-java `com.openai.models.responses.FunctionTool` (typed `Parameters` builder +
`JsonValue.from(...)`); baking the same tools into a `PromptAgentDefinition` uses Azure
`com.azure.ai.agents.models.FunctionTool` (`Map<String, BinaryData>`, above). A harness that both drives the
Responses loop **and** creates the agent from one `ToolRegistry` must convert its neutral tool spec twice, with
two different value encodings — and (see #1) those encodings have different footguns.

- **Impact:** duplicated, divergent mapping code (`toFunctionTool` for requests vs `toAzurePromptTool` for baking);
  a bug can hit only the baked-agent path and be masked at runtime because the correct per-request tools are still
  sent every turn.
- **Workaround:** two parallel mapping functions from Konductor's `ToolSpec`.
- **Suggestion:** let `PromptAgentDefinition.setTools` accept the openai-java tool types, or share a single tool
  model across the request and agent-definition surfaces.

## 3. Referencing a PromptAgent for inference: the documented `AzureCreateResponseOptions.setAgentReference` is wrapper-only

The natural per-request binding — `AzureCreateResponseOptions().setAgentReference(new AgentReference(name))` passed
to `ResponsesClient.createAzureResponse(options, params)` — exists **only** on the Azure
`ResponsesClient`/`ResponsesAsyncClient` wrapper. If you own the blocking openai-java `OpenAIClient` directly
(Konductor does, because the Azure `ResponsesAsyncClient` wrapper discards the closeable `OpenAIClient` and leaks a
non-daemon stream-handler thread — see the M1 dependency notes), that option is unreachable. The working
alternative is `AgentsClientBuilder.buildAgentScopedOpenAIClient(name)` (same `responses()` API), while omitting
request `instructions`. The two mechanisms are parallel and uncross-referenced, so it's non-obvious that the
raw-client path is `buildAgentScopedOpenAIClient`.

- **Impact:** time lost discovering how to bind an agent from the raw openai client; the documented wrapper option
  is a dead end for a client that (necessarily) owns the openai client directly.
- **Workaround:** `buildAgentScopedOpenAIClient(promptAgentName)` + omit `instructions` + send the dynamic preamble
  (cwd/os/date, context files) as a leading `developer` input item so the agent's baked instructions still apply.
- **Suggestion:** cross-document the two mechanisms and when to use each; and/or expose agent-reference binding as a
  sanctioned request-level property on openai-java `ResponseCreateParams` (an official `agent_reference` body
  shape) so raw-client callers have a per-request option too.

## 4. (Open) Is a freshly-created PromptAgent version immediately referenceable, or does it need activation polling?

Hosted agent versions provision asynchronously and must be polled to `ACTIVE` before use
([hosted_agents.md](hosted_agents.md) #1). It is not yet confirmed whether
`createAgentVersion(name, PromptAgentDefinition)` returns an immediately-referenceable version or also requires
polling `getAgentVersionDetails(...).status`. If the latter, the same "no create-and-wait" friction applies to the
Prompt path too.

- **Status:** unverified — needs a live Foundry project to confirm; flagged so the `/agent create` → immediately
  chat flow is validated (and a poll added if required).
