# Konductor

Konductor is a Kotlin/JVM terminal coding-agent harness that dogfoods Azure AI Agents / Foundry SDKs. Its Prompt
path streams model responses through 7 cwd-contained coding tools, persists JSONL sessions, compacts long context,
and can bind to a persisted PromptAgent. A separate Hosted provider drives server-owned agent sessions. The same
core runs behind the Lanterna TUI and a headless ACP agent.

Current implementation work is organized as bounded context packs under
[`docs/iterations/`](docs/iterations/index.md); [`docs/index.md`](docs/index.md) routes stable design and backlog
questions.

## Stack

- Kotlin 2.4.0 / JVM 25
- Maven
- [Lanterna](https://github.com/mabe02/lanterna) for terminal rendering and keyboard input
- Azure AI Agents / Projects SDKs for Foundry-backed inference
- [Agent Client Protocol](https://agentclientprotocol.com) for headless mode
- JNA/JNA Platform for Lanterna's native Windows console support

## Run

CLI help and version output do not require Foundry configuration:

```bash
java -jar target/konductor-0.1.0-SNAPSHOT.jar --help
java -jar target/konductor-0.1.0-SNAPSHOT.jar --version
```

Configure a Foundry project first, either in the shell or in a gitignored cwd `.env` file:

```bash
export FOUNDRY_PROJECT_ENDPOINT="https://<resource>.ai.azure.com/api/projects/<project>"
export FOUNDRY_MODEL_NAME="gpt-5-mini"
# Optional BCP-47 locale for TUI/CLI copy:
export KONDUCTOR_LOCALE="en"
az login
./mvnw
```

The checked-in Maven Wrapper downloads Maven 3.9.11, so no system Maven installation is required. The POM's
`defaultGoal` is `compile exec:java`, so bare `./mvnw` compiles and runs the TUI. Explicit form:

```bash
./mvnw compile exec:java
```

Package a standalone runnable jar:

```bash
./mvnw package
java -jar target/konductor-0.1.0-SNAPSHOT.jar
```

Useful startup flags:

```text
--agent-kind prompt|hosted
--model <deployment>
--continue | --resume <session-uuid> | --no-session
--name <session-name>
--tools <names> | --exclude-tools <names> | --no-tools
acp | --acp
```

`--tools` enables exactly the named built-ins, while `--exclude-tools` subtracts from the configured/default
set. The three tool-selection flags are mutually exclusive.

See [docs/spec/configuration.md](docs/spec/configuration.md) and [docs/spec/sessions.md](docs/spec/sessions.md).

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

- Type text and press `Enter` to send it to the model; `Shift+Enter` inserts a newline where supported.
- While a turn is running, `Esc` cancels it. At an idle prompt, `Esc`, `/quit`, or `Ctrl+C` exits.
- `Up` / `Down` scroll the transcript one line.
- `PageUp` / `PageDown` scroll by a page.
- `Home` / `End` move within the input.
- Session/model commands: `/new`, `/resume`, `/name`, `/session`, `/compact`, `/model`.
- PromptAgent commands: `/agent`, `/agent list`, `/agent use <name>`, `/agent create [name]`.

## Project layout

```text
src/main/kotlin/com/konductor
├── Main.kt                     # Application entry point; chooses TUI vs ACP
├── acp                         # Headless Agent Client Protocol frontend
├── agent                       # AgentLoop and prompt/context assembly
├── compaction                  # Context tracking and summary compaction
├── config                      # Environment/settings loading
├── core                        # App state plus session/domain models
├── conversation                # TUI adapter onto AgentLoop
├── provider                    # Prompt/Hosted providers and Azure SDK seams
├── session                     # JSONL persistence and history reconstruction
├── tool                        # Built-in cwd-contained coding tools
└── tui                         # Terminal UI runtime, layout, styling, components
```

The code is intentionally split into small seams so it can grow modularly:

- Keep rendering-only concerns in `tui` and app behavior in `conversation`/`agent`/`core`.
- Keep SDK-specific code inside `provider/inference/` and `provider/hosted/`.
- Add new panes under `tui/component` and reusable widgets under `tui/widget` as the interface grows.
