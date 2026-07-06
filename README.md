# Konductor

Konductor is a Kotlin/JVM terminal UI scaffold for a chat-style application. The initial UI mirrors the common layout used by coding-agent TUIs: a scrollable transcript pane with a message composer pinned to the bottom.

## Stack

- Kotlin/JVM 21
- Maven
- [Lanterna](https://github.com/mabe02/lanterna) for terminal rendering and keyboard input
- JNA/JNA Platform for Lanterna's native Windows console support

## Run

```bash
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

## Headless (ACP) mode

Pass `acp` to run headless as an [Agent Client Protocol](https://agentclientprotocol.com) agent over stdin/stdout (JSON-RPC) instead of the TUI, so an ACP client (e.g. Zed) can drive it:

```bash
java -jar target/konductor-0.1.0-SNAPSHOT.jar acp
```

Currently an echo bridge (Phase A); see [docs/acp.md](docs/acp.md).

## Controls

- Type text and press `Enter` to add a message.
- `/quit`, `Esc`, or `Ctrl+C` exits.
- `Up` / `Down` scroll the transcript one line.
- `PageUp` / `PageDown` scroll by a page.
- `Home` / `End` move within the input.

## Project layout

```text
src/main/kotlin/com/konductor
├── Main.kt                     # Application entry point
├── core                        # App/domain state and message models
├── conversation                # Message submission/controller seam
└── tui                         # Terminal UI runtime, layout, styling, components
```

The scaffold is intentionally split into small seams so it can grow modularly:

- Add new panes under `tui/component`.
- Add reusable widgets under `tui/widget` as the interface grows.
- Keep rendering-only concerns in `tui` and app behavior in `conversation`/`core`.
- Replace `ConversationController` with real agent/process orchestration without changing the terminal runtime.
