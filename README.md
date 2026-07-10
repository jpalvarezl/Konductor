# Konductor

Konductor is a Kotlin/JVM terminal coding-agent harness. The current Prompt path streams real Azure Foundry model responses into a Lanterna TUI and into a headless ACP agent; tools, sessions, compaction, and hosted agents are still being built out.

## Stack

- Kotlin 2.4.0 / JVM 25
- Maven
- [Lanterna](https://github.com/mabe02/lanterna) for terminal rendering and keyboard input
- Azure AI Agents / Projects SDKs for Foundry-backed inference
- [Agent Client Protocol](https://agentclientprotocol.com) for headless mode
- JNA/JNA Platform for Lanterna's native Windows console support

## Run

Configure a Foundry project first, either in the shell or in a gitignored cwd `.env` file:

```bash
export FOUNDRY_PROJECT_ENDPOINT="https://<resource>.ai.azure.com/api/projects/<project>"
export FOUNDRY_MODEL_NAME="gpt-5-mini"
az login
mvn
```

This project sets Maven's `defaultGoal` to `compile exec:java`, so bare `mvn` compiles and runs the TUI. If you prefer the explicit form, use:

```bash
mvn compile exec:java
```

Package a standalone runnable jar:

```bash
mvn package
java -jar target/konductor-0.1.0-SNAPSHOT.jar
```

## Distribution

Konductor can build a **self-contained, per-OS bundle** with [`jpackage`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html) — the user needs no JRE installed. The Maven `dist` profile drives it:

```bash
mvn -Pdist package
```

jpackage can't cross-compile, so each OS is built on its own machine: **Windows** app-image (zipped), **Linux** `.deb`, **macOS** `.dmg` (unsigned).

Releases are automated — push a version tag and GitHub Actions builds all three and attaches them to the GitHub Release:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

See [docs/distribution.md](docs/distribution.md) for per-OS overrides and gotchas.

## Headless (ACP) mode

Pass `acp` to run headless as an [Agent Client Protocol](https://agentclientprotocol.com) agent over stdin/stdout (JSON-RPC) instead of the TUI, so an ACP client (e.g. Zed) can drive it:

```bash
java -jar target/konductor-0.1.0-SNAPSHOT.jar acp
```

ACP mode uses the same streamed Prompt inference stack as the TUI. See [docs/spec/acp.md](docs/spec/acp.md)
for the inventory of supported JSON-RPC methods (`session/new`, `load`, `list`, `prompt`, `cancel`) and a
manual handshake you can pipe in.

## Controls

- Type text and press `Enter` to send it to the model.
- `/quit`, `Esc`, or `Ctrl+C` exits.
- `Up` / `Down` scroll the transcript one line.
- `PageUp` / `PageDown` scroll by a page.
- `Home` / `End` move within the input.

## Project layout

```text
src/main/kotlin/com/konductor
├── Main.kt                     # Application entry point; chooses TUI vs ACP
├── acp                         # Headless Agent Client Protocol frontend
├── agent                       # AgentLoop and prompt/context wiring
├── config                      # Environment/settings loading
├── core                        # App state plus session/domain models
├── conversation                # TUI adapter onto AgentLoop
├── provider                    # AgentProvider seam and Prompt/Azure inference implementation
└── tui                         # Terminal UI runtime, layout, styling, components
```

The code is intentionally split into small seams so it can grow modularly:

- Keep rendering-only concerns in `tui` and app behavior in `conversation`/`agent`/`core`.
- Keep SDK-specific code behind `provider/inference/AzureInferenceClient`.
- Add new panes under `tui/component` and reusable widgets under `tui/widget` as the interface grows.
