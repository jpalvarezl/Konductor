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
- **`src/` has the M0–M5 foundations plus partial M6/ACP polish.** The Prompt path has streamed local tools,
  persisted JSONL sessions, compaction, and opt-in persisted PromptAgents; the Hosted provider is live-verified.
  The TUI and ACP frontend share `AgentLoop` and expose streaming, cancellation, sessions, and tool activity.
  Trust/context-file loading, richer CLI controls, and some ACP/TUI polish remain.

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

## Service feedback — capture SDK/service painpoints

Konductor exists to **dog-food** the Azure SDKs, so when you hit a Foundry / Azure SDK or service rough edge,
**record it** in [`docs/service_feedback/`](docs/service_feedback/README.md) — one markdown file per feature area,
named `<feature_area>.md` (e.g. `hosted_agents.md`, `prompt_agents.md`). Name the exact SDK type/method + version,
the impact, the workaround Konductor adopted, and a suggested fix; then add a row to that folder's README table.
This feedback is a primary output of the exercise — don't leave it buried in code comments.

## Build, run, test

- **Toolchain:** JDK 25, Maven 3.9+, Kotlin 2.4.0. Sources live under `src/main/kotlin` and
  `src/test/kotlin` (non-default dirs, set in `pom.xml`), package root `com.konductor`.
  - The build targets JVM 25 bytecode, so **`JAVA_HOME` must point at a JDK 25** — Maven forks the
    surefire test JVM from `JAVA_HOME`, and a JDK 21 there fails tests with `class file version 69.0`.
- **Run the TUI:** `mvn` — the POM sets `defaultGoal` to `compile exec:java`, so a bare `mvn` compiles
  and launches the app (`com.konductor.MainKt`). Explicit form: `mvn compile exec:java`. The app builds
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
- `agent/` — `AgentLoop` owns the active `Session`, append-as-produced persistence, history reconstruction,
  compaction, and event folding; `AgentContextFactory` builds the stable + dynamic prompt pieces.
- `provider/` — `ProviderFactory` selects Prompt or Hosted. `PromptProvider` owns the client-side function-tool loop;
  `provider/inference/` contains ephemeral and persisted-PromptAgent Responses clients; `provider/hosted/` contains
  the server-owned hosted-session implementation.
- `tool/`, `session/`, `compaction/` — cwd-contained built-ins, JSONL session lifecycle, and client-side summary
  compaction respectively.
- `core/` — render-facing `AppState` / `ChatMessage` / `InputState`, plus serializable transcript/domain models.
- `conversation/ConversationController` — TUI adapter for streamed events and `/new`, `/resume`, `/name`,
  `/session`, `/compact`, `/model`, and `/agent`; `submitAsync()` drives cancelable background turns.
- `tui/` — rendering + input only: `component/` (`TranscriptView`, `StatusBar`, `PromptInputView`, each
  implementing `TuiComponent { render(canvas, bounds, state) }`), plus `layout/`, `style/Theme`, `text/`, and
  `TerminalCanvas`.
- `acp/KonductorAcpAgent.kt` — **headless** frontend: one persisted `AgentLoop` per ACP session, with
  create/load/list, streamed text/tool/log updates, and turn cancellation over stdio.

## Architecture direction (see docs/spec/architecture.md)

The layered design is now substantially implemented: TUI/ACP → `AgentLoop` → `AgentProvider` (`Prompt` and
`Hosted`) → Azure SDK chokepoints, with `SessionStore`, `Compactor`, `ToolRegistry`, and `Configuration` as
cross-cutting services. Remaining work should preserve the boundary: **frontends never call the SDK directly and
providers never touch Lanterna or ACP types**; each layer depends only on the layer below plus the domain model.

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
`KONDUCTOR_HOSTED_AGENT_NAME`. On Windows PowerShell set vars with `$env:FOUNDRY_PROJECT_ENDPOINT = "..."`.
