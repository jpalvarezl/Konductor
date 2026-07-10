# AGENTS.md

Guidance for AI coding agents working in this repository. Applies to any harness (Copilot CLI,
Claude, Cursor, Aider, etc.).

## Read this first: use the active iteration

Konductor is a Kotlin/JVM terminal coding-agent harness that **dog-foods** the team's Azure SDKs
(`com.azure:azure-ai-agents` / `azure-ai-projects` v2).

Before implementing:

1. Open [`docs/iterations/index.md`](docs/iterations/index.md).
2. Read the active or relevant ready `I###-*.md` file.
3. Read only the spec headings, source entry points, tests, and targeted searches in its context pack.
4. Expand beyond that packet only when it is incomplete or contradicted by source.

Do not read all of `docs/` or `src/` for orientation. [`docs/index.md`](docs/index.md) is the documentation router for
unplanned questions, and the `docs-nav` skill follows the same bounded workflow.

## Documentation ownership

- **`src/` and tests are implementation truth.**
- **`docs/spec/` is the stable intended contract.** Kotlin snippets are design sketches, not committed code.
- **`docs/iterations/` owns active and ready implementation work.** Update the iteration checklist in the same change
  that starts, changes, or completes the work.
- **`docs/future.md` owns unscheduled ideas only.** Promote selected work into an iteration rather than maintaining
  two plans.
- **GitHub issues own defect reports and design discussion.** Link them from an iteration; do not copy their full
  content into docs.
- **`docs/implementation-roadmap.md` and `docs/burndown.md` are the historical foundations record.** Do not add new
  work to them.

When behavior changes, update the owning spec in the same PR. When an iteration completes, move excluded follow-ups
to `future.md` or a focused issue and link the merged PRs from the iteration.

Each work item has one canonical tracker. `docs/iterations/index.md` is a routing index only; specs, issues, backlog
entries, and historical files must not carry a second actionable checklist for scheduled work.

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

## Architecture boundaries

Preserve the layered boundary in [`docs/spec/architecture.md`](docs/spec/architecture.md):
TUI/ACP → `AgentLoop` → `AgentProvider` → Azure SDK chokepoints. Frontends never call the SDK directly, and providers
never depend on Lanterna or ACP types. Use the active iteration's source map for the current implementation entry
points instead of relying on a duplicated architecture snapshot here.

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
