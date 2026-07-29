# Sessions

A **session** is a persisted conversation identity and its local transcript. For the **Prompt provider** the
transcript is the authoritative client-owned history Konductor re-sends as Responses `input` each turn and compacts
when it grows too large. For the **Hosted provider**, the persisted binding identifies the authoritative server-owned
`AgentSessionResource`; local entries are a display/audit trail and are never replayed to rebuild Hosted history (see
[hosted-agents.md](hosted-agents.md)).

> Code blocks are illustrative design sketches, not committed implementation.

## Lifecycle

| Action | Trigger | Effect |
|--------|---------|--------|
| New | startup (default) or `/new` | Fresh session file, empty transcript |
| Continue | `--continue` / `-c` | Reopen the most recent session for this cwd |
| Resume | `--resume` / `-r` or `/resume` | Pick a past session for this cwd |
| Name | `--name` / `/name <n>` | Human-readable label |
| Ephemeral | `--no-session` | Keep in memory only; never persist |

For Hosted execution, “new” reserves a distinct server-session id, while continue/resume reconnects the exact persisted
binding. Switching away from a persisted Hosted session detaches it without deletion; `--no-session` has no resumable
owner and delete-only cleans its remote resource on replacement/close.

## Storage

Sessions auto-save under `~/.konductor/sessions/`, organized by working directory, one **JSONL** file per session.
Transcript entries are append-only: each entry is one line, written as it is produced, so a crash leaves a valid partial
session.

Header metadata changes (name, model, and PromptAgent binding) do not rewrite the accepted file directly. The store
serializes an immutable candidate header to a sibling temporary file followed by the existing transcript bytes in their
exact order, forces and closes the complete candidate, then replaces the accepted file with same-filesystem
`ATOMIC_MOVE` + `REPLACE_EXISTING`. When the filesystem does not support `ATOMIC_MOVE`, it falls back to
`REPLACE_EXISTING` without first deleting the accepted file; the candidate is still complete and closed, although the
JDK does not guarantee crash atomicity for that fallback. Failed writes/replacements remove temporary debris and leave
callers responsible for retaining the old live metadata.

```
~/.konductor/sessions/<cwd-hash>/<session-id>.jsonl
```

## Entry model & on-disk schema

