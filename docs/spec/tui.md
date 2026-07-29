# TUI (Terminal User Interface)

Konductor's UI is a Lanterna terminal app. The scaffold already renders a scrollable transcript, a status bar, and
a bottom composer (`tui/`). This doc specifies how it renders a live agent turn and how the async agent loop feeds
it. See [architecture.md](architecture.md#threading--concurrency) for the threading model.

> Code blocks are illustrative design sketches, not committed implementation.
>
> **Implementation snapshot (2026-07-10):** non-blocking streamed turns, `Esc` cancellation, multiline input,
> markdown/code rendering, tool summaries, status tokens/context/cost, and the listed slash commands are present.
> Input is intentionally inert while a turn runs (no steering/follow-up queue yet); path completion, file refs,
> external editor/images, and collapsible long tool output remain target behavior.

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

The Lanterna input loop stays on the main thread; the agent turn runs on a coroutine. The committed implementation
applies `AppState` mutations under a render lock and repaints on a dirty tick; the queue below remains an alternative
design sketch, not the current mechanism.

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
| `Retrying` | Format a localized retry-status line from structured reason/retry-number/max-retries/delay fields |
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
([compaction.md](compaction.md)). When a persisted PromptAgent is bound, it also shows the agent name
([providers.md](providers.md#persisted-prompt-agents-promptagent)).

## Keybindings

| Key | Action |
|-----|--------|
| `Enter` | Submit input while idle; input is inert while background work is running |
| `Esc` | Request cancellation of the running turn or background command |
| `↑/↓`, `PgUp/PgDn` | Scroll transcript |
| `Ctrl+C` | Quit |
| `/` | Open slash-command completion |

Steering/follow-up queues are not implemented. The composer remains inert until the active job fully unwinds.

## Slash-commands

`/new`, `/resume`, `/name`, `/session`, `/compact`, `/model`, `/connections`, `/agent`, `/quit`. Commands are parsed
in the composer before reaching the agent loop; unknown `/x` text falls through as a normal model prompt. See
[sessions.md](sessions.md).

`/model list` queries `FoundryProjectRuntime.deployments` and renders deployment name plus model name, version,
publisher, and type. `/model <deployment>` requires an exact deployment-name match before switching. The blocking
catalog operation runs away from the Lanterna event-loop thread; while it runs, the existing working state keeps input
inert and `Esc` requests cancellation. Cancellation is checked after catalog I/O and before model mutation; richer local
command commit/reporting semantics are tracked in [#81](https://github.com/jpalvarezl/Konductor/issues/81). If discovery
fails, an explicitly requested model is selected without validation and a warning says the next service request is
authoritative. If discovery succeeds and the requested deployment is absent, the switch is rejected with guidance to
choose from `/model list` or deploy it. `/connections` queries `FoundryProjectRuntime.connections` and lists only the application DTO's
non-secret name, type, target, authentication type, and default marker. It never requests or renders credential values,
connection ids, the free-form metadata map, or raw service-error details.

Provider-sensitive commands use shared `AgentLoop` outcomes rather than TUI `AgentKind` checks. `/compact` is rejected
when client compaction is unavailable; `/model <deployment>` is rejected before discovery when client model switching
is unavailable or a bound PromptAgent fixes the model. Neither rejection mutates session metadata, queries the project
catalog, or invokes the provider. `/model list` and `/connections` remain read-only project discovery for either
provider kind. `/agent` exists only when the runtime supplies `ProviderManagement.PromptAgents`; otherwise it is
intercepted as unavailable. Thus a Hosted TUI does not advertise an operable Prompt-only control even though command
text is still recognized so the user gets an explanation.

`/agent` manages the opt-in **persisted PromptAgent** binding
([providers.md](providers.md#persisted-prompt-agents-promptagent),
[M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in)):

| Command | Effect |
|---------|--------|
| `/agent` | Show the active agent (or "ephemeral") |
| `/agent list` | List persisted PromptAgents in the project |
| `/agent use <name>` | Bind the session to an existing agent (by name; latest version) |
| `/agent create [name]` | Mint a new agent version from the current [agent context](agent-context.md) and switch to it |

Selecting/creating an agent updates the session's `promptAgentName` ([sessions.md](sessions.md)) and the status bar.

## Localized copy

TUI labels, hints, local command responses, formatted tool summaries, and CLI presentation come from
`i18n/AppStrings`, backed by `src/main/resources/com/konductor/i18n/messages.properties`. The selected locale is
resolved before Foundry configuration so help and frontend copy do not require Azure access.

Slash-command names, CLI option names, tool names/schema keys, model text, raw tool output, hosted logs, and persisted
session values remain stable. New locale bundles use standard JVM names such as `messages_es.properties` or
`messages_fr_CA.properties`; missing locale entries fall back to the English root bundle.
Catalog patterns use `MessageFormat`, so literal apostrophes in translated patterns must be doubled.

Terminal layout still measures several strings with Kotlin character counts. CJK/combining-character display-cell
measurement and right-to-left layout are separate follow-ups rather than implicit guarantees of the resource catalog.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) ·
[sessions.md](sessions.md) · [compaction.md](compaction.md)

**Existing code:** `i18n/AppStrings.kt`, `tui/TuiApp.kt`,
`tui/component/{TranscriptView,StatusBar,PromptInputView}.kt`, `tui/style/Theme.kt`, `tui/layout/Rectangle.kt`.
