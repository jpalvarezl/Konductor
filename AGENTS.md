# AGENTS.md

Guidance for AI coding agents working in this repository. Applies to any harness (Copilot CLI,
Claude, Cursor, Aider, etc.).

## ⚠️ Read this first: spec vs. code

Konductor is a Kotlin/JVM terminal coding-agent harness that **dog-foods** the team's Azure SDKs
(`com.azure:azure-ai-agents` / `azure-ai-projects` v2). The docs describe the target system, while
`src/` is an implementation-in-progress:

- **`docs/` is the target specification.** Kotlin in those docs is design sketches — **not committed
  code**. Do not assume every class, package, or interface described there exists yet (`SessionStore`,
  `ToolRegistry`, `Compactor`, `HostedProvider`, etc.).
- **`src/` is currently M1-complete on the Prompt path.** The Lanterna TUI and headless ACP frontend share
  `AgentLoop` → `PromptProvider` → `AzureInferenceClient` for streamed single-turn Foundry inference.
  Tools, sessions, compaction, persisted PromptAgents, and hosted agents remain pending/in progress.

Before implementing anything, confirm current state by reading `src/` (and `docs/burndown.md` for
at-a-glance progress), not the docs. To find the right spec, start at [`docs/index.md`](docs/index.md) — the
**documentation map** (one-line description per doc) that also holds the status banner and confirmed decisions;
every doc opens with a one-line purpose statement, and `rg <term> docs/` (or the `docs-nav` skill) pinpoints
specifics fast. `docs/spec/architecture.md` (the keystone) explains the intended design;
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
  and launches the app (`com.konductor.MainKt`). Explicit form: `mvn compile exec:java`. The M1 app builds
  a Foundry client at startup, so set `FOUNDRY_PROJECT_ENDPOINT` and `FOUNDRY_MODEL_NAME` (env or cwd `.env`)
  and sign in with `az login` first.
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

## Distribution & releases

Konductor ships as a self-contained per-OS `jpackage` bundle (bundles a JRE — nothing to install to run
it), built from the shaded jar by the Maven `dist` profile:

- **Build locally:** `mvn -Pdist package` → bundle under `target/dist/` (needs `JAVA_HOME` = JDK 25). Re-run
  locally with `mvn clean` first — jpackage marks its output read-only, so a plain rebuild can't overwrite it.
- **Per-OS artifacts:** jpackage can't cross-compile — Windows → app-image (zipped), Linux → `.deb`, macOS →
  `.dmg`. `jpackage.type` and the Windows-only `jpackage.win.console` are overridable Maven properties.
- **Releases:** `.github/workflows/release.yml` triggers on a `v*` tag, fans out across the three OS runners,
  and attaches the artifacts to the GitHub Release. Cut one with `git tag v0.1.0 && git push origin v0.1.0`.

Full usage, per-OS overrides, and the deferred size-reduction notes: [`docs/distribution.md`](docs/distribution.md).

## Current architecture (what actually exists)

The interactive TUI is a single-threaded Lanterna app; rendering is still synchronous from one `AppState`,
while agent turns stream through the Prompt stack:

- `Main.kt` builds `Configuration`, `PromptProvider(AzureInferenceClient)`, and `AgentContext`, then runs either
  `TuiApp` or the headless ACP frontend.
- `agent/` — `AgentLoop` owns the in-memory transcript for the run; `AgentContextFactory` builds the M1 system
  prompt/environment header; `NoToolExecutor` is the no-tools placeholder until M2.
- `provider/` — `AgentProvider`/`AgentEvent` seam, `PromptProvider` streamed loop, and the `inference/` vendor seam;
  `AzureInferenceClient` is the SDK/OpenAI Responses chokepoint.
- `core/` — render-facing `AppState` / `ChatMessage` / `InputState`, plus roadmap domain models (`Entry`,
  `Session`, `ToolCall`, `Usage`, etc.).
- `conversation/ConversationController.submit()` — TUI adapter that handles `/quit`/`/exit`, runs an `AgentLoop`
  turn synchronously, and folds streamed `AgentEvent`s into `AppState`.
- `tui/` — rendering + input only: `component/` (`TranscriptView`, `StatusBar`, `PromptInputView`, each
  implementing `TuiComponent { render(canvas, bounds, state) }`), plus `layout/`, `style/Theme`, `text/`, and
  `TerminalCanvas`.
- `acp/KonductorAcpAgent.kt` — **headless** frontend: runs the [ACP](https://agentclientprotocol.com) agent over
  stdio when `Main` receives the `acp` arg; one `AgentLoop` per ACP session, streaming model deltas as ACP updates.

## Target architecture (planned — see docs/spec/architecture.md)

The docs define the layered design Konductor is growing into: TUI/ACP → agent loop (coroutines) →
`AgentProvider` seam (`Prompt` and `Hosted` kinds) → Azure SDKs, with cross-cutting `SessionStore` (JSONL),
`Compactor`, `ToolRegistry`, and `Config`. M1 has the Prompt inference path; remaining roadmap work adds real
`tool/`, `session/`, `compaction/`, and hosted-provider implementations. When implementing, respect the intended
layering: **the TUI never calls the SDK directly and the provider never touches Lanterna**; each layer depends only
on the layer below plus the domain model.

## Conventions

- **Kotlin style (`.editorconfig`):** 4-space indent, `max_line_length = 120`, LF line endings, final
  newline, trimmed trailing whitespace, UTF-8.
- **TUI components are stateless renderers:** implement `TuiComponent.render(canvas, bounds, state)`
  and draw from `AppState` — do not hold mutable UI state in the component.
- **Tests:** JUnit 5 via `kotlin-test-junit5`, named `*Test.kt`, mirroring the main package path under
  `src/test/kotlin`.
- **Lanterna + JNA:** JNA/JNA-Platform are required for the native Windows console backend (see the
  comment in `pom.xml`); keep them if you touch terminal setup or the shaded jar.

## Running against Azure Foundry

Configured via env vars (`docs/development.md`, `docs/spec/configuration.md`) or a gitignored cwd `.env`:
`FOUNDRY_PROJECT_ENDPOINT` (`https://<resource>.ai.azure.com/api/projects/<project>`), `FOUNDRY_MODEL_NAME`,
plus `az login` for `DefaultAzureCredential`. Hosted provider adds `FOUNDRY_AGENT_CONTAINER_IMAGE` and
`KONDUCTOR_AGENT_NAME`. On Windows PowerShell set vars with `$env:FOUNDRY_PROJECT_ENDPOINT = "..."`.
