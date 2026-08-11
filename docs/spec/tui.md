# TUI (Terminal User Interface)

Konductor's UI is a Lanterna terminal app. The scaffold already renders a scrollable transcript, a status bar, and
a bottom composer (`tui/`). This doc specifies how it renders a live agent turn and how the async agent loop feeds
it. See [architecture.md](architecture.md#threading--concurrency) for the threading model.

> Code blocks are illustrative design sketches, not committed implementation.
>
> **Implementation snapshot (2026-07-29):** non-blocking streamed turns, `Esc` cancellation, multiline input,
> markdown/code rendering, tool summaries, status tokens/context/cost, the command palette, and the listed slash commands
> are present.
> Input is intentionally inert while an agent turn or local background command runs (no steering/follow-up/command
> queue); path completion, file refs, external editor/images, and collapsible long tool output remain target behavior.

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
(`TuiApp.render`). When open, the stateless `CommandPaletteView` renders last as a centered, terminal-bounded overlay;
small terminals clip its item rows without drawing outside the screen. A one- or two-row terminal has no visible
result row, so `Enter` is inert; a three-row terminal omits the footer to expose one selectable result.

## Event loop & coroutine marshalling

The Lanterna input loop stays on the main thread; an agent turn or local background command runs on a coroutine.
There is no UI-event queue. Background work applies `AppState` mutations through `StateApplier` under the same render
lock used by the event loop, sets a dirty flag, and the next short polling tick repaints that accepted state.

```kotlin
while (running) {
    synchronized(stateLock) {
        if (dirty) {
            render(screen)
            dirty = false
        }
    }
    val key = screen.pollInput()
    if (key == null) shortTick() else running = handleKey(key)
}

// Background work: applier { fold event/result into AppState; dirty = true }
```

Non-blocking `screen.pollInput()` plus the short tick lets streaming deltas render and lets `Esc` race the active work's
explicit cancellation/commit state. `AppState` is never mutated from a background coroutine outside the applier/render
lock.

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

## Startup trust choice

After launch-cwd session selection and before project configuration/runtime construction, a valid unknown workspace with
at least one gated project file shows a localized four-choice prompt: **Trust** (persist), **Trust for this session
only**, **Do not trust** (persist), and **Do not trust for this session only**. The default is session-only untrusted.
Session-only choices affect only the current application process and perform no trust-store, lock, or candidate write.
Persistent Trust takes effect only after its store write succeeds; either untrusted choice keeps project `.env` and
project settings unopened. Context-file records and their rendered path-bearing block remain independent of the choice.

The process flags `--approve`/`-a` and `--no-approve`/`-na` suppress this normal prompt and never persist. Approve still
requires a structurally safe config directory and a valid/missing trust store; corruption is an actionable error, not a
silent trust grant. No-approve forces untrusted and may bypass trust-store content safely. Without an override, an error
snapshot uses the separate repair-guided Continue-untrusted/Quit screen from
[configuration.md](configuration.md#workspace-identity-and-trust-store), not the ordinary four-choice prompt.

## Status bar

Shows: model, `input/window` tokens with context %, running cost estimate, and session name. Fed by
`UsageReported` and the `ContextWindowTracker`. When context % is high, hint that compaction will run
([compaction.md](compaction.md)). When a persisted PromptAgent is bound, it also shows the agent name
([providers.md](providers.md#persisted-prompt-agents-promptagent)). That name is a presentation cache derived only from
a committed session/binding result; prepare, persistence, resume, or fresh-publication failure leaves it unchanged.

## Keybindings

| Key | Action |
|-----|--------|
| `Enter` | Submit input while idle; input is inert while background work is running |
| `Esc` | Request cancellation of the running turn or background command |
| `↑/↓`, `PgUp/PgDn` | Scroll transcript |
| `Ctrl+C` | Quit |
| `Ctrl+K` | Open the command palette while idle |
| `/` | Open the command palette only when the composer is empty; otherwise insert literal `/` |

While the palette is open, typing fuzzy-filters its current catalog, `↑/↓` selects, `Enter` stages the selected text in
the composer, and `Esc` closes without dispatch. `Ctrl+K` preserves an existing draft while browsing; selecting an
entry replaces that draft so the staged slash command remains the leading token. When `/` is consumed as the empty-
composer trigger, the palette tracks `/` plus the complete typed or pasted query as its cancellation draft. `Esc`
restores that exact text, allowing path-like input such as `/etc/hosts` to continue through normal submission. A turn
or background command keeps all palette triggers inert under the existing single-flight input guard.

Steering, follow-up, and command queues are not implemented. Submitted text is consumed rather than retained as a
queued draft, and cancellation does not restore it to the composer. The composer remains inert until terminal reporting
for the exact active submission completes. Palette draft restoration is different: it applies only to pre-submission
text while the overlay owns input.

### Active submissions and cancellation

> **Implementation target (I081):** the atomic local-command state and distinct submission identities below are the
> accepted contract for issue #81; the current implementation still carries background commands as turn-shaped jobs.

The one active TUI work slot distinguishes an **agent turn** from a **local background command**; a palette option load
is overlay-owned and is not an active submission. Agent turns and commands therefore use separate localized
cancellation copy and terminal behavior instead of treating every coroutine job as a "turn."

For an agent turn, the first accepted `Esc` requests job cancellation. The turn-specific cancellation line is rendered
once, only after collection unwinds; repeated `Esc` is inert. Entries and external effects completed before cancellation
remain real: in particular, the persisted user entry and completed tool call/results are not rolled back. Input stays
inert while the cancelled job unwinds.

A local command owns one atomic phase. It starts `Running`; cancellation and `beginCommit` compare-and-set that same
phase:

```text
Running -- cancel wins --> Cancelling -- unwind --> Cancelled
Running -- commit wins --> Committing -- natural result --> Completed | Failed
```

An unexpected preparation exception that wins its race with cancellation transitions `Running` to `Failed` and reports
an ordinary command failure; cancellation that wins first suppresses that result. If interactive `Esc` cancellation
wins, the job is cancelled and cannot publish an ordinary result or mutate durable
session state, the active runtime/session/binding, or command-result UI state, even if non-cooperative preparation later
returns. After unwind, one command-specific cancellation line is added before the working state clears. If commit wins,
persistence and the matching live/UI commit run in `NonCancellable`; `Esc` during that phase is inert, and the command
reports its natural success or failure, never cancellation or rollback. Persistence precedes matching live state. Then
status, model, and token changes plus the command report are applied under the render lock. Only afterward do the
working flag and exact active identity clear. A stale finalizer may not report for or clear another identity.

Read-only background commands use result publication as an empty commit. Mutating commands put their first durable,
provider-binding, active-session, or presentation mutation after `beginCommit`; all fallible validation and immutable
candidate construction occur before it. Expected post-persistence in-memory assignments are infallible. An unexpected
failure after a durable or service effect reports the accepted state for reconciliation and does not invent rollback.

While a submission is `Running`, `Cancelling`, or `Committing`, only scrolling, `Esc`, and `Ctrl+C` remain active. No
later command supersedes it. Graceful exit routes a pre-commit command through the same cancellation state
before cancelling its job; a bounded wait may stop waiting for non-cooperative preparation, but that job can never
commit later; cancellation copy may be omitted because the frontend is closing. An already-committing command is not
cancelled: shutdown waits for its operation-bounded natural result before closing dependent runtime resources and
restoring the terminal. Forced JVM/OS termination may prevent terminal copy and has only the durability guarantees of
the active store/service.

## Startup model bootstrap

The interactive Prompt frontend may start with a project endpoint/identity but no local model. Before constructing a
provider, `AgentLoop`, `TuiApp`, or new session, bootstrap queries the process-scoped project's deployment catalog and
keeps only application DTOs whose type is `ModelDeployment`. The persisted model from a kind-matched resumed or
continued session, or a model resolved from CLI, environment, or permitted settings, skips this lookup entirely. `--continue` with no session for the exact canonical launch cwd instead follows the new-session path and performs this
lookup when local resolution is still empty. Hosted also skips it because Hosted has no client-model selection capability.

Before project/catalog construction, the effective configured agent kind must match the selected resume/continue
session's strict schema/binding kind. Prompt-over-Hosted and Hosted-over-Prompt are symmetric localized errors with no
migration, fallback, provider switch, service operation, or write. `--continue` does not skip an opposite-kind newest
workspace session to find an older same-kind session and does not treat it as "no match."

The pre-provider outcomes are:

- **zero** — after restoring any bootstrap terminal, print localized stderr guidance to deploy a model in the
  configured project or set a model locally, then rerun; exit non-zero without constructing a provider or session;
- **one** — select the exact deployment name automatically, carry a localized notice into the subsequently created
  TUI's startup/system messages, and continue;
- **many** — show a dedicated cancellable selector in canonical catalog order; Enter returns the exact selected value
  to configuration finalization and Esc exits successfully;
- **catalog failure** — after terminal restoration, print a sanitized localized stderr explanation with
  endpoint/identity/access and explicit-model actions, then exit non-zero. Raw service messages, response bodies,
  credentials, and endpoint details are not rendered.

The selector is bootstrap UI, not a command palette opened against a hidden `TuiApp`. It reuses the existing SDK-free
`CommandOption`/`CommandOptionProvider` model option boundary and may reuse bounded fuzzy navigation/rendering patterns,
but it does not stage `/model`, dispatch a command, create an `AppState` session, or invoke a provider. The complete
filtered catalog determines zero/one/many; selector state retains only the first 2,000 options in canonical order, and
fuzzy rendering retains its existing 100-result bound. When the catalog exceeds 2,000, localized selector copy reports
the shown and total counts, says later entries are omitted, and points to `--model <deployment>` for an omitted exact
name. It remains continuously visible while the selector is active, including during filtering/navigation; omitted
entries cannot become selectable.

Cancellation, empty inventory, and failure write no settings or session metadata and create no session. The selected
value is process-local; if startup succeeds, only normal session creation persists it as the session's `modelName`.
The auto-selection notice remains in `AppState.messages` for the TUI lifetime but is neither model-visible transcript
history nor persisted session data. Truncation is informational selector state and need not be repeated after selection
or clean cancellation. Zero/failure are terminal outcomes that explain a non-zero exit and required user action; their
reports are written to stderr only after the selector/terminal is closed, so they cannot be lost with an alternate
screen. There is no refresh, deployment creation, project selection, or model-metadata context sizing in this flow.

All of these messages use `AppStrings`; deployment names remain raw stable identifiers. This pre-provider fatal failure
policy is intentionally narrower than the in-session `/model` fallback below, where an already-usable session survives
a discovery outage.

## Slash-commands

The canonical registry exposes `/quit` (`/exit` alias), `/new`, `/name`, `/session`, `/resume`, `/compact`,
`/model`, `/connections`, and `/agent` in deterministic display order. Each entry has one stable name, alias set, usage
syntax, and localized description. Registry lookup is case-insensitive, while command names and usage identifiers stay
stable across locales.

Dispatch parses only the leading command token and preserves the original input plus the raw remainder. There is no
generic argument tokenizer: `/compact` owns its free-text instructions, `/resume` owns number/UUID parsing, and
`/agent` owns its nested grammar. Unknown slash-prefixed text, including path-like input such as `/etc/hosts`, returns
not-handled and falls through as a normal model prompt rather than producing a command error. See
[sessions.md](sessions.md).

Commands return an immediate, background, quit, or not-handled action; they do not launch coroutines.
`ConversationController` is the sole execution adapter. `/new` prepares an in-memory candidate and required local
runtime/binding/context/tool validation before its command gate, then commits the new header once with
`SessionStore.persistNew` before replacing the active/visible session. `/resume <number|id>` similarly resolves, loads,
and validates a detached candidate before its first binding or active-session mutation. `/compact` performs summary
work and constructs an immutable transcript candidate while cancellable, then commits `SessionStore.rewrite`, the same
live entry order, and presentation after its gate. Failure leaves the prior visible state usable; none of these paths
creates then deletes a rollback header.

The palette enumerates the same registry, evaluates each command's optional availability contract when it opens, and
shows unavailable descriptors disabled with a localized reason. Availability is discovery guidance only; existing
execution-time provider gates remain authoritative. Selecting a normal command stages its descriptor's stable
insertion/usage prefix for confirmation and never dispatches from the overlay. Blocking submission applies immediate
work directly and runs background work with `runBlocking`; async submission applies immediate work on the event-loop
thread and returns a distinct agent-turn or local-command submission. The controller owns preparation, the atomic
local-command commit state, working-state ordering, `StateApplier`, and exact active-job handoff. Palette option loading
remains frontend/generation-owned: `Esc` closes it, `Ctrl+K` replaces it, and a late result cannot reopen or replace
newer state. Neither action emits turn/command cancellation copy or changes a submitted command's commit state.

Fuzzy matching truncates raw strings before locale-independent lowercase normalization and has explicit per-pass
bounds: 128 query code points, 512 code points per term, 8 terms per candidate, 2,000 inspected candidates, and 100
returned results. Equal scores retain deterministic source order. These bounds apply to both command descriptors and
dynamic option catalogs.

A separate suspend option-provider contract supplies application-owned labels and values without extending
`TuiCommand`. Selecting enabled `/model`, `/resume`, or `/agent` transitions directly to its dynamic option catalog.
Option-source lookup keys and ordinary insertion prefixes come from the canonical command descriptors: model selection
stages `/model <exact deployment name>` and recent-session selection stages `/resume <full UUID>`. PromptAgent selection
is the intentional nested-prefix exception, staging the minimal `/agent use <exact name>` form without an intermediate
`use` picker. Labels and localized details are presentation-only;
the persisted full session UUID and exact PromptAgent name remain the insertion identities.

Catalogs are snapshot-on-open. Opening an option picker invokes its suspend provider once under a new generation;
reopening the command and picker is the explicit refresh action, while an already-open catalog does not poll or reorder
itself. Loading, source-specific empty states, and sanitized failures stay in the overlay and do not add transcript
entries. Cancellation or replacement invalidates the generation, so even a non-cooperative late completion cannot
replace or reopen newer state. Selection only replaces the composer draft: it does not dispatch a command, resume a
session, bind an agent, or otherwise mutate application state. The unchanged submission path remains the sole authority
for model validation, session resume, agent binding, and reporting.

The `/resume` provider reads application session summaries and presents them most-recently-updated first, with localized
short-id, entry-count, and update-time detail while staging the complete UUID. The `/agent` picker is composed only when
the runtime supplies `ProviderManagement.PromptAgents`; it lists that management surface's PromptAgent names and marks
the current binding in localized detail. Hosted and unmanaged Prompt runtimes retain the disabled `/agent` descriptor
and its provider-availability reason, and never expose PromptAgent choices.

`/model list` queries `FoundryProjectRuntime.deployments` and renders deployment name plus model name, version,
publisher, and type. `/model <deployment>` requires an exact deployment-name match before switching. The blocking
catalog operation runs away from the Lanterna event-loop thread as cancellable preparation. A valid match—or the
existing unvalidated fallback decision after a discovery outage—produces immutable context/metadata and presentation
candidates; `beginCommit` then precedes metadata persistence, live loop/context mutation, status update, and copy in
that order. Cancellation that wins before the gate suppresses all of them. `Esc` after the gate cannot cancel the
persistence or claim the old model was restored. A confirmed missing deployment remains a read-only command result and
is rejected with guidance to choose from `/model list` or deploy it.

`/model`, `/model list`, and `/connections` without a domain mutation use publication of their prepared result as the
commit point; cancellation before publication suppresses that result and emits only command-cancelled copy.
`/connections` queries `FoundryProjectRuntime.connections` and lists only the application DTO's non-secret name, type,
target, authentication type, and default marker. It never requests or renders credential values, connection ids, the
free-form metadata map, or raw service-error details.

Provider-sensitive commands use shared `AgentLoop` outcomes rather than TUI `AgentKind` checks. `/compact` is rejected
when client compaction is unavailable; `/model <deployment>` is rejected before discovery when client model switching
is unavailable or a bound PromptAgent fixes the model. Neither rejection mutates session metadata, queries the project
catalog, or invokes the provider. `/model list` and `/connections` remain read-only project discovery for either
provider kind. Production TUI composition always supplies the Foundry project catalogs explicitly. A non-production
embedder that intentionally composes discovery as unavailable receives localized unavailable explanations for those
commands rather than the valid-empty-project messages; an explicit `/model <deployment>` still follows the documented
unvalidated-switch fallback. `/agent` exists only when the runtime supplies `ProviderManagement.PromptAgents`;
otherwise it is intercepted as unavailable. Thus a Hosted TUI does not advertise an operable Prompt-only control even
though command text is still recognized so the user gets an explanation.

`/agent` manages the opt-in **persisted PromptAgent** binding
([providers.md](providers.md#persisted-prompt-agents-promptagent),
[M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in)):

| Command | Effect |
|---------|--------|
| `/agent` | Show the active agent (or "ephemeral") |
| `/agent list` | List persisted PromptAgents in the project |
| `/agent use <name>` | Bind the session to an existing agent through the name-scoped endpoint |
| `/agent create [name]` | Mint a new agent version from the current stable base + configured append and tools, then switch to its name |

For `/agent use`, the parser trims the supplied name and the application validates and prepares that exact name without
changing the active provider. It atomically persists an immutable session-metadata candidate, then performs non-failing
provider and live-session commits. The TUI
sets status from that exact committed result and emits success copy in the same state application. A reported prepare
or persistence failure leaves the accepted header, provider route, live `Session.promptAgentName`, and displayed status
unchanged. Input remains inert and no model turn or session command can observe the commit interval.

`/agent create` performs remote version creation before that local adoption sequence. If creation fails, copy
conservatively says a version may exist when the remote outcome is ambiguous and confirms that the current binding did
not change. If creation succeeds but adoption fails, copy says the version was created but not adopted and suggests
`/agent use <name>`; Konductor does not delete the version. A successful message may show the lifecycle-returned version
as creation information but never says the name-scoped Responses binding pins that exact version.

Fresh startup places the exact validated configured name in its provisional session header before `persistNew`; `/new`
places the current committed name there before publication. Neither updates the status or reports a new session until
publication succeeds. Resume prepares the exact persisted name before replacing transcript/session/status; an omitted
`promptAgentName` field decodes to the in-memory `null` binding, prepares ephemeral, and visibly unbinds. Resume
performs no implicit `listAgents` validation/fallback, so
failure retains the previous transcript, binding, and status rather than silently contradicting the accepted header.
After restart, status is reconstructed only from the valid accepted header and successfully prepared provider.

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
