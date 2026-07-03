# Sessions

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md).

**Purpose:** the Session concept — the client-owned transcript, its persistence, and resume. (Hosted sessions are
server-owned; see [hosted-agents.md](hosted-agents.md).)

## Lifecycle

TODO: new / continue / resume; where sessions live on disk; naming.

## Transcript & entry model

TODO: entry types (user, assistant, tool call, tool result, compaction) with fields; how a turn maps to entries.

## Persistence format

TODO: JSONL, append-only, resumable (pi-aligned). Document the on-disk schema here (session-format section).

## Reconstructing request `input`

TODO: how the transcript is serialized back into Responses `input` each turn (post-compaction). Ties to
[compaction.md](compaction.md) and the multi-turn decision in [index.md](index.md).

## Slash-commands

TODO: `/new`, `/resume`, `/session`, `/compact`, etc.

## References

- [index.md](index.md) · [compaction.md](compaction.md) · [hosted-agents.md](hosted-agents.md)
