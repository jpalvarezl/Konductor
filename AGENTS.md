# AGENTS.md

Guidance for AI coding agents working in this repository. Applies to any harness (Copilot CLI,
Claude, Cursor, Aider, etc.).

## ⚠️ Read this first: spec vs. code

Konductor is a Kotlin/JVM terminal coding-agent harness that **dog-foods** the team's Azure SDKs
(`com.azure:azure-ai-agents` / `azure-ai-projects` v2). There is a large gap between what is
**documented** and what is **built**:

- **`docs/` is a complete written specification for a project that does not exist yet.** The Kotlin
  in those docs is design sketches — **not committed code**. Do not assume any class, package, or
  interface described there (`AgentProvider`, `AgentLoop`, `SessionStore`, `ToolRegistry`, providers,
  compaction, etc.) exists in `src/`.
- **`src/` is still the original Lanterna TUI scaffold** — a scrollable transcript + bottom composer
  that echoes input. The Azure SDK dependencies are **not** in `pom.xml` yet.

Before implementing anything, confirm current state by reading `src/` (and `docs/burndown.md` for
at-a-glance progress), not the docs. `docs/index.md` (status banner + confirmed decisions) and
`docs/spec/architecture.md` (the keystone) explain the intended design;
`docs/implementation-roadmap.md` stages the build as milestones M0–M6.

## Progress tracking — keep `docs/burndown.md` current

[`docs/burndown.md`](docs/burndown.md) is the at-a-glance record of what's done vs. pending across the
roadmap. **Read it first** to learn where things stand instead of re-deriving status from the code.

- **Agents:** when you start or finish roadmap work, update `docs/burndown.md` in the *same change* —
  tick the relevant `- [x]` box, add sub-items where the work grew, and bump the _Last updated_ line.
  Net-new work outside the roadmap goes under its "Ad-hoc / added work" section. If you can't verify a
  box is truly done, leave it unchecked.
- **Developers:** if you complete or change the status of something by hand, update the burndown so the
  next agent or human starts from an accurate picture.

## Build, run, test

- **Toolchain:** JDK 25, Maven 3.9+, Kotlin 2.4.0. Sources live under `src/main/kotlin` and
  `src/test/kotlin` (non-default dirs, set in `pom.xml`), package root `com.konductor`.
  - The build targets JVM 25 bytecode, so **`JAVA_HOME` must point at a JDK 25** — Maven forks the
    surefire test JVM from `JAVA_HOME`, and a JDK 21 there fails tests with `class file version 69.0`.
- **Run the TUI:** `mvn` — the POM sets `defaultGoal` to `compile exec:java`, so a bare `mvn` compiles
  and launches the app (`com.konductor.MainKt`). Explicit form: `mvn compile exec:java`.
- **Run headless (ACP):** `java -jar target/konductor-0.1.0-SNAPSHOT.jar acp` (or `mvn -q exec:java -Dexec.args="acp"`)
  speaks ACP over stdin/stdout instead of the TUI; stdout is the protocol channel, so logs go to stderr. See
  `docs/spec/acp.md`.
- **Runnable jar:** `mvn package` → shaded `target/konductor-0.1.0-SNAPSHOT.jar`; run with
  `java -jar target/konductor-0.1.0-SNAPSHOT.jar`.
- **All tests:** `mvn test`.
- **Single test class:** `mvn -Dtest=RectangleTest test`. **Single method:**
  `mvn -Dtest=RectangleTest#methodName test` (surefire + JUnit 5).
- The TUI takes over the terminal — log to a file, not stdout, while a session is active. It renders
  full-screen and won't behave inside a captured/piped stdout.

## Current architecture (what actually exists)

The interactive TUI is a single-threaded Lanterna app; everything renders synchronously from one `AppState`:

- `Main.kt` → `TuiApp().run()` opens a Lanterna `TerminalScreen` and runs a blocking read/render
  `eventLoop` (`tui/TuiApp.kt`).
- `core/` — plain domain state: `AppState` (messages + `InputState` + scrollback), `ChatMessage` /
  `MessageRole`, `InputState`.
- `tui/` — rendering + input only: `component/` (`TranscriptView`, `StatusBar`, `PromptInputView`,
  each implementing the `TuiComponent { render(canvas, bounds, state) }` interface), plus `layout/`,
  `style/Theme`, `text/`, and `TerminalCanvas`.
- `conversation/ConversationController.submit()` — **the seam.** Currently echoes input and handles
  `/quit` `/exit`. This is the single point to replace with real agent orchestration.
- `acp/KonductorAcpAgent.kt` — **headless** frontend (added): runs the [ACP](https://agentclientprotocol.com)
  agent over stdio (coroutines) when `Main` receives the `acp` arg; currently an echo bridge. See `docs/spec/acp.md`.

## Target architecture (planned — see docs/spec/architecture.md)

The docs define a layered design to grow into: TUI → agent loop (coroutines) → `AgentProvider` seam
(`Prompt` and `Hosted` kinds) → Azure SDKs, with cross-cutting `SessionStore` (JSONL), `Compactor`,
`ToolRegistry`, `Config`. Target package layout adds `agent/`, `provider/{prompt,hosted}/`,
`session/`, `compaction/`, `tool/`, `config/` alongside the existing `tui/`, `core/`, `conversation/`.
When implementing, respect the intended layering: **the TUI never calls the SDK directly and the
provider never touches Lanterna**; each layer depends only on the layer below plus the domain model.

## Conventions

- **Kotlin style (`.editorconfig`):** 4-space indent, `max_line_length = 120`, LF line endings, final
  newline, trimmed trailing whitespace, UTF-8.
- **TUI components are stateless renderers:** implement `TuiComponent.render(canvas, bounds, state)`
  and draw from `AppState` — do not hold mutable UI state in the component.
- **Tests:** JUnit 5 via `kotlin-test-junit5`, named `*Test.kt`, mirroring the main package path under
  `src/test/kotlin`.
- **Lanterna + JNA:** JNA/JNA-Platform are required for the native Windows console backend (see the
  comment in `pom.xml`); keep them if you touch terminal setup or the shaded jar.

## Running against Azure Foundry (once the Prompt provider exists)

Configured via env vars (`docs/development.md`, `docs/spec/configuration.md`): `FOUNDRY_PROJECT_ENDPOINT`
(`https://<resource>.ai.azure.com/api/projects/<project>`), `FOUNDRY_MODEL_NAME`, plus `az login` for
`DefaultAzureCredential`. Hosted provider adds `FOUNDRY_AGENT_CONTAINER_IMAGE` and
`KONDUCTOR_AGENT_NAME`. On Windows PowerShell set vars with `$env:FOUNDRY_PROJECT_ENDPOINT = "..."`.
