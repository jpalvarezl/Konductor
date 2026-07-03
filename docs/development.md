# Development

How to build, run, and debug Konductor locally against a Foundry project.

## Prerequisites

- **JDK 21** (the project targets JVM 21).
- **Maven 3.9+**.
- An **Azure Foundry project** and a **model deployment** you can reach.
- **Azure CLI** signed in (`az login`) to the tenant/subscription owning the project (for `DefaultAzureCredential`).

## Build & run

The project sets Maven's `defaultGoal` to `compile exec:java`, so a bare `mvn` compiles and runs the TUI:

```bash
mvn                      # compile + run
mvn compile exec:java    # explicit form
mvn package              # build a shaded runnable jar
java -jar target/konductor-0.1.0-SNAPSHOT.jar
```

## Point at a Foundry project

```bash
export FOUNDRY_PROJECT_ENDPOINT="https://<resource>.ai.azure.com/api/projects/<project>"
export FOUNDRY_MODEL_NAME="gpt-5-mini"
# Hosted provider only:
export FOUNDRY_AGENT_CONTAINER_IMAGE="<image>"
export KONDUCTOR_AGENT_NAME="konductor-coding-agent"
az login
mvn
```

On Windows PowerShell use `$env:FOUNDRY_PROJECT_ENDPOINT = "..."`. See [configuration.md](configuration.md) for all
variables and settings.

## Project layout

```
src/main/kotlin/com/konductor
├── Main.kt              # entry point → TuiApp().run()
├── core/               # domain model (Entry, Session, ToolCall/Result, Usage, AgentContext)
├── agent/              # AgentLoop, ContextWindowTracker, ToolExecutor wiring
├── provider/           # AgentProvider seam + prompt/ + hosted/
├── session/            # SessionStore (JSONL)
├── compaction/         # Compactor
├── tool/               # ToolRegistry + built-in tools
├── config/             # Config loading
├── conversation/       # existing seam → adapter onto AgentLoop
└── tui/                # Lanterna rendering + input
```

New subsystems (`agent/`, `provider/`, `session/`, `compaction/`, `tool/`, `config/`) are introduced by the
milestones in [implementation-roadmap.md](implementation-roadmap.md). The existing `conversation/ConversationController`
is the seam to replace.

## Dependencies to add (M0)

Add to `pom.xml` (see [implementation-roadmap.md](implementation-roadmap.md#m0)):

- `com.azure:azure-ai-agents` (2.2.0) — `ResponsesClient`, agents/sessions, `FunctionTool`.
- `com.azure:azure-ai-projects` (2.2.0) — project client (optional; `buildOpenAIClient`).
- `com.azure:azure-identity` — `DefaultAzureCredential`.
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — the agent loop / `Flow`.
- A JSON library (`kotlinx-serialization` or Jackson) — session JSONL + tool schemas.

## Debugging

- **Auth/endpoint errors** are the most common first-run failure. Verify `az account show`, the endpoint format,
  and that your identity has access to the project. See [configuration.md](configuration.md).
- Run against a **mock provider** (an `AgentProvider` that returns canned `AgentEvent`s) to develop the TUI/session
  layers without hitting the service.
- Increase Azure SDK HTTP logging via the standard `AZURE_LOG_LEVEL` / builder `httpLogOptions` when diagnosing
  request/response issues.
- The TUI takes over the terminal; log to a file rather than stdout while a session is active.

## Related docs

[index.md](index.md) · [architecture.md](architecture.md) · [configuration.md](configuration.md) ·
[implementation-roadmap.md](implementation-roadmap.md)
