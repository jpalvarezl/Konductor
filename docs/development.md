# Development

How to build, run, and debug Konductor locally against a Foundry project.

## Prerequisites

- **JDK 25** (the project targets JVM 25).
- No Maven installation is required: the checked-in Maven Wrapper downloads Maven 3.9.11.
- An **Azure Foundry project** and a **model deployment** you can reach.
- **Azure CLI** signed in (`az login`) to the tenant/subscription owning the project (for `DefaultAzureCredential`).

## Build & run

The project sets Maven's `defaultGoal` to `compile exec:java`, so the wrapper compiles and runs the TUI:

```bash
./mvnw                      # compile + run
./mvnw compile exec:java    # explicit form
./mvnw package              # build a shaded runnable jar
java -jar target/konductor-0.1.0-SNAPSHOT.jar
java -jar target/konductor-0.1.0-SNAPSHOT.jar --help
java -jar target/konductor-0.1.0-SNAPSHOT.jar --version
```

`--help` and `--version` are handled before `.env`, settings, authentication, or provider construction.
Unknown options/positional arguments fail with a usage hint instead of being ignored.

## Point at a Foundry project

```bash
export FOUNDRY_PROJECT_ENDPOINT="https://<resource>.ai.azure.com/api/projects/<project>"
export FOUNDRY_MODEL_NAME="gpt-5-mini"
# Optional frontend locale (BCP-47); falls back to the OS display locale and English resources:
export KONDUCTOR_LOCALE="en"
# Hosted provider only:
export FOUNDRY_AGENT_CONTAINER_IMAGE="<image>"
export KONDUCTOR_HOSTED_AGENT_NAME="konductor-coding-agent"
az login
./mvnw
```

On Windows PowerShell use `$env:FOUNDRY_PROJECT_ENDPOINT = "..."`. See [configuration.md](spec/configuration.md) for all
variables and settings.

## Project layout

```
src/main/kotlin/com/konductor
├── Main.kt              # entry point → TUI or headless ACP
├── acp/                 # ACP protocol adapter + per-session runtime construction
├── core/               # domain model (Entry, Session, ToolCall/Result, Usage, AgentContext)
├── agent/              # AgentLoop and prompt/context assembly
├── provider/           # AgentProvider seam + prompt/ + hosted/
├── session/            # SessionStore (JSONL)
├── compaction/         # Compactor
├── tool/               # ToolRegistry + built-in tools
├── config/             # Config loading
├── i18n/               # ResourceBundle-backed frontend copy
├── conversation/       # existing seam → adapter onto AgentLoop
└── tui/                # Lanterna rendering + input
```

Locale bundles live under `src/main/resources/com/konductor/i18n/` using standard JVM names:
`messages.properties` for the English root and, for example, `messages_es.properties` or
`messages_fr_CA.properties` for translations.

For current implementation work, use the exact source and test entry points in
[`iterations/index.md`](iterations/index.md). The foundations roadmap is historical.

## Key dependencies (added in M0)

Already present in `pom.xml`:

- `com.azure:azure-ai-agents` (2.2.0) — the Foundry Responses/Agents client + `FunctionTool`.
- `com.azure:azure-ai-projects` (2.2.0) — project client.
- `com.azure:azure-identity` — `DefaultAzureCredential`.
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — the agent loop / `Flow`.
- `org.jetbrains.kotlinx:kotlinx-serialization-json` — config, tool schemas, and (later) session JSONL.

## Debugging

- **Auth/endpoint errors** are the most common first-run failure. Verify `az account show`, the endpoint format,
  and that your identity has access to the project. See [configuration.md](spec/configuration.md).
- Run against a **mock provider** (an `AgentProvider` that returns canned `AgentEvent`s) to develop the TUI/session
  layers without hitting the service.
- Increase Azure SDK HTTP logging via the standard `AZURE_LOG_LEVEL` / builder `httpLogOptions` when diagnosing
  request/response issues.
- The TUI takes over the terminal; log to a file rather than stdout while a session is active.

## Related docs

[index.md](index.md) · [iterations](iterations/index.md) · [architecture.md](spec/architecture.md) ·
[configuration.md](spec/configuration.md)
