# Configuration

How Konductor is configured: environment variables, authentication, and settings. All values have safe defaults so
a first run needs only an endpoint, a model, and a signed-in Azure identity.

> Code blocks are illustrative design sketches, not committed implementation.

## Environment variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | yes | Foundry project endpoint: `https://{resource}.ai.azure.com/api/projects/{project}` |
| `FOUNDRY_MODEL_NAME` | yes (Prompt) | Model deployment name, e.g. `gpt-5-mini` |
| `FOUNDRY_AGENT_CONTAINER_IMAGE` | Hosted only | Container image for hosted-agent sessions |
| `KONDUCTOR_HOSTED_AGENT_NAME` | Hosted | Named hosted agent to deploy/select ([hosted-agents.md](hosted-agents.md)) |
| `KONDUCTOR_PROMPT_AGENT_NAME` | opt-in Prompt | Optionally bind a persisted **PromptAgent** ([providers.md](providers.md#persisted-prompt-agents-promptagent)) |
| `KONDUCTOR_CONFIG_DIR` | no | Override config dir (default `~/.konductor`) |

## Authentication

Konductor authenticates with **Entra ID** via `DefaultAzureCredential`; the SDK applies the AAD scope
`https://ai.azure.com/.default`.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
```

`DefaultAzureCredential` tries, in order: environment service-principal vars, managed identity, the Azure CLI
(`az login`), Azure Developer CLI, etc. For local dev, `az login` to the tenant/subscription that owns the Foundry
project is the simplest path. No API keys are stored by Konductor.

## Settings file

Optional JSON at `~/.konductor/settings.json` (global) and `<cwd>/.konductor/settings.json` (project). Project
overrides global.

```json
{
  "provider": { "agentKind": "prompt", "model": "gpt-5-mini", "promptAgentName": null, "hostedAgentName": null, "hostedAgentContainerImage": null, "temperature": 0.2 },
  "tools": { "allow": ["read", "ls", "find", "grep", "bash", "write", "edit"], "maxOutputBytes": 16384 },
  "compaction": { "enabled": true, "reserveTokens": 16384, "keepRecentTokens": 20000 },
  "systemPromptAppend": null
}
```

```kotlin
data class Config(
    val projectEndpoint: String,
    val model: String,
    val agentKind: AgentKind = AgentKind.Prompt,
    val promptAgentName: String? = null,   // opt-in persisted PromptAgent (Prompt)
    val hostedAgentName: String? = null,   // named hosted agent to deploy/select (Hosted)
    val hostedAgentContainerImage: String? = null,
    val temperature: Double? = null,
    val toolAllow: Set<String>? = null,
    val compaction: CompactionSettings = CompactionSettings(),
    val systemPromptOverride: String? = null,
    val systemPromptAppend: String? = null,
)
```

## Precedence

Highest wins:

```
CLI flags  >  environment variables  >  project settings.json  >  global settings.json  >  built-in defaults
```

## Selecting the provider / agent kind

- `--agent-kind prompt|hosted` (or `provider.agentKind` in settings) picks the [provider](providers.md).
- `--model <name>` overrides `FOUNDRY_MODEL_NAME` (Prompt).
- **Prompt (opt-in):** `KONDUCTOR_PROMPT_AGENT_NAME` / `provider.promptAgentName` binds the loop to a persisted **PromptAgent**
  (selected/created via [`/agent`](tui.md#slash-commands)); empty ⇒ ephemeral. See
  [providers.md](providers.md#persisted-prompt-agents-promptagent) and
  [M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in).
- Hosted reads `KONDUCTOR_HOSTED_AGENT_NAME` + `FOUNDRY_AGENT_CONTAINER_IMAGE` ([hosted-agents.md](hosted-agents.md)).

## Tools & compaction knobs

- `--tools a,b,c` / `--exclude-tools x` set the active tool set; read-only = `--tools read,ls,find,grep`
  ([tools.md](tools.md)).
- `compaction.*` tunes the context-window behavior ([compaction.md](compaction.md)).

## Related docs

[providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) · [tools.md](tools.md) ·
[compaction.md](compaction.md) · [development.md](../development.md)
