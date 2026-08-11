# Tools

Tools are the coding agent's hands. For the **Prompt provider**, each tool is exposed to the model as a client-side
`FunctionTool`; when the model calls one, the harness executes it locally (cwd-scoped) and returns the output via
the [`ToolExecutor`](architecture.md#the-agentprovider-seam). This mirrors pi / Copilot CLI.

> Code blocks are illustrative design sketches, not committed implementation.

## Built-in tool set

| Tool | Parameters | Behavior | Mutates |
|------|-----------|----------|---------|
| `read` | `path`, `offset?`, `limit?` | Return file contents (optionally a line range), with line numbers | no |
| `ls` | `path?` | List a directory (names, types) | no |
| `find` | `pattern`, `path?` | Glob file paths (e.g. `src/**/*.kt`) | no |
| `grep` | `pattern`, `path?`, `glob?` | Regex search file contents; return matching lines | no† |
| `bash` | `command`, `timeout?` | Run a shell command in `cwd`; capture stdout+stderr+exit code | yes* |
| `write` | `path`, `content` | Create/overwrite a file | yes |
| `edit` | `path`, `oldString`, `newString` | Replace an exact unique occurrence | yes |

\* `bash` can do anything; treat it as workspace-mutating. Read-only mode disables `bash`, `write`, `edit`.

† `grep` is read-only because it uses the in-process search. A future release may use only a cryptographically
verified, application-bundled absolute `rg` path under the rules below; it never resolves or launches `rg` by bare name
from `PATH`.

### Bash process lifecycle

Normal completion continues to return combined stdout/stderr and the shell exit code. Output is drained in fixed-size
chunks while CRLF, CR, and LF line endings are incrementally normalized to LF, including when CRLF spans chunks. As
with the previous line reader, a final unterminated line is treated as ending in LF. Normalized output is captured only
up to the stream cap and discarded beyond the cap so even a no-newline producer cannot grow the capture buffer or block
on a full pipe. On wall-clock timeout or coroutine cancellation, `bash` snapshots the process
descendants still observable through the JVM, requests graceful termination where the platform supports it from deepest
descendants toward the root shell, waits for bounded grace periods, and then forcefully terminates any remaining
handles in the same descendant-before-root order. Cleanup closes process streams and bounds the output-pump join so
cleanup itself cannot wait indefinitely.

Timeout remains a completed error result containing bounded partial output and a timeout note. Cancellation instead
propagates `CancellationException`: it does not produce a synthetic `ToolResult`, a normal tool-completion event, or a
tool output to persist. Commands may already have changed files or external systems before cancellation; Konductor does
not roll those side effects back.

This is best-effort termination of the process tree observable at cleanup time, not process containment. Detached or
reparented processes may no longer be descendants and are not guaranteed to terminate. Konductor does not use OS job
objects, process groups, cgroups, or equivalent platform-specific containment for `bash`.

## The `Tool` interface

```kotlin
enum class ToolMutability { ReadOnly, Mutating }

interface Tool {
    val spec: ToolSpec
    val mutability: ToolMutability // abstract: deliberately no default

    /** Pure JSON/schema preparation: no filesystem, process, network, policy, or other side effect. */
    fun prepare(call: ToolCall): PreparedToolInvocation
}

data class ToolContext(val cwd: Path)

class PreparedToolInvocation internal constructor(
    val call: ToolCall,
    val rawInput: JsonObject,
    internal val invoke: suspend (ToolContext) -> ToolResult,
)

class ToolRegistry(tools: List<Tool>, private val allow: Set<String>? = null) {
    private val byName = buildMap {
        for (tool in tools) {
            require(tool.spec.name !in this) { "duplicate tool name: ${tool.spec.name}" }
            put(tool.spec.name, tool)
        }
    }

    fun enabled(): List<Tool> = byName.values.filter { allow == null || it.spec.name in allow }
    fun get(name: String): Tool? = byName[name]?.takeIf { allow == null || name in allow }
}
```

`mutability` is a non-null enum property with no implementation on `Tool`. Each tool must therefore declare it to
compile, and the executor dispatches it with an exhaustive `when` and no `else`. There is no permissive omitted or
string-valued runtime classification. `BuiltinTools` is the authoritative, stable list; its test asserts the exact
name/classification table and registry construction rejects duplicate names.

`prepare` parses a top-level JSON object, strictly validates the tool's advertised required keys and value types, and
captures the resulting typed arguments in an immutable invocation exactly once. Unknown schema properties are rejected.
Preparation must be pure. This gives both frontends the same validated `rawInput` and ensures malformed arguments cannot
open a prompt, consume/corrupt a cached decision, mark the ACP permission channel unavailable, or reach execution.
Expected parse/schema failures use the exact stable error `invalid arguments for tool: <name>`.

The registry is wired into the [`ToolExecutor`](architecture.md#the-agentprovider-seam) the agent loop passes to
`runTurn`. Its contractual shape is:

```kotlin
class RegistryToolExecutor(
    private val registry: ToolRegistry,
    private val context: ToolContext,
    private val policy: ToolApprovalPolicy,
    private val approver: ToolApprover = DenyAllToolApprover,
) : ToolExecutor {
    override suspend fun execute(call: ToolCall): ToolResult {
        val tool = registry.get(call.name) ?: return ToolErrors.unknownOrDisabled(call)
        val prepared = try {
            tool.prepare(call)
        } catch (_: ToolArgumentException) {
            return ToolErrors.invalidArguments(call)
        }

        return when (tool.mutability) {
            ToolMutability.ReadOnly -> executePrepared(prepared)
            ToolMutability.Mutating -> policy.authorizeAndExecute(prepared, approver, ::executePrepared)
        }
    }
}
```

The exact internal names may differ, but the order does not: resolve enablement, prepare/validate immutable arguments,
exhaustively classify, obtain a decision when required, atomically admit, commit a winning session decision, then invoke
the prepared closure. `ToolErrors` (or one equivalently focused internal factory) is the only constructor for stable
unknown/disabled, malformed-argument, and policy-denial results; frontends do not reconstruct those strings. A missing
frontend approver is deny-all, never allow-all or Ask-without-a-responder. Cancellation is caught before `Throwable` and
rethrown; the executor never converts it to a `ToolResult`.

## Mapping to the SDK

`ToolSpec` → `FunctionTool(name, parametersSchema, strict = true).setDescription(...)`; a model tool call arrives as
`ResponseOutputItem.functionCall()` (`callId`, `name`, `arguments`), and Konductor returns
`ResponseFunctionToolCallOutputItem(callId, output)` on the next request ([providers.md](providers.md)). Argument
JSON is validated against the tool's schema before execution.

## File-I/O & text truncation

Tool outputs re-enter the model's context, so they are bounded:

- **Per-call cap:** default `maxToolOutputBytes = 16 KB` (configurable). Beyond it, output is cut and a marker is
  appended: `[output truncated: showed N of M bytes]`.
- **`read`:** supports `offset`/`limit` (line-based) so the model can page large files instead of dumping them.
- **`bash`:** stream cap + wall-clock `timeout` (default 120s); on timeout, return partial output + a timeout note.
- **Binary/non-UTF-8:** refuse with a short message rather than emitting garbage.

Truncation is surfaced to the model (see [agent-context.md](agent-context.md)) so it can narrow the request.
Compaction later applies its own coarser truncation when summarizing ([compaction.md](compaction.md)).

## Search: ripgrep, globs & ignored dirs

`grep` uses the **portable in-process search**. It must not probe or launch a bare `rg` name from `PATH`: project
configuration can influence process environment, and executing an untrusted replacement would make a nominally
read-only tool mutate arbitrarily. I038 exposes no external-executable configuration or PATH fallback.

A later optimized implementation may launch `rg` only after #46 updates this contract/tests and supplies a canonical
absolute regular non-symlink path under the application installation root, selected solely from package metadata, whose
SHA-256 matches the immutable release digest shipped/compiled with that distribution. Resolution and verification must
not use cwd, project/global configuration, workspace trust, user `PATH`, or model input. Any failed check falls back
in-process; it does not execute and ask for approval afterward.

Both `find` and in-process `grep` prune noise directories (`.git`, `node_modules`, `target`, `build`, `.gradle`,
`.idea`, …) and skip binary / non-UTF-8 files. Glob patterns match working-directory-relative paths, and a leading `**`
also matches at depth zero (so `**/*.kt` finds top-level files too, working around a Java NIO glob quirk). Supplying the
trusted bundled executable is tracked in [future.md](../future.md); until then production uses the in-process path.

## Safety & approval

Four independent boundaries apply in order:

1. **Enablement.** `--tools` / `--exclude-tools` select the registry's active set
   ([configuration.md](configuration.md)). Read-only mode (`--tools read,ls,find,grep`) removes every mutating tool from
   both advertisement and lookup. Approval can never enable an unknown or disabled tool.
2. **Argument preparation.** The enabled tool strictly parses and schema-validates one immutable prepared invocation.
   Malformed/non-object/wrong-type/missing/unknown-key input returns `invalid arguments for tool: <name>` before policy
   lookup or prompting. The prepared typed values and `rawInput` are the values shown, authorized, and executed.
3. **Authorization.** The `read`, `ls`, `find`, and trusted-execution `grep` tools are **read-only**; `bash`, `write`, and
   `edit` are **mutating**. Every `bash` call remains mutating even when a command appears read-only. The required enum
   property and exhaustive dispatch make classification compile-time explicit. Read-only calls execute without an
   approval prompt; mutating calls enter the shared policy.
4. **Containment.** After authorization, path arguments are resolved and must stay within `cwd`; `..` and symlink
   escapes remain rejected. Authorization does not weaken containment.

The stored per-tool states are **Ask**, **Allow for session**, and **Deny for session**; every mutating tool starts in
Ask. Once decisions do not change that stored state. An interactive decision is one of:

- **Allow once** — admit this exact call only;
- **Deny once** — return a denial for this exact call only;
- **Allow this tool for the session** — admit this call and later calls with the same stable tool name; or
- **Deny this tool for the session** — deny this and later calls with the same tool name.

A session decision is not a blanket mutating grant: allowing `write` does not allow `edit` or `bash`, and the arguments
of later same-tool calls are not constrained by the earlier call. TUI scope ends on `/new`, successful `/resume`, or
application exit. Each new/loaded logical ACP session has its own scope. Decisions are memory-only: they are not written
to settings, workspace trust, session JSONL, or another process. A recorded denied `ToolResult` is conversation
activity, not persisted authorization state.

Project trust is a separate boundary. Trusting project `.env`/settings—or using the existing project-trust
`--approve` flag—does not approve a mutating tool. Likewise, untrusted project configuration does not remove otherwise
enabled tools. Tool allow-lists expose capabilities but never imply permission to mutate.

Stable failures are constructed centrally and are never localized: `unknown or disabled tool: <name>`,
`invalid arguments for tool: <name>`, and
`tool call denied by policy: <name>; do not retry without new user approval`. Each has `isError = true` and the original
call id. The ordinary started/completed event and transcript shape is retained so the model can recover. Cancelling
while approval is pending instead propagates `CancellationException`; it creates no synthetic denial result, though the
already-observed tool-call entry may remain in the activity transcript.

Authorization is the final harness gate before possible side effects. Every mutating call has an unforgeable request
identity (scope generation plus call id/nonce) and one atomic state with exactly these terminal races:
`Pending(identity) → Admitted(identity) | Denied(identity) | Cancelled(identity)`. A frontend result is only a candidate
decision; after it returns, the executor checks caller activity and attempts the exact `Pending → Admitted` transition.
Every TUI/ACP/parent-cancellation path targets the same identity and attempts `Pending → Cancelled`. Only the winning
transition has an effect. A stale decision, duplicate completion, identity mismatch, or overlapping pending request
fails closed and cannot release another call. Every valid mutating call enters this state, including a cache hit: cached
allow drives the same immediate `Pending → Admitted` attempt and cached deny drives `Pending → Denied`; neither bypasses
admission/cancellation linearization or opens a frontend prompt.

An allow-for-session decision is inserted only by the `Admitted` winner; deny-for-session only by the `Denied` winner.
Thus a cancelled or malformed call cannot poison the per-tool cache. Cancellation that wins before admission performs no
side effect and stores no allow. Once `Admitted` wins, later cancellation follows the prepared tool's existing cleanup
and no-rollback rules. The existing Prompt loop services calls sequentially, so overlap is an invariant violation, not a
queue.

`PromptProvider`'s adjacent-identical-call suppression remains *before* `ToolExecutor`: “identical” is the existing exact
`(call.name, call.argumentsJson)` key for the immediately preceding call, excluding call id. It creates a synthetic error
and performs no execution, so a suppressed duplicate does not prompt, consult/change authorization state, or consume an
allow-once decision. The first non-suppressed call still goes through argument preparation and authorization normally;
a cancellation is never converted into a duplicate result.

The TUI and ACP presentation/mapping are specified in [tui.md](tui.md#tool-approval-overlay) and
[acp.md](acp.md#mutating-tool-permissions). Hosted container/server tools are outside this local executor policy.

## Adding a tool

1. Implement `Tool` (spec + required mutability + pure strict `prepare` returning an executable invocation).
2. Register it in `ToolRegistry`; duplicate names are rejected.
3. Add it to the exact classification/preparation tests. It then appears in `AgentContext.tools` and is callable by the
   model.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [agent-context.md](agent-context.md) ·
[configuration.md](configuration.md)

**Sample:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/FunctionCallSync.java`.
