# TUI (Terminal GUI)

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md). Builds on the existing
> Lanterna scaffold (`tui/`).

**Purpose:** how the terminal UI renders the conversation and streams agent output.

## Layout

TODO: transcript pane / status bar / composer (already scaffolded in `tui/component/`). Resize behavior.

## Event loop

TODO: how the Lanterna input loop coordinates with the async agent loop; marshalling provider events to the render
thread (see [architecture.md](architecture.md)).

## Rendering agent output

TODO: streaming text deltas; tool-call / tool-result display; **hosted-agent log-stream** frames
([hosted-agents.md](hosted-agents.md)); errors.

## Status bar

TODO: model, token usage, context %, cost, session name.

## Keybindings & slash-commands

TODO: submit/abort/scroll; `/` command palette.

## References

- [index.md](index.md) · [architecture.md](architecture.md) · [hosted-agents.md](hosted-agents.md)
- Existing code: `tui/TuiApp.kt`, `tui/component/{TranscriptView,StatusBar,PromptInputView}.kt`.