Each line is a JSON object with a `type` discriminator, matching the
[domain model](architecture.md#core-domain-model). The first line is a header.

Prompt/client-owned sessions retain the v1 header:

```jsonl
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440000","version":1,"name":"refactor auth","cwd":"/repo","model":"gpt-5-mini","createdAt":"...","promptAgentName":"konductor-coder"}
{"type":"user","id":"01J...","parentId":"01J...hdr","timestamp":"...","text":"add retries to the client"}
{"type":"assistant","id":"...","parentId":"...","timestamp":"...","text":"I'll read the client first.","toolCalls":[{"callId":"c1","name":"read","argumentsJson":"{\"path\":\"Client.kt\"}"}],"usage":{"inputTokens":1200,"outputTokens":80,"totalTokens":1280}}
{"type":"tool_call","id":"...","parentId":"...","timestamp":"...","call":{"callId":"c1","name":"read","argumentsJson":"{...}"}}
{"type":"tool_result","id":"...","parentId":"...","timestamp":"...","result":{"callId":"c1","output":"...","isError":false,"truncatedBytes":0}}
{"type":"compaction","id":"...","parentId":"...","timestamp":"...","summary":"## Goal ...","firstKeptEntryId":"01J...","tokensBefore":48000}
```

A persisted Hosted session uses a v2 header. Its service id is reserved as the same UUID as the local session before
network creation:

```jsonl
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440000","version":2,"cwd":"/repo","model":"hosted","createdAt":"...","hostedAgentName":"konductor-hosted","hostedSessionId":"550e8400-e29b-41d4-a716-446655440000"}
```

Both Hosted fields are required together. The binding itself distinguishes server-owned Hosted state, avoiding a
generic persisted provider-kind enum. New code continues to read v1 as Prompt/client-owned. Old binaries reject v2 via
the existing newer-version guard instead of ignoring and later dropping the Hosted identity. Existing pre-v2 Hosted
transcripts contain no recoverable server id and cannot be resumed as Hosted.

Notes:
- `parentId` links entries in order. It is a linear chain now; the field is kept so **branching** can be added later
  without a format change ([future.md](../future.md)).
- Tool results are stored verbatim (already truncated by the tool, [tools.md](tools.md)).
- `compaction` entries record the summary and where kept messages resume (`firstKeptEntryId`).
- `promptAgentName` (v1 header, optional) records the persisted **PromptAgent** name. On resume Konductor validates
  and rebinds it; ephemeral sessions omit the field ([providers.md](providers.md#persisted-prompt-agents-promptagent)).
- `hostedAgentName` + `hostedSessionId` (v2 header) are an indivisible Hosted binding. A partial binding is invalid.
- Failed or cancelled partial turns keep the user entry and any completed tool call/results, because those actions
  happened. Partial assistant text is display-only and is not written without terminal `TurnCompleted`; a dedicated
  failure/aborted entry remains deferred.
- `src/test/resources/session/current-session-v1.jsonl` remains the compatibility golden for the Prompt header and
  every current `Entry` subtype. Hosted persistence adds a separate v2 golden; it does not rewrite the v1 contract.

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
    fun rewrite(session: Session)                       // compaction transcript rewrite
    fun load(id: Uuid): Session
    fun listForCwd(cwd: Path): List<SessionSummary>     // id, name, updatedAt, message count
    fun rename(session: Session, name: String)
    fun persistMetadata(session: Session, candidate: SessionMetadata)
    fun locate(session: Session): Path?
}
object NoOpSessionStore : SessionStore   // in-memory implementation for --no-session/tests
```

`SessionMetadata` is the immutable candidate containing the header's mutable `name`, `modelName`, and
`promptAgentName`. A caller derives a candidate from the live session, asks the store to persist it, and commits the
candidate to the live `Session` only after persistence returns successfully. `rename` follows that ordering internally;
model switching and PromptAgent binding use the same contract. The no-op store preserves the ordering while performing
no I/O.

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
- `/agent use|create` persists candidate `Session.promptAgentName` metadata before committing the live session and
  binding/status state ([tui.md](tui.md#slash-commands)).
- Compaction is untouched — see the server-side-overhead note in [compaction.md](compaction.md).

## Hosted bindings & resume

A persisted Hosted binding is allocated when the local session is created, before a remote sandbox exists. Activation
runs before the first local user entry is recorded:

1. `getSession(hostedAgentName, hostedSessionId)` reconnects an existing resource.
2. If it is missing **and the local transcript is empty**, create that exact caller-provided id. A create `409` or
   ambiguous transport result is reconciled with bounded `getSession` polling; create is not assumed idempotent and an
   alternate id is never allocated.
3. If it is missing and local entries exist, fail explicitly. Creating a replacement would discard authoritative
   server history while presenting the old audit transcript.

Resume also fails for a configured-agent mismatch or terminal service status. `ACTIVE` and auto-suspended `IDLE` are
usable; `CREATING`/`UPDATING` are boundedly polled; `FAILED`/`DELETING`/`DELETED`/`EXPIRED` are terminal. Local entries
are never replayed to a replacement Hosted session.

TUI `/new` and ACP `session/new` allocate different bindings. TUI resume/continue and ACP `session/load` activate the
selected binding. Cancellation keeps it because the service may already have advanced even when no terminal assistant
entry was written locally. Normal switching and provider/process close detach durable bindings without deletion.
Foundry auto-suspends idle sessions and expires them 30 days after last activity.

`--no-session` has no persisted owner, so its Hosted sandbox is runtime-owned and delete-only cleanup runs when `/new`
replaces it or the provider closes. A future explicit local-session delete must delete only its owned remote binding
before removing JSONL. Konductor does not sweep unknown service sessions. See
[I028](../iterations/I028-hosted-session-lifecycle.md) for the implementation and validation contract.

## Related docs

[architecture.md](architecture.md) · [compaction.md](compaction.md) · [providers.md](providers.md) ·
[hosted-agents.md](hosted-agents.md) · [tui.md](tui.md)
