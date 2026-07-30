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
| New | startup (default) or `/new` | Fresh empty candidate; session file is published only after validation |
| Continue | `--continue` / `-c` | Reopen the most recent session for the canonical launch cwd only |
| Resume | `--resume <id>` / `-r <id>` or `/resume` | Resume that exact id; startup rejects a different canonical cwd |
| Name | `--name` / `/name <n>` | Human-readable label |
| Ephemeral | `--no-session` | Keep in memory only; never persist |

TUI session selection is exact-canonical-cwd-scoped. `--resume <id>` accepts the stored session only when its persisted
canonical cwd equals the canonical launch cwd under host path semantics; sharing a Git workspace root is insufficient.
`--continue` selects only the most recent session for that canonical cwd. A mismatch or corrupt cwd fails before
workspace trust, project settings, model discovery, provider construction, or metadata writes. After trusted sources
establish effective TUI kind, strict persisted kind must match it: Prompt-over-Hosted and Hosted-over-Prompt both fail
before project/catalog construction, discovery, provider/service operations, or writes, with no migration.
`--continue` does not skip an opposite-kind newest session or silently start new. Only no canonical-cwd match becomes
ordinary new-session startup, where Prompt applies eligible local precedence and performs pre-provider discovery when
unresolved.

For Hosted execution, “new” reserves a distinct server-session id, while matched continue/resume reconnects the exact
persisted binding. Switching away from a persisted Hosted session detaches it without deletion; `--no-session` has no
resumable owner and delete-only cleans its remote resource on replacement/close.

### TUI startup workspace binding

The TUI runtime, context discovery, project configuration, and built-in tools are rooted in the launch cwd, so startup
session selection cannot silently retarget them. After parsing process inputs and resolving the external config and
session-storage directory, Konductor canonicalizes the existing launch cwd and performs header selection **before**
reading workspace trust/project configuration or constructing credentials, `FoundryProjectRuntime`, provider, context,
or tools:

- `--resume <id>` uses the read-only `SessionStore.loadHeader(id)` inspection path, canonicalizes the persisted cwd, and
  requires it to equal the canonical launch cwd under host path semantics before loading the transcript. A different
  directory is rejected with both paths and guidance to launch Konductor from the session's workspace. Sharing a Git
  root is not sufficient: different cwd values can change nested context, project settings/dotenv eligibility, and tool
  containment. Missing/non-directory persisted cwd, failed canonicalization, or mismatch aborts startup before runtime
  construction; Konductor never re-roots the launch to the session and never runs it under launch-workspace context.
- `--continue` asks `SessionStore.mostRecentForCwd(canonicalLaunchCwd)` only, then inspects/loads that exact header. It
  neither scans another cwd bucket nor falls back to global recency. No match allocates a provisional fresh candidate
  for the canonical launch cwd.
- startup without either flag allocates a provisional fresh candidate for the canonical launch cwd. `--name` is part of
  the selected/candidate header only after selection succeeds.

After selection, Konductor resolves trust and eligible configuration, validates the final configuration and binding
shape, and constructs credentials, Foundry/project/provider runtime, the path-bearing context block, and cwd-bound tools.
For a new candidate, `persistNew(candidate)` performs the one durable header commit only after every local validation
and construction step succeeds and immediately before the session is published to the TUI. Existing resumed sessions
need no new-header commit. Any preceding failure closes provisional resources and leaves no session file; a
`create`-then-delete rollback is prohibited. TUI `/new` follows the same candidate → local validation → `persistNew` →
publish ordering (the already resolved trust/configuration may be reused only while its cwd and inputs are unchanged).

