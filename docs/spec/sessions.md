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
A new Prompt candidate carries its exact validated PromptAgent name; a mutually exclusive new Hosted candidate
carries its Hosted binding. The selected binding is part of the unpublished candidate before `persistNew(candidate)`
performs the one durable header commit. Ephemeral Prompt uses in-memory `null`, which the header codec persists by
omitting `promptAgentName`. Provider binding preparation and every other local
validation/construction step complete before that commit and immediately
before the session/runtime/status are published to the TUI. Existing resumed sessions need no new-header commit. Any
preceding failure closes provisional resources and leaves no session file; a `create`-then-delete rollback is
prohibited. TUI `/new` copies the current committed PromptAgent name into its candidate and follows the same candidate
→ local validation → `persistNew` → publish ordering (the already resolved trust/configuration may be reused only while
its cwd and inputs are unchanged). It never publishes an unbound header and repairs it with `persistMetadata`.

This launch-cwd rejection is an intentional Konductor difference from pi 0.82.1. ACP `session/load` remains
persisted-cwd-authoritative and may load a session whose cwd differs from the process launch directory, because its
per-session runtime is built only after that cwd is known ([acp.md](acp.md#how-it-maps-onto-konductor)).

## Storage

Sessions auto-save under `<config-dir>/sessions/`, where the effective user config directory is resolved as defined in
[configuration.md](configuration.md#config-directory). They are organized by working directory, one **JSONL** file per
session. Non-terminal user and completed tool records use ordinary one-line append. Those appends are not forced or
transactional: an exception can mean no bytes, a matching partial suffix (including bytes ending inside a UTF-8 code
point), a complete line, or a complete JSON record at EOF without its delimiter reached the accepted path. Before such
an exception escapes, the store poisons that session id. This blocks every later same-session write and turn, so a
second ambiguous append cannot exist. The sole in-process exception is the already active turn's terminalization request
carrying that exact attempted entry and live prefix; accepted bytes must be reconciled before ordinary use resumes.

Terminal entries are different. `AssistantEntry`, `FailedEntry`, and `AbortedEntry` are never ordinarily appended;
`SessionStore.terminalize` accepts them only through one forced-and-closed complete-file candidate and atomic
replacement. It reconciles the caller's live prefix and at most the immediately preceding ambiguous non-terminal append
against the accepted path first. Consequently an assistant whose atomic move completed before an exception cannot be
followed by a failed outcome, and retrying a deterministic terminal cannot add a second terminal. Every legacy v1/v2
terminalization promotes its header to v3 in the same replacement, including a successful assistant terminal.

Header metadata changes, compaction transcript rewrites, and terminalization do not write the accepted file directly.
Metadata serializes an immutable candidate header followed by the existing transcript bytes. Compaction serializes the
current v3 header followed by every candidate entry in supplied order. Terminalization uses the byte-preserving rules
below. Each operation writes a sibling temporary file, forces and closes the complete candidate, then requests
same-filesystem `ATOMIC_MOVE` + `REPLACE_EXISTING`. Unsupported atomic move fails without fallback. A successful move
changes the accepted path atomically; the store does not force the parent directory and therefore does not promise that
the directory entry survives an operating-system or power failure. Temporary cleanup is best-effort.

The final accepted path is authority, not the temporary name or whether `replaceAtomically` returned normally. Any
terminalization candidate/replacement failure is reconciled by rereading that path while still holding the lock: an
exact sole terminal is accepted; rejection requires byte-for-byte equality with the caller's validated live prefix,
without a logically discarded suffix or complete ambiguous extension; every other state is indeterminate and remains
poisoned. In particular, rejecting atomic replacement after candidate construction discarded a matching partial suffix
leaves that suffix in the accepted file and can never be reported as a clean rejection. Other replacement operations
retain their existing accepted-file restart authority but do not gain terminal reconciliation.

Within one `JsonlSessionStore` instance, append, transcript rewrite, metadata replacement, and terminalization filesystem
phases are serialized per session. Poison is checked under that same per-session lock before every mutator; neither a
read nor a same-writer reload clears it. `AgentLoop` additionally serializes turn recording and compaction through
matching in-memory commit. Another store instance/process and concurrent direct `Session` mutation remain unsupported.

### Background TUI command commits

A session-mutating local background command separates cancellable **preparation** from **commit**. Preparation may load
and validate a detached session, build immutable metadata/transcript candidates, perform read-only discovery, or obtain
a compaction summary, but it does not publish a header, replace accepted metadata/transcript bytes, retarget the active
session/provider binding, or mutate command-result `AppState`. One atomic command phase lets cancellation race
`beginCommit`; cancellation that wins prevents every operation below. Admission checks the launched job immediately
before and after its provisional phase CAS, so direct child/parent cancellation observed before the final check also
wins without reaching persistence.

Once `beginCommit` passes that final active-job check, the command runs persistence and matching live mutation in
`NonCancellable` and reports its natural success/failure. The ordering is:

1. persist/publish the complete immutable local candidate using the operation documented here;
2. commit that same candidate to live session/context/transcript state;
3. update TUI model/token/session state and add the command's success/failure report; and
4. clear the working state and exact active-submission identity.

Thus `Esc` immediately before metadata/rewrite/header persistence prevents that write, while `Esc` during persistence
is inert and cannot produce a cancellation report. A persistence failure leaves live state unchanged under the
candidate contracts and is reported as command failure. Expected validation happens before persistence and expected
post-persistence assignments are infallible. If a process dies or an unexpected failure occurs after an accepted
write, the accepted file is authoritative on restart; Konductor does not delete it, restore old metadata, or claim a
rollback. Forced termination has only each store operation's documented force/atomic-move guarantees.

The affected command-specific boundaries are: `/model <deployment>` persists candidate model metadata before live
context/model/status; `/compact` rewrites a candidate transcript before committing the same entry order and resetting
usage; `/new` publishes a fully prepared candidate before making it active/visible; and `/resume <number|id>` completes
any post-gate provider-binding work before replacing the current active session. The owning provider/session lifecycle
still determines remote ordering, and no local/Foundry distributed transaction is implied.

Agent turns persist the user and completed tool records as those events happen. Cancellation keeps them because they
and any external effects are real, then asks atomic terminalization to accept `AbortedEntry`. The complete candidate
preserves accepted records; removing only the exact matching partial suffix of one ambiguous append is reconciliation,
not rollback of a complete action.

```
<config-dir>/sessions/<cwd-hash>/<session-id>.jsonl
```

## Entry model & on-disk schema

Each line is a JSON object with a `type` discriminator, matching the
[domain model](architecture.md#core-domain-model). The first line is a header.

Legacy Prompt/client-owned sessions use the v1 header:

```jsonl
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440000","version":1,"name":"refactor auth","cwd":"/repo","model":"gpt-5-mini","createdAt":"...","promptAgentName":"konductor-coder"}
{"type":"user","id":"01J...","parentId":"01J...hdr","timestamp":"...","text":"add retries to the client"}
{"type":"assistant","id":"...","parentId":"...","timestamp":"...","text":"I'll read the client first.","toolCalls":[{"callId":"c1","name":"read","argumentsJson":"{\"path\":\"Client.kt\"}"}],"usage":{"inputTokens":1200,"outputTokens":80,"totalTokens":1280}}
{"type":"tool_call","id":"...","parentId":"...","timestamp":"...","call":{"callId":"c1","name":"read","argumentsJson":"{...}"}}
{"type":"tool_result","id":"...","parentId":"...","timestamp":"...","result":{"callId":"c1","output":"...","isError":false,"truncatedBytes":0}}
{"type":"compaction","id":"...","parentId":"...","timestamp":"...","summary":"## Goal ...","firstKeptEntryId":"01J...","tokensBefore":48000}
```

A legacy persisted Hosted session uses a v2 header. Its service id is reserved as the same UUID as the local session
before network creation:

```jsonl
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440000","version":2,"cwd":"/repo","model":"hosted","createdAt":"...","hostedAgentName":"konductor-hosted","hostedSessionId":"550e8400-e29b-41d4-a716-446655440000"}
```

Fresh Prompt and Hosted sessions use a v3 header. Version no longer selects kind by itself in v3: absence of both
Hosted fields is Prompt (an absent `promptAgentName` means ephemeral Prompt), while presence of both is Hosted. A
partial Hosted binding or a header carrying both Hosted fields and `promptAgentName` is corrupt. No generic persisted
provider-kind enum is added.

```jsonl
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440000","version":3,"cwd":"/repo","model":"gpt-5-mini","createdAt":"...","promptAgentName":"konductor-coder"}
{"type":"header","id":"550e8400-e29b-41d4-a716-446655440001","version":3,"cwd":"/repo","model":"hosted","createdAt":"...","hostedAgentName":"konductor-hosted","hostedSessionId":"550e8400-e29b-41d4-a716-446655440001"}
```

Only a v3 header may precede the new outcome variants:

```jsonl
{"type":"failed","id":"...","parentId":"...","timestamp":"...","userEntryId":"...","failureKind":"context_overflow","overflowStage":"retry","partialAssistant":true}
{"type":"aborted","id":"...","parentId":"...","timestamp":"...","userEntryId":"...","reason":"cancelled","partialAssistant":false}
```

New readers retain strict compatibility: v1 is Prompt-only with no Hosted fields, v2 is Hosted-only with both Hosted
fields and no PromptAgent, and v3 accepts either valid shape above. Existing pre-v2 Hosted transcripts contain no
recoverable server id and cannot be resumed as Hosted. Versions newer than v3 fail at the header guard.

Old binaries support at most v2. They reject direct load/resume of v3 before decoding entries, while their existing
catch-and-skip listing path omits v3 files; an old `--continue` may therefore start a separate legacy session but cannot
mutate the hidden v3 file. New writers emit v3 for fresh sessions and complete replacements. Ordinary non-terminal
append may leave a loaded v1/v2 header unchanged. Its next assistant/failure/abort terminalization writes one forced
sibling candidate with a v3 header, accepted prior transcript records, and exactly one terminal, then atomically
replaces. Failure leaves the legacy accepted path unchanged. Promotion is one-way.

Notes:
- `parentId` links entries in order. It is a linear chain now; the field is kept so **branching** can be added later
  without a format change ([future.md](../future.md)).
- Tool results are stored verbatim (already truncated by the tool, [tools.md](tools.md)).
- `compaction` entries record the summary and where kept messages resume (`firstKeptEntryId`).
- `promptAgentName` (Prompt v1/v3 header, optional) records the persisted **PromptAgent** name. A present name must be
  non-blank and already trimmed; encoding and loading reject blank or whitespace-padded names rather than silently
  changing the
  binding identity. On resume Konductor rebinds that exact name through the agent-scoped
  Responses endpoint; no version/layout field is added to the header. Ephemeral sessions omit the field
  ([providers.md](providers.md#persisted-prompt-agents-promptagent)).
- `hostedAgentName` + `hostedSessionId` (Hosted v2/v3 header) are an indivisible binding. A partial binding is invalid;
  both values must be non-blank, the server id must equal the header UUID, and Hosted cannot also carry
  `promptAgentName`. V1 rejects Hosted fields rather than silently discarding them.
- Fresh v3 Hosted headers write the canonical `"hosted"` model placeholder. For compatibility, loaders accept any
  non-blank model in an otherwise valid Hosted binding and preserve it exactly as opaque metadata; provider/runtime
  code neither uses it as an execution target nor silently rewrites it to the canonical placeholder. Missing/blank
  model remains corrupt. A v1 Prompt header whose deployment happens to be named `hosted` is still Prompt because
  binding fields/version, not the model string, determine kind.
- A successful assistant, terminal non-cancellation failure, or turn cancellation is accepted through
  `terminalize`, never ordinary append. Outcomes refer to the opening user through `userEntryId` and chain `parentId`
  to the accepted physical tip. The next user chains to the sole terminal. Loading retains legacy dangling turns but
  the replay-safe projection treats each dangling/failure/abort as a hard history boundary.
- `FailedEntry.failureKind` is exactly `error`, `context_overflow`, or `persistence`. An optional `overflowStage` is
  present only for context overflow and is exactly `initial`, `no_compactable_history`, `compaction`, or `retry`.
  Store-write origin takes precedence over an overflow wrapper; all other implementation/provider failures collapse
  to `error`. `AbortedEntry.reason` is currently exactly `cancelled`.
- `partialAssistant` records only whether non-empty streamed assistant text was observed (or non-empty completed text
  could not be persisted). No assistant prose, length, hash, exception message/class/stack/cause, service payload,
  request id, or duplicated tool data is persisted. There is no new text to truncate; existing tool output keeps the
  bound defined in [tools.md](tools.md).
- User and completed tool entries remain because those actions happened. Failure/abort may close a turn with an
  outstanding tool call because no result proves no absence of effect. A successful assistant with an outstanding
  call, an orphan/duplicate result, or tool/terminal activity outside an open user turn is invalid.
- `src/test/resources/session/current-session-v1.jsonl` and `current-session-v2-hosted.jsonl` remain exact decode-only
  compatibility goldens. New Prompt-v3 and Hosted-v3 goldens are the current encoder contract and cover every `Entry`
  subtype and explicit outcome defaults.

## Atomic turn terminalization

The store and every transcript consumer share one forward state machine. `BetweenTurns + UserEntry` opens a turn.
Within `Open(user)`, tool calls must be unique and results must consume an outstanding call exactly once; compaction
metadata neither opens nor closes a turn. `AssistantEntry` closes a tool-complete turn successfully. `FailedEntry` or
`AbortedEntry` closes an open turn only when its `userEntryId` names that user and its `parentId` is the current physical
tip; outstanding calls are allowed on these non-success paths. A second user closes the earlier open span as legacy
dangling and opens the next. EOF may likewise leave one dangling turn. A terminal while between turns, a second
terminal before another user, a successful terminal with an outstanding call, and orphan/duplicate tool results fail
validation. Thus each turn has at most one terminal without inventing outcomes for interrupted legacy history.

`terminalize(session, request)` receives an assistant/failure/abort draft with stable id/timestamp/payload, an immutable
live-entry prefix, and optionally the one non-terminal entry whose immediately preceding append threw. The store binds
the draft's `parentId` only after reconciliation to the actual accepted disk tip: a complete ambiguous append changes
that parent, while a discarded matching partial suffix does not. Under the lock it scans accepted bytes forward:

- LF ends a record; one CR immediately before it is the CRLF delimiter. Payload is strict UTF-8. Blank records, bare
  payload CR, malformed JSON, unknown entry/enums, or forward-state violations fail closed.
- A final suffix without LF is accepted when it is one complete decodable JSON entry; an optional final CR is treated
  as an incomplete half of CRLF rather than payload. Otherwise the suffix may be removed only when it is exactly a byte
  prefix of the supplied ambiguous entry's canonical UTF-8 encoding at the expected tip. Arbitrary corruption is never
  truncated.
- The decoded disk prefix must equal the caller's live prefix, followed by at most the supplied complete ambiguous
  entry. A complete disk extension is returned in the accepted snapshot so live state catches up. Missing, reordered,
  mismatched, or multiple unexplained records are indeterminate.
- An already accepted sole terminal for the requested user is returned unchanged. Otherwise the parent-bound candidate
  contains a canonical v3 header, accepted transcript record payloads and complete delimiters byte-for-byte, and one
  canonical terminal. A complete EOF record receives an inferred delimiter. The header retains its LF/CRLF delimiter;
  a new delimiter uses the most recent complete transcript delimiter, then the header delimiter, then LF. Mixed legacy
  line endings are not normalized.

The sibling candidate is forced/closed and atomically replaces the final path. After any candidate or move failure the
final path is rescanned. Results are mutually exclusive:

- **Accepted:** the requested or another valid sole terminal is accepted authority. Its snapshot reconciles live state
  and clears matching append poison.
- **Rejected:** accepted bytes equal the caller's validated live prefix byte-for-byte. There is no accepted terminal,
  complete ambiguous extension, or logically discarded suffix. This exact reread reconciles a zero-byte append
  exception and clears matching poison.
- **Indeterminate:** every other state. A still-present matching partial suffix—whether valid ASCII so far or ending
  inside a multibyte UTF-8 character—and a complete ambiguous extension without terminal are included. Poison remains.

A partial suffix is removed only by an accepted atomic candidate; if replacement is rejected, the accepted path still
contains it and the result is indeterminate rather than rejected. Terminal ids/content are stable across retry.
`NoOpSessionStore` performs the same prefix/state/sole-terminal decision in memory without I/O. Only an accepted result
replaces live entries; a clean rejected result proves the live prefix still matches disk.

Assistant completion uses this operation too. Clean rejection transitions the existing terminalizing phase directly to
one `FailedEntry(persistence)` attempt; this is not a new admission and never reopens `Running`. Reconciliation that
finds the assistant makes success win. An indeterminate result blocks the session, and a failed failure/abort outcome
decision is not recorded recursively. The complete loop phase graph is in
[architecture.md](architecture.md#turn-lifecycle-prompt-provider).

## Overflow-recovery persistence

Bounded Prompt context-overflow recovery writes no outcome for an intermediate overflow that safe recovery consumes.
The logical user turn is accepted once: `AgentLoop` appends its `UserEntry` before proactive compaction or the first
provider attempt, and the compact-and-retry path never calls public `AgentLoop.runTurn` or appends that user again. The
first classified overflow is an event/control signal only and is withheld when safe recovery begins. No additional
JSONL entry represents that consumed overflow or the recovery boundary. Assistant and tool entries from the second
provider attempt use normal turn persistence; only a terminal overflow writes one staged `FailedEntry`.

A reactive `CompactionEntry` uses the same complete-candidate sibling rewrite, forced close, atomic replacement, and
post-persistence in-memory commit as proactive/manual compaction. Only after that commit does the loop emit
`AgentEvent.Compacted`, reconstruct history, and start the one retry. The marker and its summarized/kept ordering remain
accepted if the retry later fails or is cancelled; restart reconstruction therefore uses the reduced transcript. The
marker is not rollback state. If summary generation returns no marker or fails, no rewrite occurs. If rewrite fails,
the previously accepted transcript remains unchanged and no retry starts.

Safe eligibility requires that the failed first attempt produced no assistant delta/completed output or tool
call/result/activity. There are therefore no first-attempt assistant/tool entries to delete before retry. An unsafe
failure follows the existing partial-turn rule: streamed text stays display-only, while any already recorded tool
call/results and their external side effects remain; no automatic replay occurs. Successful retry persists ordinary
tool and terminal assistant entries once, chained after the committed transcript tip.

Cancellation remains exception-transparent and creates no `AgentEvent.Failed`, but cancellation must win the turn's
terminal-admission gate before it can choose `AbortedEntry`. Before compaction commit the terminal follows the user and
accepted tip; after commit it follows the valid marker. A failure/assistant that admitted terminalization first makes a
later cancellation inert, and frontends use the accepted terminal rather than `Job.isCancelled`. Hosted never enters
overflow recovery; its local abort remains audit-only while server history stays authoritative.

## Reconstructing Responses `input`

Prompt reconstruction consumes the validated forward-turn analysis, not a type filter over raw entries. Every failed,
aborted, or legacy-dangling turn is a hard model-history epoch boundary: its user/tool/control records and everything
before it are excluded. After the latest such boundary, only complete successful turns with valid tool pairing are
mapped. The sole overload used during a turn additionally accepts `currentUserEntryId` and may include the exact final
open span only when its opening id matches; an arbitrary open/dangling span is never replayed. Invalid or unsegmentable
history blocks continuation rather than guessing.

Within that safe epoch, the latest usable compaction marker must itself occur after the boundary. Its summary becomes a
developer item and mapping resumes at its replayable `firstKeptEntryId`; older/tainted markers are ignored. A later
failure therefore starts clean rather than reusing a summary that may contain uncertain activity. Hosted keeps its
server-owned current-user-only request and does not reconstruct local history.

`UserEntry`/`AssistantEntry` map to messages; paired `ToolCallEntry`/`ToolResultEntry` map to function-call/output items.
Outcome and compaction entries are never direct model items. See [compaction.md](compaction.md) for indexed safe
projection and cut rounding.

## SessionStore API

```kotlin
interface SessionStore {
    fun newCandidate(cwd: Path, model: String, name: String?): Session // allocation only; no filesystem write
    fun persistNew(candidate: Session)                    // one durable, create-new header publication
    fun loadHeader(id: Uuid): SessionHeader               // header-only inspection; never creates or repairs
    fun load(id: Uuid): Session                           // header + transcript
    fun append(session: Session, entry: NonTerminalEntry)  // ordinary user/tool JSONL line
    fun terminalize(session: Session, request: TerminalizationRequest): TerminalizationResult
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

`terminalize` is abstract so every store chooses explicit exactly-one semantics; no implementation may inherit an
append fallback. `JsonlSessionStore` always uses the atomic complete-file/reconciliation contract above, while
`NoOpSessionStore` validates and accepts the in-memory candidate without I/O. The result carries the accepted snapshot
and terminal so disk ambiguity can reconcile live state before events.

Every ordinary append exception poisons the session before rethrow. All later same-session mutators fail before I/O
except the current accepted turn's one matching terminalization. Accepted replacement or an exact clean-prefix reread
clears that poison; indeterminate state never does. An initial user append failure has no loop-accepted turn and cannot
use the terminalization exception, so the same runtime accepts no subsequent turn or mutation for that session. A fresh
process/store may continue only after strict `load` establishes complete valid bytes: a complete dangling user becomes
a safe epoch boundary, while a partial/malformed UTF-8 suffix requires explicit operator repair before restart. I035
adds no automatic or general-purpose repair API.

`SessionMetadata` is the immutable candidate containing the header's mutable `name`, `modelName`,
`promptAgentName`, and Hosted binding. Rename and model switching derive a candidate from an already published live
session, ask the store to persist it, and commit the candidate to the live `Session` only after persistence returns
successfully. `SessionStore.rewrite` is an abstract interface member, so every store must choose durable or ephemeral
behavior at compile time rather than inheriting a runtime fallback. `NoOpSessionStore` explicitly implements both
`persistMetadata` and `rewrite` as no I/O; the shared paths still commit successful metadata/compaction candidates to
the live in-memory session.

PromptAgent changes add provider preparation without changing that store contract. Under the loop's single-operation
boundary, a published-session change executes:

1. `PromptAgentBinder.prepareBinding(agentName)` validates the non-blank/already-trimmed name (or explicit ephemeral
   `null`) and builds an unpublished delegate for that exact identity;
2. derive `candidate = session.metadata.copy(promptAgentName = prepared.agentName)`;
3. `persistMetadata(session, candidate)` atomically decides the durable result;
4. prepared provider commit swaps one immutable name/delegate holder without throwing;
5. `session.commitMetadata(candidate)` copies the already accepted metadata without I/O;
6. the frontend sets displayed status from the exact committed result and only then reports success.

No turn, session switch, or second PromptAgent operation may interleave these steps. Candidate abort and old/new client
cleanup are best-effort and cannot change the accepted result. Recoverable preparation or persistence failure occurs
before step 3 returns and preserves the old header, provider, live `Session`, and displayed status; there is no rollback
of an accepted header. Steps 4–6 are deliberately non-failing in-process fan-out, not a claim of cross-resource
atomicity. An implementation defect that makes them throw must not be translated into an ordinary failure claiming the
old binding.

After process or power interruption, the valid header found at the accepted JSONL path is the sole durable binding
authority. Startup/load prepares its exact `promptAgentName` and derives live/status state from it; depending on I080's
stated directory-durability limit, that header may contain the old or new complete candidate. A missing unpublished
fresh candidate is not a session. No journal, rollback, exact-version lookup, or generic transaction recovery runs.

`load` decodes every accepted record and applies the shared forward validator. `listForCwd` builds each summary from
that same validated full read—`updatedAt` is the final validated entry timestamp and `entryCount` is the physical entry
count—then retains the existing catch-and-skip behavior for invalid files. It may not decode only the last line and
advertise a session that `load` rejects. `loadHeader` remains deliberately header-only for workspace/binding selection.

The two production implementations are `JsonlSessionStore` and `NoOpSessionStore`. Direct test implementations must
also implement terminalization explicitly: `RecordingSelectionStore` in `MainTest`, and the full anonymous failing
store plus `MockMetadataSessionStore` in `AgentLoopSessionTest`. Kotlin-delegating fault stores inherit the delegate's
contract unless a terminalization fault is the subject of that test; assistant-write fault injection no longer
overrides ordinary `append`.

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

- **Resume** prepares the session's exact recorded `promptAgentName` through the name-scoped endpoint before committing
  the provider, live session selection/transcript, and status. An absent field prepares the ephemeral adapter and is the
  explicit unbind path. Preparation failure retains the previously selected session and binding. Resume does not call
  `listAgents`, silently fall back, or rewrite the accepted header when an advisory list snapshot omits a name; a
  deleted/unusable agent fails on preparation or authoritative service use and remains recorded for explicit retry.
  Legacy and newly created versions retain their baked base + configured append and receive only the shared current
  path-bearing context block + environment dynamically; resume performs no version-layout metadata inspection or
  mismatch warning.
- `/agent use|create` follows prepare → atomic metadata persistence → provider commit → live `Session` commit → status
  and reporting ([tui.md](tui.md#slash-commands)). A reported local adoption failure leaves all four states at the old
  binding. A created version is a separate remote side effect and is not deleted when later adoption fails.
- Fresh startup writes its effective configured binding in the provisional candidate before `persistNew`; `/new` adopts
  the current committed name the same way. Neither performs post-publication header repair.
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
terminal assistant entry was written locally; the local transcript records an aborted outcome after any observed tool
activity without claiming the service did not advance. Normal switching and provider/process close detach durable
bindings without deletion.
Foundry auto-suspends idle sessions and expires them 30 days after last activity.

`--no-session` has no persisted owner, so its Hosted sandbox is runtime-owned and delete-only cleanup runs when `/new`
replaces it or the provider closes. A future explicit local-session delete must delete only its owned remote binding
before removing JSONL. Konductor does not sweep unknown service sessions. See
[I028](../iterations/I028-hosted-session-lifecycle.md) for the implementation and validation contract.

## Related docs

[architecture.md](architecture.md) · [compaction.md](compaction.md) · [providers.md](providers.md) ·
[hosted-agents.md](hosted-agents.md) · [tui.md](tui.md)
