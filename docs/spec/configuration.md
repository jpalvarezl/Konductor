# Configuration

How Konductor is configured: environment variables, authentication, settings, and pre-provider model resolution. A
first Prompt TUI run needs a project endpoint and signed-in Azure identity; it can resolve a missing model from that
project before constructing a provider. Prompt ACP remains noninteractive and requires a local model.

> Code blocks are illustrative design sketches, not committed implementation.

## Environment variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | yes | Foundry project endpoint: `https://{resource}.ai.azure.com/api/projects/{project}` |
| `FOUNDRY_MODEL_NAME` | no (Prompt TUI); local model required for Prompt ACP | Model deployment name, e.g. `gpt-5-mini`; skips startup discovery |
| `FOUNDRY_AGENT_CONTAINER_IMAGE` | Hosted only | Container image for hosted-agent sessions |
| `KONDUCTOR_HOSTED_AGENT_NAME` | Hosted | Named hosted agent to deploy/select ([hosted-agents.md](hosted-agents.md)) |
| `KONDUCTOR_PROMPT_AGENT_NAME` | opt-in Prompt | Optionally bind a persisted **PromptAgent** ([providers.md](providers.md#persisted-prompt-agents-promptagent)) |
| `KONDUCTOR_CONFIG_DIR` | no | Override config dir (default `~/.konductor`) |
| `KONDUCTOR_LOCALE` | no | Frontend locale as a BCP-47 tag, e.g. `en`, `es`, or `fr-CA` |

## Authentication

Konductor authenticates with **Entra ID** via `DefaultAzureCredential`; the SDK applies the AAD scope
`https://ai.azure.com/.default`. Bootstrap configuration resolves the endpoint and credential once, then
`FoundryProjectRuntime` owns them for typed catalog construction and all later per-session provider clients. This lets
an unresolved interactive Prompt query the SDK-free deployment catalog before provider construction. A locally
resolved Prompt model and every Hosted run skip that startup query.

After startup, the TUI also queries the catalogs for `/model list`, `/model <deployment>`, or `/connections`. Those
in-session failures keep the current session usable: if an explicit `/model <name>` request cannot be validated,
Konductor switches with a warning; if discovery proves the name absent, it rejects the switch and points to
`/model list` or deployment setup. This nonfatal command fallback is distinct from unresolved-model bootstrap, where a
catalog failure leaves no executable target and exits before provider/session creation. Session-specific configuration
may change model or PromptAgent binding, but cannot replace the runtime's project endpoint or credential.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
```

`DefaultAzureCredential` tries, in order: environment service-principal vars, managed identity, the Azure CLI
(`az login`), Azure Developer CLI, etc. For local dev, `az login` to the tenant/subscription that owns the Foundry
project is the simplest path. No API keys are stored by Konductor.

## Settings file

Optional JSON at `~/.konductor/settings.json` (global) and `<cwd>/.konductor/settings.json` (project). In the TUI, a
permitted, trusted project file overrides global values; trust resolution happens before the project file is read. ACP
process bootstrap never reads the launch-cwd project file. Its per-session factory may read a project file only after
#26 resolves trust for the `session/new` cwd or persisted `session/load` cwd.

```json
{
  "provider": { "agentKind": "prompt", "model": "gpt-5-mini", "promptAgentName": null, "hostedAgentName": null, "hostedAgentContainerImage": null, "temperature": 0.2, "maxToolIterations": 30 },
  "tools": { "allow": ["read", "ls", "find", "grep", "bash", "write", "edit"], "maxOutputBytes": 16384 },
  "compaction": { "enabled": true, "reserveTokens": 16384, "keepRecentTokens": 20000, "contextWindow": 128000 },
  "systemPromptAppend": null
}
```

Configuration is resolved in two shapes. The bootstrap shape may lack a Prompt model and is sufficient only for
project/catalog composition. Finalization requires a non-blank model and returns the effective `Configuration` used by
providers, contexts, loops, and frontends; downstream code does not make `Configuration.model` nullable.

```kotlin
data class BootstrapConfig(
    val projectEndpoint: String,
    val model: String?,
    val agentKind: AgentKind = AgentKind.Prompt,
    // remaining effective settings
)

data class Configuration(
    val projectEndpoint: String,
    val model: String,
    val agentKind: AgentKind = AgentKind.Prompt,
    val promptAgentName: String? = null,   // opt-in persisted PromptAgent (Prompt)
    val hostedAgentName: String? = null,   // named hosted agent to deploy/select (Hosted)
    val hostedAgentContainerImage: String? = null,
    val temperature: Double? = null,
    val toolAllow: Set<String>? = null,
    val maxToolIterations: Int = 30,      // cap on tool-call rounds per turn (Prompt loop convergence guard)
    val compaction: CompactionSettings = CompactionSettings(),
    val systemPromptOverride: String? = null,
    val systemPromptAppend: String? = null,
)
```

## Precedence

For a new run, highest wins:

```
CLI flags  >  environment variables  >  trusted project settings.json  >  global settings.json  >  built-in defaults
```

Workspace trust is resolved before project `.konductor/settings.json` is read or merged. An untrusted/ignored project
file contributes no model or other setting; global operator settings remain eligible. The trust identity, persistence,
and noninteractive unknown-trust policy are owned by [I001](../iterations/I001-workspace-context-and-trust.md), not by
model bootstrap; #26's implementation is a hard prerequisite for implementing this startup design.

For TUI resume, the session header's persisted cwd must have the same #26-normalized workspace identity as the launch
cwd before trust or project settings are resolved. `--continue` selects the most recent session only for that identity.
This prevents selecting a session for one workspace while loading project configuration, context, or tools from
another. Once trusted settings establish effective agent kind, the selected session's strict schema/binding kind must
match it. Both Prompt-over-Hosted and Hosted-over-Prompt fail before project/catalog construction, discovery, provider
construction, service operations, or writes. `--continue` neither skips an opposite-kind newest session nor silently
starts new, and no kind migration occurs in this slice.

ACP has no launch workspace. Before opening the transport, bootstrap may read only CLI flags, the real process
environment (`System.getenv`, without a cwd `.env` overlay), and global settings. The launch cwd's
`.konductor/settings.json` is never read or carried into ACP session defaults. Process startup resolves the effective
agent kind first. Prompt ACP requires at least one model among the eligible process sources and retains normalized CLI,
process-environment, and global candidates separately. After a Prompt `session/new` identifies its cwd, the session
factory applies #26 trust and may merge that workspace's permitted project settings; the Prompt-only model precedence
is CLI > real process environment > trusted per-session project > global. The Prompt resolver returns both the value
and one process-local provenance tag (`CLI`, `PROCESS_ENVIRONMENT`, `TRUSTED_SESSION_PROJECT`, or `GLOBAL`) so layers
are not accidentally flattened. Only the value enters final Prompt `Configuration` and the Prompt session header; the
provenance tag is neither persisted nor a new settings field.

Hosted ACP requires no process-default model. Once startup resolves Hosted kind, explicit `--model` is rejected before
the protocol transport opens. A Hosted `session/new` ignores model values from the real process environment, permitted
per-session project settings, and global settings, does not run Prompt model provenance resolution, and finalizes the
shared non-null model shape plus the new header with canonical `hosted`. Common cwd trust/project composition may still
run, but its model value cannot alter the Hosted execution target. Project settings cannot replace the process-scoped
project endpoint/credential or agent kind for either kind. `session/load` instead uses strict persisted metadata and
does not merge a model fallback.

### Startup model resolution

Bootstrap model resolution is deterministic and finishes before provider or new-session construction:

1. **Hosted** performs no deployment lookup or selection and does not apply Prompt model-source precedence. Once
   startup has resolved effective Hosted mode, explicit `--model` is a localized CLI conflict; ACP reports it before
   opening the protocol transport. Ambient `FOUNDRY_MODEL_NAME` and `provider.model` may remain configured for Prompt
   mode, but Hosted ignores them as execution inputs and requires no process-default model. Hosted finalization supplies
   canonical `hosted` for the shared non-null model shape, and every new Hosted session records that value regardless of
   ambient process/project/global model settings. No model provenance tag is produced. A resumed/loaded valid Hosted
   binding accepts either that placeholder or a legacy non-blank model value, preserves it byte-for-byte as opaque
   compatibility metadata, and never silently canonicalizes the header. The Hosted binding/version—not `modelName`—
   identifies provider kind; missing/blank model metadata is still corrupt.
2. **TUI resume/continue** first enforces the workspace-identity rule above, then requires the persisted kind to match
   effective configured kind. Prompt v1 is Prompt; only a valid complete Hosted v2 binding is Hosted. A mismatch in
   either direction is a localized early error, never a migration/provider switch/header rewrite. For a matched Prompt
   session, the existing non-blank `modelName` is authoritative. Ambient defaults do not rewrite it; changing its model
   remains the explicit in-session `/model` operation. `--model` together with `--resume`/`--continue` is rejected as a
   localized CLI conflict. A blank persisted model is invalid metadata and fails without discovery. If `--continue`
   has no identity match, startup becomes the new-session path in steps 3–4; an opposite-kind newest match is an error,
   not "no match."
3. **New Prompt TUI** uses `--model`, then `FOUNDRY_MODEL_NAME`, then trusted project `provider.model`, then global
   `provider.model`. Any such value preserves the current discovery-free startup; it is not startup-validated, and the
   first service request remains authoritative.
4. **Unresolved Prompt TUI** lists only project DTOs with type `ModelDeployment`. Zero deployments produces localized
   stderr setup guidance and a non-zero exit. One is selected automatically and handed to the TUI as a visible startup
   message. Many enter the bounded, cancellable pre-provider selector described in
   [tui.md](tui.md#startup-model-bootstrap). Cancellation exits cleanly.
5. **Prompt ACP** requires a non-blank process-default from CLI, the real process environment, or global settings
   before protocol startup. It does not discover, prompt, read launch-cwd project inputs, or use a future load to fill
   bootstrap state. For Prompt `session/new` only, preserve source provenance, resolve trust for the requested cwd, and
   finalize the model as CLI > real process environment > trusted per-session project settings > global settings. Use
   that value for provider/context preparation and persist it unchanged in the Prompt header; source provenance remains
   process-local. Hosted `session/new` follows step 1 instead: it ignores those ambient candidates and persists
   canonical `hosted`. Finalization and preparation precede header commit for either kind. If `session/new` returns an
   error, it closes prepared resources and leaves no listable/loadable local session or remote Hosted resource. For
   `session/load`, decode persisted kind first and reject Prompt/Hosted mismatch in either direction before trust,
   project settings, discovery, runtime, service operations, or writes. A matching Prompt load uses only its persisted
   non-blank model; missing/blank/corrupt metadata is a method-level protocol error with no fallback or rewrite.
   Matching Hosted loads follow compatibility rules.

A configured/bound PromptAgent does not satisfy local model resolution. This slice does not fetch its definition to
infer model metadata; the local fallback remains required by shared context/session/compaction state even though the
service-side PromptAgent definition owns the actual request model.

Inventory selection is process-local. It does not update either settings file or create a separate preference. If
startup continues into an ordinary new session, that session records the selected name through existing `modelName`
metadata. Empty inventory, discovery failure, or cancellation creates no provider or session and writes no metadata.
Zero/failure user guidance is emitted to stderr after terminal restoration; the one-deployment notice remains visible
in the TUI's startup/system messages and is not persisted as conversation history. Selector truncation copy is
informational and only required to stay visible while the selector is active; terminal zero/failure reports are durable
and never exist only in transient alternate-screen state.

### Frontend locale

`KONDUCTOR_LOCALE` is bootstrap configuration rather than Foundry runtime configuration. TUI startup resolves it from
the real environment or cwd `.env` before ordinary configuration loading. ACP resolves it only from the real process
environment; its launch cwd is not a project/session input. When absent, Konductor uses the operating system's display
locale. JVM resource fallback ultimately selects the English root bundle.

There is no localized CLI flag or settings-file field for locale yet. Commands, option names, tool/schema identifiers,
persisted data, model prompts, raw logs/tool results, and ACP protocol content remain stable regardless of locale.

## Selecting the provider / agent kind

- `--agent-kind prompt|hosted` (or `provider.agentKind` in settings) picks the [provider](providers.md).
- `--model <name>` overrides `FOUNDRY_MODEL_NAME` for a new Prompt session and skips startup discovery. It conflicts
  with `--resume`/`--continue`, whose persisted model remains authoritative, and with effective Hosted mode, where a
  model is not an execution input. In the TUI, `/model list` discovers project deployments after startup.
  `/model <deployment>` validates an exact name when discovery is available, rejects a confirmed missing deployment,
  and otherwise preserves free-text selection with an explicit unvalidated warning.
- `acp` and `--acp` select the headless ACP frontend; all other positional arguments are rejected.
- `--help`/`-h` and `--version`/`-V` run before Foundry configuration or provider construction.
- **Prompt (opt-in):** `KONDUCTOR_PROMPT_AGENT_NAME` / `provider.promptAgentName` binds the loop to a persisted **PromptAgent**
  (selected/created via [`/agent`](tui.md#slash-commands)); empty ⇒ ephemeral. See
  [providers.md](providers.md#persisted-prompt-agents-promptagent) and
  [M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in).
- Hosted reads `KONDUCTOR_HOSTED_AGENT_NAME` + `FOUNDRY_AGENT_CONTAINER_IMAGE` ([hosted-agents.md](hosted-agents.md)).

## Tools & compaction knobs

- `--tools a,b,c` enables exactly those built-ins.
- `--exclude-tools x,y` subtracts names from `tools.allow`, or from all built-ins when no allow-list is configured.
- `--no-tools` enables an empty client-side tool set. These three Prompt-only flags are mutually exclusive;
  read-only = `--tools read,ls,find,grep` ([tools.md](tools.md)).
- `compaction.*` tunes the context-window behavior ([compaction.md](compaction.md)).

Session flags are TUI-only. `--no-session` is incompatible with `--resume`/`--continue`, `--resume` is incompatible
with `--continue`, and `--model` is incompatible with either resume form. Effective Hosted mode also rejects
`--model`; ambient model environment/settings values are tolerated but ignored by Hosted. ACP mode rejects TUI session
flags rather than silently ignoring them.

## Related docs

[providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) · [tools.md](tools.md) ·
[compaction.md](compaction.md) · [development.md](../development.md)
