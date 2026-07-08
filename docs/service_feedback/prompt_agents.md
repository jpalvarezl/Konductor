# Persisted Prompt agents (PromptAgent) — SDK/service feedback

Dog-fooding feedback from building Konductor's opt-in **persisted PromptAgent** path (M2.5,
`provider/inference/AzurePromptAgentInferenceClient.kt` + the `AzurePromptAgentClient` lifecycle client) against
`com.azure:azure-ai-agents` **2.2.0** (openai-java 4.14.0)
— the *create a `PromptAgentDefinition` version → reference it for inference → list/switch* flow driven from the
**client-owned** Responses loop (distinct from the container-owned Hosted provider).

Legend: **Impact** = what it cost us · **Workaround** = what Konductor does today · **Suggestion** = what would
have removed the friction.

> _Status: ✅ **Verified live end-to-end** (2026-07-08, `foundry-sdk-deployment`/`java`, gpt-5.2): the standalone
> lifecycle client mints a `PromptAgentDefinition` version with real tool schemas, and `AzurePromptAgentInferenceClient`
> — agent-scoped — invokes it with an input-only payload + a leading `developer` preamble item (response `"pong"`).
> #1 (tool-schema baking) and the **invocation shape** (#5) were proven live; #4 (no endpoint config / activation
> polling needed for a PromptAgent) is confirmed; #6 (developer/system input items need an explicit `type`) was
> isolated with a live probe matrix and worked around._

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

## 4. A freshly-created PromptAgent version is immediately usable — no activation polling (unlike Hosted)

Hosted agent versions provision asynchronously and must be polled to `ACTIVE` before use
([hosted_agents.md](hosted_agents.md) #1). **Confirmed live** that a PromptAgent is different:
`createAgentVersion(name, PromptAgentDefinition)` returns a version that is immediately referenceable — **no**
`getAgentVersionDetails(...).status` poll and **no endpoint configuration** (`updateAgentDetails`) needed, unlike
the Hosted flow. Good — but the asymmetry with Hosted is undocumented and easy to over-engineer for.

- **Status:** ✅ verified live (create → invoke worked with no polling / no endpoint config).

## 5. Invoking a PromptAgent needs the preview header + an **input-only** payload — non-obvious, vague error

Holding the agent-scoped client (`buildAgentScopedOpenAIClient(name)` → base URL
`{endpoint}/agents/{name}/endpoint/protocols/openai`), the invocation is **not** a drop-in for the ephemeral call:

- The client must be built with **`allowPreview(true)`** so the request carries the agent preview-features header.
  Without it the call fails with a **vague `400 "Invalid payload"`**; with it, the same over-full request fails
  with the *actionable* `400 "Not allowed when agent is specified."`
- The request must be **input-only** — send just the transcript `input`. `model` is tolerated, but `instructions`
  and `tools` are **rejected**: the agent supplies them from its baked definition. (The SDK's own
  `tools/AgentToAgentSync.java` sample invokes with exactly `ResponseCreateParams.builder().input(...)`.)

- **Impact:** the natural "same `responses()` API, just a different client" assumption is wrong; a full-payload
  call hits both 400s live. Cost real debugging (probe-per-payload) to discover the rules.
- **Workaround:** the agent-bound path builds `allowPreview(true).buildAgentScopedOpenAIClient(name)` and
  `buildParams` emits input-only (no model/instructions/tools).
- **Suggestion:** make `allowPreview` unnecessary (or document it as required for agent endpoints); return the
  precise "not allowed when agent is specified" error regardless of the preview header; and either ignore
  agent-supplied fields or document the input-only contract in the agent-scoped client's javadoc.

## 6. `developer`/`system` input messages need an explicit `type: "message"` — the endpoint mis-defaults a missing type to `""`

The agent-scoped Responses endpoint **rejects** a `developer`- or `system`-role easy-input message that omits the
item `type`, with a confusing `400 Invalid value: ''. Supported values are: 'message', 'function_call', …`. The
*same* message with a `user`/`assistant` role is accepted. openai-java serializes all four roles **identically** —
`{"role":"…","content":"…"}` with `type` omitted, the documented shorthand for an "easy input message" — confirmed
by serializing the item locally with the SDK's own `com.openai.core.jsonMapper()`:

```jsonc
{"content":"x","role":"user"}       // accepted (endpoint defaults type -> "message")
{"content":"x","role":"developer"}  // rejected (endpoint defaults type -> "")
{"content":"x","role":"system"}     // rejected (endpoint defaults type -> "")
```

So the empty `""` is injected **service-side**: the endpoint defaults a missing item `type` to `"message"` for
user/assistant but to `""` for developer/system, then validates the `""` and rejects it. A single live probe matrix
(agent-scoped client, `allowPreview(true)`) pins it down:

| input items | result |
| --- | --- |
| `[user]`, `[user, user]` | ✅ (type omitted) |
| `[developer, user]`, `[system, user]` | ❌ `400 Invalid value: ''` |
| `[developer(type=message), user]`, `[user(type=message), user(type=message)]` | ✅ |

- **Impact:** the dynamic environment preamble (cwd/os/date + context files) rides `input` as a leading `developer`
  item — the only channel left, since #3/#5 forbid request `instructions`. With the omitted-`type` shorthand that
  every user/assistant message already uses, it fails with an error that enumerates item **types** and never names
  the real trigger (a role + a missing type the *service* blanked). Cost a live probe matrix + a local
  wire-serialization dump to isolate that the SDK sends nothing wrong.
- **Workaround:** set `type` explicitly on every easy-input message —
  `EasyInputMessage.builder().role(role).content(text).type(EasyInputMessage.Type.MESSAGE)`.
- **Suggestion:** default a missing input-item `type` to `"message"` for **all** roles (matching both the user/
  assistant behavior here and the public OpenAI Responses API); and surface the offending item index/role in the
  error instead of a bare `Invalid value: ''`.
