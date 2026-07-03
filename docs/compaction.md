# Compaction

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md). Applies to the **Prompt**
> provider (client-owned history). Hosted agents manage their own context server-side.

**Purpose:** keep the conversation within the model's context window by summarizing older turns while preserving
recent work.

## Trigger

TODO: `contextTokens > contextWindow − reserveTokens`, using `ResponseUsage` token counts from the last response.
Manual `/compact [instructions]`.

## Algorithm

TODO: walk back from newest keeping `keepRecentTokens`; summarize the older span; append a compaction entry;
rebuild `input` from summary + kept messages. Handle split turns (one huge turn) and never cut a tool result from
its call.

## Summary format

TODO: the structured summary template (goal, constraints, progress, decisions, next steps, read/modified files).

## Serialization & truncation

TODO: serialize messages to text for the summary request; truncate tool results (~2000 chars) to bound cost.

## Settings

TODO: `enabled`, `reserveTokens`, `keepRecentTokens` — see [configuration.md](configuration.md).

## Future: long-term memory

TODO: note Memory Stores as a **future** per-user/session persistence mechanism (not compaction) — see
[future.md](future.md).

## References

- [index.md](index.md) · [sessions.md](sessions.md) · [configuration.md](configuration.md)
