# Compaction

LLMs have a finite context window. When the client-owned transcript ([sessions.md](sessions.md)) grows too large,
**compaction** summarizes older turns while preserving recent work, so the Prompt provider can keep going.
Compaction applies to the **Prompt provider only** — Hosted agents manage their own context server-side
([hosted-agents.md](hosted-agents.md)).

> Code blocks are illustrative design sketches, not committed implementation.

## Trigger

The [`ContextWindowTracker`](architecture.md#compaction-integration) holds the last reported
`Usage.totalTokens` (from `ResponseUsage`, [providers.md](providers.md)) and the model's context window. Before each
turn:

```
if (contextTokens > contextWindow - reserveTokens) compact()
```

Defaults: `reserveTokens = 16384` (headroom for the reply). Manual: `/compact [instructions]` forces it with
optional focus instructions.

## Algorithm

```
1. Find the cut point: walk backwards from the newest entry, summing token estimates until
   `keepRecentTokens` (default 20000) is reached. Round to a turn boundary.
2. Collect messagesToSummarize: from the previous kept boundary (or session start) up to the cut point.
3. Generate the summary: call the model with the structured template below, passing any previous
   summary as iterative context.
4. Insert `CompactionEntry { summary, firstKeptEntryId, tokensBefore }` immediately before the first kept entry.
5. Rewrite the JSONL session so the physical layout is `[summarized..., marker, kept...]`.
6. Next turn, `buildInput()` emits: `[summary as developer item] + entries from firstKeptEntryId onward`.
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

On repeated compactions the summarized span starts at the **previous** compaction's `firstKeptEntryId`, so messages
that survived the earlier pass are re-summarized rather than dropped.

### Cut-point rules

Valid cut points: user messages, assistant messages, `bash`/custom messages. **Never cut between a tool call and
its result** — they must travel together.

### Split turns

If a *single* turn exceeds `keepRecentTokens`, the cut can fall back to an assistant boundary inside that turn
("split turn"). The summarized prefix is included in the single structured summary; a tool call/result pair is
never split.

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

Configured in `~/.konductor/settings.json` (see [configuration.md](configuration.md)):

| Setting | Default | Meaning |
|---------|---------|---------|
| `enabled` | `true` | Enable auto-compaction |
| `reserveTokens` | `16384` | Tokens reserved for the reply |
| `keepRecentTokens` | `20000` | Recent tokens kept unsummarized |
| `contextWindow` | `128000` | The model's usable context window. No reliable SDK call exposes it, so it is a configurable knob; the conservative default compacts a little early rather than overflowing. Raise it for large-window models. |

Set `enabled=false` to disable auto-compaction; `/compact` still works manually.

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
