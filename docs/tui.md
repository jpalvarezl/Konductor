# TUI (Terminal GUI)

Konductor's UI is a Lanterna terminal app. The scaffold already renders a scrollable transcript, a status bar, and
a bottom composer (`tui/`). This doc specifies how it renders a live agent turn and how the async agent loop feeds
it. See [architecture.md](architecture.md#threading--concurrency) for the threading model.

> Code blocks are illustrative design sketches, not committed implementation.

## Layout

```
┌───────────────────────────────────────────────┐
│ Transcript (scrollable)                        │  user / assistant / tool / log / error entries
│   you› add retries to the client               │
│   konductor› I'll read the client first.       │
│     ⚙ read(path="Client.kt")  → 42 lines       │
│   …log… [hosted] container: building            │
├───────────────────────────────────────────────┤
│ status: gpt-5-mini · 12.4k/128k ctx 10% · $0.02 │  model · tokens/window · cost · session
├───────────────────────────────────────────────┤
│ › type a message…                              │  composer (multi-line, thinking-level border)
└───────────────────────────────────────────────┘
```

Panes are the existing `TranscriptView`, `StatusBar`, `PromptInputView`. Heights adapt as they do today
(`TuiApp.render`).

## Event loop & coroutine marshalling

The Lanterna input loop stays on the main thread; the agent turn runs on a coroutine. Provider `AgentEvent`s are
pushed onto a thread-safe queue that the render loop drains — UI state is only mutated on the UI thread.

```kotlin
// UI thread (existing loop, made non-blocking on input)
while (running) {
    drainAgentEvents()            // apply queued AgentEvents to AppState
    render(screen)
    val key = screen.pollInput() ?: continue
    running = handleKey(key)
}

// Agent loop (Dispatchers.IO): provider.runTurn(...).collect { uiQueue.offer(it) }
```

Use `screen.pollInput()` (non-blocking) plus a short wait, or Lanterna's async input, so streaming deltas render
while the user can still type/abort.

## Rendering agent output

| `AgentEvent` | Rendering |
|--------------|-----------|
| `TextDelta` | Append to the in-progress assistant entry; repaint incrementally |
| `ToolCallStarted` | Show a `⚙ name(args)` line under the assistant entry |
| `ToolCallCompleted` | Annotate with a result summary (e.g. `→ 42 lines`, `→ exit 0`); collapse long output |
| `LogFrame` | Append to a dim "log lane" (hosted agents, [hosted-agents.md](hosted-agents.md)) |
| `UsageReported` | Update the status bar tokens/context % |
| `TurnCompleted` | Finalize the assistant entry; persist |
| `Failed` | Render an error entry (red); keep the session usable |

Tool output and logs are visually distinct from assistant prose and are collapsible to keep the transcript
readable.

## Status bar

Shows: model, `input/window` tokens with context %, running cost estimate, and session name. Fed by
`UsageReported` and the `ContextWindowTracker`. When context % is high, hint that compaction will run
([compaction.md](compaction.md)).

## Keybindings

| Key | Action |
|-----|--------|
| `Enter` | Submit message (or queue as steering if a turn is running) |
| `Esc` | Cancel the running turn; restore queued input |
| `↑/↓`, `PgUp/PgDn` | Scroll transcript |
| `Ctrl+C` | Quit |
| `/` | Open slash-command completion |

Message-queue semantics (submit while working) are a small extension of the existing composer; keep it simple for
the hackathon.

## Slash-commands

`/new`, `/resume`, `/name`, `/session`, `/compact`, `/model`, `/quit`. Commands are parsed in the composer before
reaching the agent loop; unknown `/x` is echoed as an error. See [sessions.md](sessions.md).

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) ·
[sessions.md](sessions.md) · [compaction.md](compaction.md)

**Existing code:** `tui/TuiApp.kt`, `tui/component/{TranscriptView,StatusBar,PromptInputView}.kt`,
`tui/style/Theme.kt`, `tui/layout/Rectangle.kt`.