This launch-cwd rejection is an intentional Konductor difference from pi 0.82.1. ACP `session/load` remains
persisted-cwd-authoritative and may load a session whose cwd differs from the process launch directory, because its
per-session runtime is built only after that cwd is known ([acp.md](acp.md#how-it-maps-onto-konductor)).

## Storage

Sessions auto-save under `<config-dir>/sessions/`, where the effective user config directory is resolved as defined in
[configuration.md](configuration.md#config-directory). They are organized by working directory, one **JSONL** file per
session. Normal transcript writes are append-only: each entry is one line, written as it is produced. Appends are not
forced or transactional; an interrupted write can leave an incomplete trailing line. Compaction is the exception
because its marker must be inserted before the first kept entry, so it replaces the complete transcript.

Header metadata changes and compaction transcript rewrites do not write the accepted file directly. For metadata, the
JSONL store serializes an immutable candidate header followed by the existing transcript bytes in their exact order.
For compaction, it serializes the current header followed by every candidate entry in the exact supplied order. In both
cases it writes a sibling temporary file, forces and closes the complete candidate, then requests same-filesystem
`ATOMIC_MOVE` + `REPLACE_EXISTING`. A candidate-write or replacement failure leaves the accepted file in place. If
atomic move is unsupported, the operation fails without a non-atomic replacement attempt. A successful move changes
the accepted path atomically; the store does not force the parent directory and therefore does not promise that the
directory entry survives an operating-system or power failure. Temporary-file cleanup after failure is best-effort and
may leave a sibling `.tmp` file.

Within one `JsonlSessionStore` instance, the filesystem phases of append, transcript rewrite, and metadata replacement
operations are serialized per session, so those file operations do not overlap. `AgentLoop` additionally serializes
turn recording and manual/automatic compaction across planning, candidate construction, persistence, and the in-memory
commit. Direct callers that mutate a `Session` or construct a rewrite candidate concurrently are unsupported, as are
writers using another store instance or process; the store lock alone does not make an externally built candidate a
transactional snapshot of arbitrary `Session` mutation.

```
<config-dir>/sessions/<cwd-hash>/<session-id>.jsonl
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
- `promptAgentName` (v1 header, optional) records the persisted **PromptAgent** name. On resume Konductor rebinds that
  name through the agent-scoped Responses endpoint; no version/layout field is added to the header. Ephemeral sessions
  omit the field ([providers.md](providers.md#persisted-prompt-agents-promptagent)).
- `hostedAgentName` + `hostedSessionId` (v2 header) are an indivisible Hosted binding. A partial binding is invalid;
  both values must be non-blank, the server id must equal the header UUID, and v2 cannot also carry
  `promptAgentName`. V1 rejects Hosted fields rather than silently discarding them.
- New v2 Hosted headers write the canonical `"hosted"` model placeholder. For compatibility, loaders accept any
  non-blank model in an otherwise valid Hosted binding and preserve it exactly as opaque metadata; provider/runtime
  code neither uses it as an execution target nor silently rewrites it to the canonical placeholder. Missing/blank
  model remains corrupt. A v1 Prompt header whose deployment happens to be named `hosted` is still Prompt because
  binding fields/version, not the model string, determine kind.
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
    fun newCandidate(cwd: Path, model: String, name: String?): Session // allocation only; no filesystem write
    fun persistNew(candidate: Session)                    // one durable, create-new header publication
    fun loadHeader(id: Uuid): SessionHeader               // header-only inspection; never creates or repairs
    fun load(id: Uuid): Session                           // header + transcript
    fun append(session: Session, entry: Entry)             // writes one JSONL line
    fun rewrite(session: Session, candidateEntries: List<Entry>) // atomic compaction transcript candidate
    fun listForCwd(cwd: Path): List<SessionSummary>        // id, name, updatedAt, message count
    fun mostRecentForCwd(cwd: Path): SessionSummary?       // never searches another cwd/global recency
    fun rename(session: Session, name: String)
    fun persistMetadata(session: Session, candidate: SessionMetadata)
    fun locate(session: Session): Path?
}
object NoOpSessionStore : SessionStore   // in-memory implementation for --no-session/tests
```

`newCandidate` allocates UUID/creation time and canonical header fields in memory and performs no directory or file
operation; it is not listable or locatable as a durable session. Runtime/binding preparation may complete the candidate
header, but no transcript append or metadata replacement may occur before `persistNew`. `loadHeader` reads and validates
only an existing first line for selection/inspection and remains separate from full `load` and from allocation.

`persistNew` accepts an empty, fully validated candidate exactly once. The JSONL store writes the complete header plus
newline to a create-new sibling candidate, forces and closes it, and atomically publishes it at the absent final path
without replacement; unsupported atomic publication fails with no non-atomic fallback. It never overwrites an existing
id. Directory-entry force is used where supported; the portable guarantee is a forced file plus atomic publication.
Failure leaves no accepted header and only best-effort removable sibling temporary state. `NoOpSessionStore.persistNew`
is explicitly a no-op, so the same frontend ordering publishes the in-memory candidate without I/O. Implementations do
not emulate provisional creation by creating an accepted header early and deleting it after validation failure.

`SessionMetadata` is the immutable candidate containing the header's mutable `name`, `modelName`, and
`promptAgentName`. Rename and model switching derive a candidate from an already published live session, ask the store
to persist it, and commit the candidate to the live `Session` only after persistence returns successfully.
`SessionStore.rewrite` is an abstract interface member, so every store must choose durable or ephemeral behavior at
compile time rather than inheriting a runtime fallback. `NoOpSessionStore` explicitly implements both
`persistMetadata` and `rewrite` as no I/O; the shared paths still commit successful metadata/compaction candidates to
the live in-memory session. PromptAgent binding retains its existing binder-first behavior pending the two-phase design
in [issue #92](https://github.com/jpalvarezl/Konductor/issues/92).

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

- **Resume** validates and rebinds the session's recorded `promptAgentName` through the name-scoped endpoint. If the
  agent was deleted server-side, Konductor falls back to ephemeral and keeps the transcript. Legacy and newly created
  versions retain their baked base + configured append and receive only the shared current path-bearing context block +
  environment dynamically;
  resume performs no version-layout metadata inspection or mismatch warning.
- `/agent use|create` updates `Session.promptAgentName` and persists the live session header
  ([tui.md](tui.md#slash-commands)); coordinated binder + metadata commit is tracked in
  [issue #92](https://github.com/jpalvarezl/Konductor/issues/92).
- Compaction is untouched — see the server-side-overhead note in [compaction.md](compaction.md).

## Hosted bindings & resume

A persisted Hosted binding is allocated in the local session candidate before `persistNew`, while no remote sandbox
exists. Activation runs after durable publication and before the first local user entry is recorded:

1. `getSession(hostedAgentName, hostedSessionId)` reconnects an existing resource.
2. If it is missing **and the local transcript is empty**, create that exact caller-provided id. A create `409` or
   ambiguous transport result is reconciled with bounded `getSession` polling; create is not assumed idempotent and an
   alternate id is never allocated.
3. If it is missing and local entries exist, fail explicitly. Creating a replacement would discard authoritative
   server history while presenting the old audit transcript.

Resume also fails for a configured-agent mismatch, returned-id/version-shape mismatch, unknown status, or terminal
service status. `ACTIVE` and auto-suspended `IDLE` are usable; `CREATING`/`UPDATING` are polled up to 30 checks at
two-second intervals; `FAILED`/`DELETING`/`DELETED`/`EXPIRED` are terminal. Conflict/ambiguous-create reconciliation
uses the same bound. Local entries are never replayed to a replacement Hosted session.

TUI `/new` and ACP `session/new` allocate different bindings. An explicit process-level Hosted selection plus
`--model` fails before ACP transport, but Hosted does not require a process-default model. Otherwise Hosted
`session/new` resolves requested-cwd trust/settings and final kind through the common composition path, rejects an
explicit `--model` at method level, then ignores ambient process-environment, trusted cwd dotenv, trusted project, and
global model values. The Prompt-only CLI > real process environment > trusted
cwd dotenv > trusted project > global precedence and provenance tag do not apply. Hosted finalization supplies the
canonical `hosted` placeholder to the shared non-null configuration shape and persists exactly that value in every new
Hosted header. Runtime preparation
precedes header commit; failures close prepared resources and leave no listable/loadable local header or remote Hosted
resource.

TUI resume/continue activates only a binding whose strict persisted kind matches effective TUI kind. ACP
`session/load` instead derives kind from the strict header, resolves trust and eligible fresh sources for its persisted
cwd, then overlays exact persisted model/kind/binding before final validation; a fresh process/project kind cannot veto
or replace it. Internally inconsistent headers, unusable bindings, and missing/blank/corrupt model metadata remain
method-level errors. Prompt receives no ambient fallback or discovery; Hosted preserves a non-blank legacy value rather
than rewriting it. Cancellation keeps an activated binding because the service may already have advanced even when no
terminal assistant entry was written locally. Normal switching and provider/process close detach durable bindings
without deletion.
Foundry auto-suspends idle sessions and expires them 30 days after last activity.

`--no-session` has no persisted owner, so its Hosted sandbox is runtime-owned and delete-only cleanup runs when `/new`
replaces it or the provider closes. A future explicit local-session delete must delete only its owned remote binding
before removing JSONL. Konductor does not sweep unknown service sessions. See
[I028](../iterations/I028-hosted-session-lifecycle.md) for the implementation and validation contract.

## Related docs

[architecture.md](architecture.md) · [compaction.md](compaction.md) · [providers.md](providers.md) ·
[hosted-agents.md](hosted-agents.md) · [tui.md](tui.md)
