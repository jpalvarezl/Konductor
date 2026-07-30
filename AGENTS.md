# AGENTS.md

Guidance for AI coding agents working in this repository. Applies to any harness (Copilot CLI,
Claude, Cursor, Aider, etc.).

## Read this first: route through the task

Konductor is a Kotlin/JVM terminal coding-agent harness that **dog-foods** the team's Azure SDKs
(`com.azure:azure-ai-agents` / `azure-ai-projects` v2). Azure AI Foundry is the sole service platform target; retain
interfaces for SDK adaptation, lifecycle, agent-kind semantics, and tests—not hypothetical backend portability.

Before implementing:

1. If the prompt, branch, or pull request references a GitHub issue, read that issue for coordination, ownership,
   dependencies, blockers, and its delivery-packet link when one exists.
2. A delivery packet is a small repository-local implementation brief/context pack, not a second issue or status
   tracker. For accepted non-trivial work, read its scope, non-goals, acceptance, exact context, constraints, and
   validation. If the packet is already known, do not open the packet registry first.
3. Read only the exact spec headings, source symbols, tests, and targeted searches named by the packet. Design/triage
   issues and tiny focused fixes may intentionally have no packet; use their exact references and the owning spec.
4. If no issue or packet exists, use [`docs/index.md`](docs/index.md) to locate the owning spec, then inspect targeted
   source and tests. For roadmap/status, use open GitHub issues and milestones. Read
   [`docs/iterations/index.md`](docs/iterations/index.md) only to locate a packet or historical evidence.
5. Expand beyond the targeted context only when it is incomplete or contradicted by source.

Do not scan all of `docs/` or `src/` for orientation. The `docs-nav` skill follows the same bounded workflow. If GitHub
is unavailable, a linked delivery packet contains everything required to implement and validate the scoped work.

## Documentation ownership

- **`src/` and tests are implementation truth.**
- **`docs/spec/` is the stable intended contract.** Kotlin snippets are design sketches, not committed code.
- **GitHub issues own work coordination:** discussion, assignment, dependencies, blockers, priority/status labels, and
  closure.
- **GitHub milestones group related issues and pull requests into concrete outcomes or releases.**
- **`docs/iterations/` owns delivery contracts for accepted non-trivial work:** scope, non-goals, acceptance, targeted
  context, constraints, and validation. Packets do not duplicate issue discussion, assignees, priority, status, or
  burndown. Design/triage issues and tiny obvious fixes do not require one.
- **`docs/future.md` owns durable unscheduled direction, not a prioritized backlog.** Actionable work moves to an issue;
  stable design constraints move to the owning spec.
- **`docs/implementation-roadmap.md` and `docs/burndown.md` are the historical foundations record.** Do not add new
  work to them.

When behavior changes, update the owning spec in the same PR. When delivery completes, close the issue from the final
PR and move excluded follow-ups to `future.md` or focused issues.

Each field has one canonical owner. Do not copy packet acceptance criteria into issue comments or PR templates, and do
not copy issue status or discussion into packets. `docs/iterations/index.md` is a packet registry, not a status board.

## Service feedback — capture SDK/service painpoints

Konductor exists to **dog-food** the Azure SDKs, so when you hit a Foundry / Azure SDK or service rough edge,
**record it** in [`docs/service_feedback/`](docs/service_feedback/README.md) — one markdown file per feature area,
named `<feature_area>.md` (e.g. `hosted_agents.md`, `prompt_agents.md`). Name the exact SDK type/method + version,
the impact, the workaround Konductor adopted, and a suggested fix; then add a row to that folder's README table.
This feedback is a primary output of the exercise — don't leave it buried in code comments.

## Build, run, test

- **Toolchain:** JDK 25, Kotlin 2.4.0, and the checked-in Maven Wrapper (Maven 3.9.11). Prefer `./mvnw`
  (`.\mvnw.cmd` on Windows) so contributors and CI use the same Maven version. Sources live under `src/main/kotlin` and
  `src/test/kotlin` (non-default dirs, set in `pom.xml`), package root `com.konductor`.
  - The build targets JVM 25 bytecode, so **`JAVA_HOME` must point at a JDK 25** — Maven forks the
    surefire test JVM from `JAVA_HOME`, and a JDK 21 there fails tests with `class file version 69.0`.
- **Run the TUI:** `./mvnw` — the POM sets `defaultGoal` to `compile exec:java`, so a bare wrapper invocation compiles
  and launches the app (`com.konductor.MainKt`). Explicit form: `./mvnw compile exec:java`. The app builds
  a Foundry client at startup, so set `FOUNDRY_PROJECT_ENDPOINT` and `FOUNDRY_MODEL_NAME` (env or cwd `.env`)
  and sign in with `az login` first.
- **Run headless (ACP):** `java -jar target/konductor-0.2.0.jar acp`
  (or `./mvnw -q exec:java -Dexec.args="acp"`)
  speaks ACP over stdin/stdout instead of the TUI; stdout is the protocol channel, so logs go to stderr. See
  `docs/spec/acp.md`.
- **Runnable jar:** `./mvnw package` → shaded `target/konductor-0.2.0.jar`; run with
  `java -jar target/konductor-0.2.0.jar`.
- **All tests:** `./mvnw test`.
- **Single test class:** `./mvnw -Dtest=RectangleTest test`. **Single method:**
  `./mvnw -Dtest=RectangleTest#methodName test` (surefire + JUnit 5).
- The TUI takes over the terminal — log to a file, not stdout, while a session is active. It renders
  full-screen and won't behave inside a captured/piped stdout.
- **Distribution/release work:** read [`docs/distribution.md`](docs/distribution.md) before running the `dist` profile
  or changing release packaging; `jpackage` is per-OS and local rebuilds require `clean`.

## Architecture boundaries

Preserve the layered boundary in [`docs/spec/architecture.md`](docs/spec/architecture.md):
TUI/ACP → `AgentLoop` → `AgentProvider` → Azure SDK chokepoints. Frontends never call the SDK directly, and providers
never depend on Lanterna or ACP types. Use the relevant delivery packet's source map for implementation entry
points instead of relying on a duplicated architecture snapshot here.

## Conventions

- **Kotlin style (`.editorconfig`):** 4-space indent, `max_line_length = 120`, LF line endings, final
  newline, trimmed trailing whitespace, UTF-8.
- **TUI components are stateless renderers:** implement `TuiComponent.render(canvas, bounds, state)`
  and draw from `AppState` — do not hold mutable UI state in the component.
- **Localization is presentation-only:** route human-facing TUI/CLI copy through semantic `AppStrings` methods.
  Keep command/tool names and schemas, persisted identifiers, model prompts, raw tool results, and ACP fields stable.
- **Built-in tools are cwd-contained:** reuse `resolveInCwd`/`displayPath` for paths; do not bypass lexical and symlink
  containment. Preserve bounded tool output and strict UTF-8 handling because tool results re-enter model context.
- **Tests:** JUnit 5 via `kotlin-test-junit5`, named `*Test.kt`, mirroring the main package path under
  `src/test/kotlin`. Foundry service tests use Azure Core Test modes: unset `AZURE_TEST_MODE` is PLAYBACK, RECORD
  writes a local recording, LIVE bypasses recording, and `@LiveOnly` marks scenarios that cannot play back.
- **Lanterna + JNA:** JNA/JNA-Platform are required for the native Windows console backend (see the
  comment in `pom.xml`); keep them if you touch terminal setup or the shaded jar.
