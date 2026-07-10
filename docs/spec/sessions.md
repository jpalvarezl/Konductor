# Sessions

A **session** is a persisted conversation. For the **Prompt provider** the session is the **client-owned
transcript** — the authoritative history Konductor re-sends as Responses `input` each turn and compacts when it
grows too large. (Hosted-agent sessions are server-owned `AgentSessionResource`s; see
[hosted-agents.md](hosted-agents.md).)

> Code blocks are illustrative design sketches, not committed implementation.

## Lifecycle

| Action | Trigger | Effect |
|--------|---------|--------|
| New | startup (default) or `/new` | Fresh session file, empty transcript |
| Continue | `--continue` / `-c` | Reopen the most recent session for this cwd |
| Resume | `--resume` / `-r` or `/resume` | Pick a past session for this cwd |
| Name | `--name` / `/name <n>` | Human-readable label |
| Ephemeral | `--no-session` | Keep in memory only; never persist |

## Storage

Sessions auto-save under `~/.konductor/sessions/`, organized by working directory, one **JSONL** file per session.
Append-only: each entry is one line, written as it is produced, so a crash leaves a valid partial session.

```
~/.konductor/sessions/<cwd-hash>/<session-id>.jsonl
```

## Entry model & on-disk schema

Each line is a JSON object with a `type` discriminator, matching the
[domain model](architecture.md#core-domain-model). The first line is a header.

```jsonl
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440000","version":1,"name":"refactor auth","cwd":"/repo","model":"gpt-5-mini","createdAt":"...","promptAgentName":"konductor-coder"}
{"type":"user","id":"01J...","parentId":"01J...hdr","timestamp":"...","text":"add retries to the client"}
{"type":"assistant","id":"...","parentId":"...","timestamp":"...","text":"I'll read the client first.","toolCalls":[{"callId":"c1","name":"read","argumentsJson":"{\"path\":\"Client.kt\"}"}],"usage":{"inputTokens":1200,"outputTokens":80,"totalTokens":1280}}
{"type":"tool_call","id":"...","parentId":"...","timestamp":"...","call":{"callId":"c1","name":"read","argumentsJson":"{...}"}}
{"type":"tool_result","id":"...","parentId":"...","timestamp":"...","result":{"callId":"c1","output":"...","isError":false,"truncatedBytes":0}}
{"type":"compaction","id":"...","parentId":"...","timestamp":"...","summary":"## Goal ...","firstKeptEntryId":"01J...","tokensBefore":48000}
```

Notes:
- `parentId` links entries in order. It is a linear chain now; the field is kept so **branching** can be added later
  without a format change ([future.md](../future.md)).
- Tool results are stored verbatim (already truncated by the tool, [tools.md](tools.md)).
- `compaction` entries record the summary and where kept messages resume (`firstKeptEntryId`).
- `promptAgentName` (header, optional) records the persisted **PromptAgent** name. On resume Konductor validates and
  rebinds it; ephemeral sessions omit the field ([providers.md](providers.md#persisted-prompt-agents-promptagent)).
- Failed or cancelled partial turns keep the user entry and any completed tool call/results, because those actions
  happened. Partial assistant text is display-only and is not written without terminal `TurnCompleted`; a dedicated
  failure/aborted entry remains deferred.
- `src/test/resources/session/current-session-v1.jsonl` is the schema golden for the current header and every
  current `Entry` subtype. Serialization changes must update that fixture intentionally.

## Reconstructing Responses `input`

Before each Prompt turn, the loader walks entries **from the latest compaction's `firstKeptEntryId`** (or the
header) to the tip and serializes them into Responses input items:

```kotlin
fun buildInput(session: Session): List<ResponseInputItem> {
    val start = session.entries.lastCompaction()?.let { cmp ->
        listOf(summaryAsSystemItem(cmp.summary)) to indexAfter(cmp.firstKeptEntryId)
    } ?: (emptyList<ResponseInputItem>() to 0)
    return start.first + session.entries.drop(start.second).map { it.toInputItem() }
}
```

`UserEntry`/`AssistantEntry` → messages; `ToolCallEntry` → function-call item; `ToolResultEntry` →
`ResponseFunctionToolCallOutputItem` (matched by `callId`). Never send a tool result without its call. See
[compaction.md](compaction.md) for what gets summarized vs kept.

## SessionStore API

```kotlin
interface SessionStore {
    fun create(cwd: Path, model: String, name: String?): Session
    fun append(session: Session, entry: Entry)          // writes one JSONL line
    fun rewrite(session: Session)                       // compaction/header changes
    fun load(id: Uuid): Session
    fun listForCwd(cwd: Path): List<SessionSummary>     // id, name, updatedAt, message count
    fun rename(session: Session, name: String)
    fun persistHeader(session: Session)
    fun locate(session: Session): Path?
}
object NoOpSessionStore : SessionStore   // in-memory implementation for --no-session/tests
```

M3 delivers `NoOpSessionStore` (ephemeral, for `--no-session`) alongside the JSONL-backed store
([implementation-roadmap.md](../implementation-roadmap.md)).

## Slash-commands

| Command | Effect |
|---------|--------|
| `/new` | Start a new session |
| `/resume` | Pick a previous session |
| `/name <n>` | Rename the current session |
| `/session` | Show file, id, message count, tokens |
| `/compact [instructions]` | Manually compact ([compaction.md](compaction.md)) |

## Persisted agents & resume

Binding an opt-in **persisted PromptAgent** ([providers.md](providers.md#persisted-prompt-agents-promptagent)) leaves
the session model **unchanged** in mechanism — the transcript stays client-owned and `buildInput` is identical
(instructions live server-side in the agent, and were never part of the reconstructed `input`). The only addition is
the optional `promptAgentName` in the header:

- **Resume** validates and rebinds the session's recorded `promptAgentName`; if it was deleted server-side, Konductor falls
  back to ephemeral and keeps the transcript.
- `/agent use|create` updates `Session.promptAgentName` and persists the live session header
  ([tui.md](tui.md#slash-commands)).
- Compaction is untouched — see the server-side-overhead note in [compaction.md](compaction.md).

## Related docs

[architecture.md](architecture.md) · [compaction.md](compaction.md) · [providers.md](providers.md) ·
[hosted-agents.md](hosted-agents.md) · [tui.md](tui.md)
