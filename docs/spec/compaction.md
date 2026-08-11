# Compaction

LLMs have a finite context window. When the client-owned transcript ([sessions.md](sessions.md)) grows too large,
**compaction** summarizes older turns while preserving recent work, so the Prompt provider can keep going.
Compaction applies to the **Prompt provider only** — Hosted agents manage their own context server-side
([hosted-agents.md](hosted-agents.md)). `AgentLoop` applies `ProviderCapabilities.clientCompaction` before creating its
tracker/compactor behavior, so TUI and ACP cannot accidentally enable Prompt compaction for Hosted.

> Code blocks are illustrative design sketches, not committed implementation.

## Trigger

The [`ContextWindowTracker`](architecture.md#compaction-integration) holds the last reported
`Usage.totalTokens` (from `ResponseUsage`, [providers.md](providers.md)) and the model's context window. Before each
turn:

```
if (contextTokens > contextWindow - reserveTokens) compact()
```

Defaults: `reserveTokens = 16384` (headroom for the reply). Manual: `/compact [instructions]` forces it with
optional focus instructions when the provider supports client compaction. The shared loop returns a typed unsupported
outcome for server-owned history; the TUI renders it without making a provider call.

Automatic compaction remains inside its triggering turn's `AgentLoop` lock. Manual compaction acquires that same
non-queuing single-flight lock before planning and holds it through summarization, candidate construction, persistence,
and the in-memory commit. A manual compaction racing a turn or another manual compaction is rejected with the same
in-progress outcome as overlapping turns, so loop-owned transcript recording cannot make the candidate stale.
Automatic summary-generation failures skip proactive compaction and continue the turn; a rewrite/persistence failure
instead fails the turn while preserving the accepted file and uncommitted live ordering. Manual compaction reports
either kind of failure to its caller. A service-reported overflow from the ensuing Prompt request follows the separate
bounded recovery contract below.

## Reactive context-overflow recovery

The usage trigger is necessarily retrospective: a large newly appended user entry or previously persisted tool result
can make the next request exceed the real service window before a fresh `Usage` exists. The two Prompt Responses
adapters therefore map only the exact structured service failure defined in
[providers.md](providers.md#context-overflow-classification) to an SDK-free `PromptContextOverflowException`.
`AgentLoop`, not an adapter or frontend, owns the resulting compact-and-retry decision because it owns transcript
persistence and can prove whether replay is safe.

Recovery is eligible only for a client-owned Prompt transcript (`clientCompaction` plus
`SessionHistoryOwnership.Client`) and the first main provider attempt in the logical user turn. Before the typed
failure, the attempt may have emitted retry-status events only. Any `TextDelta` (including an empty delta),
`UsageReported`, `ToolCallStarted`, `ToolCallCompleted`, or `TurnCompleted` makes replay unsafe. This conservative gate
means there is no assistant text, completed model output, recorded tool call/result, tool execution, or other known turn
side effect to duplicate. An ineligible overflow is terminalized as a staged structured failure and then surfaced
unchanged. In particular, an overflow after a tool result created during this turn is not recovered; the tool entries
remain before that outcome, and defining a safe continuation for that case is separate work.

For an eligible overflow, while still holding the turn's single-flight lock, the loop:

1. withholds the first overflow `AgentEvent.Failed` rather than rendering it as terminal;
2. calls the existing `Compactor.compact` once against the session that already contains the one accepted user entry;
3. requires a non-null `CompactionEntry`, atomically rewrites and commits it through the normal compaction path, emits
   `AgentEvent.Compacted`, and resets the tracker;
4. reconstructs history from that committed marker and kept span; and
5. calls the provider once more for the same logical turn without re-entering public `AgentLoop.runTurn` or appending
   another `UserEntry`.

This is one immediate recovery cycle, not a generic retry policy. It emits no transient `Retrying` status of its own,
does not delay/back off, and never recurses. The reactive budget is consumed when compaction starts. No compactable
span means no retry; a compaction/summary/rewrite failure means no retry; and any retry failure, including another
context overflow, terminates without another compaction. Existing adapter handling of 429/5xx/timeouts is independent.

Reactive recovery is a correctness fallback and remains available when `settings.enabled=false`; that setting controls
only proactive usage-based compaction. Provider capability enforcement still takes precedence, so Hosted never enters
this path. Proactive work does not consume the reactive budget: after proactive success, one reactive cycle may
re-compact surviving history if the first main request still overflows; after a best-effort proactive summary failure,
the ensuing overflow may also use the cycle. Both use the same `keepRecentTokens` cut policy—there is no hidden
aggressive mode. If no further span is compactable, the request is not replayed. A proactive rewrite failure stays
terminal before the main provider call. Consequently one turn can emit at most two `Compacted` events, one proactive
and one reactive.

Cancellation wins recovery only by winning the accepted turn's terminal-admission gate. Admission checks coroutine
activity before and after its phase transition. A cancellation winner selects `AbortedEntry`, starts no retry, and
propagates without `AgentEvent.Failed`; a recovery/persistence failure that admits `FailedEntry` first makes a later
cancel inert. Frontends derive the result from the accepted terminal, not the child job flag. Cancellation during
summary generation commits no marker; cancellation after a committed rewrite leaves the valid marker before abort.

For non-cancellation failures the loop atomically terminalizes one safe `FailedEntry` before exposing one terminal
`AgentEvent.Failed`. Unsafe/unsupported recovery forwards the original overflow. Once recovery starts,
`ContextOverflowRecoveryException` names `NO_COMPACTABLE_HISTORY`, `COMPACTION`, or `RETRY`; no-plan retains the
original as cause, while summary/rewrite or retry failure is primary with original overflow suppressed. A committed
marker is never rolled back. Any ordinary user/tool append exception poisons the session until that exact active-turn
ambiguity is reconciled, so overflow handling cannot start another write or turn on uncertain bytes.
Terminalization/reconciliation and persistence precedence are defined in
[sessions.md](sessions.md#atomic-turn-terminalization).

## Algorithm

```
1. Build the indexed replay-safe epoch; walk backwards over its model-visible entries until
   `keepRecentTokens` (default 20000) is reached, then apply the projected rounding rules below.
2. Collect summary content from the epoch's previous usable kept boundary up to the cut, omitting control/audit data.
3. Generate the summary: call the model with the structured template below, passing any previous
   summary as iterative context.
4. Build an ordered transcript candidate with
   `CompactionEntry { summary, firstKeptEntryId, tokensBefore }` immediately before the first kept entry.
5. Persist the complete JSONL candidate as `[summarized..., marker, kept...]` using a forced-and-closed sibling file
   and atomic replacement ([sessions.md](sessions.md#storage)).
6. Only after persistence succeeds, commit the same marker/order to the live in-memory session. Candidate-write,
   replacement, and unsupported-atomic-move failures leave both the accepted file and live ordering unchanged.
7. Next turn, `buildInput()` emits the safe epoch's usable summary plus projected entries from
   `firstKeptEntryId`. Failed/aborted/dangling spans are hard epoch boundaries, not merely filtered outcome records.
   All physical audit entries remain in the candidate, but excluded spans contribute no token/summary content.
```

```kotlin
class Compactor(private val provider: AgentProvider, private val settings: CompactionSettings) {
    suspend fun compact(session: Session, instructions: String? = null): CompactionEntry {
        val (toSummarize, firstKeptId) = planCut(session, settings.keepRecentTokens)
        val summary = summarize(serialize(toSummarize), session.lastSummary(), instructions)
        return CompactionEntry(newId(), session.tip().id, now(), summary, firstKeptId, session.contextTokens())
    }
}
```

On repeated compactions within one replay-safe epoch, the span starts at the latest usable compaction's
`firstKeptEntryId`, so survivors are re-summarized. A hard failure/abort/dangling boundary invalidates older markers and
starts planning after that boundary.

### Cut-point rules

Planning first builds `(entry, physicalIndex)` pairs from the replay-safe epoch in
[sessions.md](sessions.md#reconstructing-responses-input). Entries at/before the latest hard failure/abort/dangling
boundary, outcome records, and unusable compaction metadata do not participate in the backward token walk, so a
zero-token record adjacent to the threshold cannot move it.

Rounding also searches projected entries, then maps the selected id/index back to the physical transcript:

1. prefer the opening user of a complete successful turn at or before the threshold, preserving whole recent turns;
2. if one complete successful turn alone exceeds `keepRecentTokens`, fall back to its assistant boundary; and
3. otherwise search forward for the next projected user/assistant boundary.

The fallback is not legal in failed, aborted, dangling, or current-open turns and never separates a tool call from its
result. `firstKeptEntryId` is therefore a replayable user/assistant id, never an outcome. The marker is inserted
immediately before that physical entry while the rewrite preserves every audit record before and after it. Planning
starts after the latest hard epoch boundary and uses only a later usable compaction marker; if no safe projected cut
exists it returns no plan.

## Summary format

A structured template keeps summaries actionable and machine-parseable (file lists feed the next turn):

```markdown
## Goal
[What the user is trying to accomplish]

## Constraints & Preferences
- [Requirements the user stated]

## Progress
### Done
- [x] ...
### In Progress
- [ ] ...
### Blocked
- ...

## Key Decisions
- **[Decision]**: [rationale]

## Next Steps
1. ...

## Critical Context
- [Data needed to continue]

<read-files>
path/to/file.kt
</read-files>
<modified-files>
path/to/changed.kt
</modified-files>
```

## Serialization & truncation

Before summarizing, messages are serialized to plain text (so the model treats them as material, not a
conversation to continue):

```
[User]: ...
[Assistant]: ...
[Assistant tool calls]: read(path="Client.kt"); bash(command="mvn -q test")
[Tool result]: ...
```

Tool results are truncated to ~2000 chars during serialization (they dominate token cost), with a marker noting how
many chars were dropped. This is coarser than the per-call tool truncation in [tools.md](tools.md).

## Settings

Configured in `<config-dir>/settings.json` (see [configuration.md](configuration.md)):

| Setting | Default | Meaning |
|---------|---------|---------|
| `enabled` | `true` | Enable auto-compaction |
| `reserveTokens` | `16384` | Tokens reserved for the reply |
| `keepRecentTokens` | `20000` | Recent tokens kept unsummarized |
| `contextWindow` | `128000` | The model's usable context window. No reliable SDK call exposes it, so it is a configurable knob; the conservative default compacts a little early rather than overflowing. Raise it for large-window models. |

Set `enabled=false` to disable proactive auto-compaction; `/compact` still works manually and an exactly classified
safe overflow may still invoke the bounded reactive recovery for a client-compaction-capable runtime. Provider
capability enforcement takes precedence over this setting.

## Persisted agents

Compaction is **unaffected** by the opt-in persisted **PromptAgent** path
([providers.md](providers.md#persisted-prompt-agents-promptagent)): the transcript stays client-owned, so the
trigger, cut-point, and summary logic above are identical. One nuance — a bound agent's baked `instructions` and
tool declarations are **fixed server-side overhead**: they count toward `Usage.totalTokens` (so the tracker still
triggers correctly off the authoritative number) but live outside the client transcript and cannot be compacted
away. Budget for them via `reserveTokens` if you bind large server-side instructions.

## Future: long-term memory

Compaction is *short-term* context management. Foundry **Memory Stores** are a different mechanism — durable,
per-user/session memory — and are **not** used here. They are a candidate future enhancement
([future.md](../future.md)).

## Related docs

[architecture.md](architecture.md) · [sessions.md](sessions.md) · [providers.md](providers.md) ·
[configuration.md](configuration.md)
