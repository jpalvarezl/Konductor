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
| `grep` | `pattern`, `path?`, `glob?` | Regex search file contents; return matching lines | no |
| `bash` | `command`, `timeout?` | Run a shell command in `cwd`; capture stdout+stderr+exit code | yes* |
| `write` | `path`, `content` | Create/overwrite a file | yes |
| `edit` | `path`, `oldString`, `newString` | Replace an exact unique occurrence | yes |

\* `bash` can do anything; treat it as workspace-mutating. Read-only mode disables `bash`, `write`, `edit`.

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
    val mutability: ToolMutability
    suspend fun execute(call: ToolCall, ctx: ToolContext): ToolResult
}

data class ToolContext(val cwd: Path)

class ToolRegistry(tools: List<Tool>, private val allow: Set<String>? = null) {
    private val byName = tools.associateBy { it.spec.name }

    fun enabled(): List<Tool> = byName.values.filter { allow == null || it.spec.name in allow }
    fun get(name: String): Tool? = byName[name]?.takeIf { allow == null || name in allow }
}
```

The registry is wired into the [`ToolExecutor`](architecture.md#the-agentprovider-seam) the agent loop passes to
`runTurn`:

```kotlin
class RegistryToolExecutor(
    private val registry: ToolRegistry,
    private val context: ToolContext,
    private val approvals: ToolApprovalPolicy,
    private val maxOutputBytes: Int = MAX_TOOL_OUTPUT_BYTES,
) : ToolExecutor {
    override suspend fun execute(call: ToolCall): ToolResult {
        val tool = registry.get(call.name)
            ?: return ToolResult(call.callId, "unknown or disabled tool: ${call.name}", isError = true)

        val approval = if (tool.mutability == ToolMutability.Mutating) {
            approvals.decide(call)
        } else {
            ToolApprovalDecision.AllowOnce
        }
        if (!approval.allowsExecution) {
            return ToolResult(
                call.callId,
                "tool call denied by policy: ${call.name}; do not retry without new user approval",
                isError = true,
            )
        }

        val result = try {
            executeAfterCancellationSafeAdmission(tool, call, context, approval)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            ToolResult(
                callId = call.callId,
                output = "tool '${call.name}' failed: ${error.message ?: error::class.simpleName}",
                isError = true,
            )
        }
        return truncateToolResult(result, maxOutputBytes)
    }
}
```

The exact policy/admission implementation may use different internal names, but the ordering is contractual: resolve
registry enablement, classify, decide if mutating, win cancellation-safe side-effect admission, commit any session
allow, then call the tool.
Cancellation is deliberately caught before `Throwable` and rethrown; the executor must never convert it to a
`ToolResult`.

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

`grep` uses a **`ripgrep` (`rg`) binary when one is on `PATH`** (fast, `.gitignore`-aware, binary-skipping) and
otherwise falls back to a **portable in-process search** — so search works with no external dependency. Both
`find` and the in-process `grep` prune noise directories (`.git`, `node_modules`, `target`, `build`, `.gradle`,
`.idea`, …) and skip binary / non-UTF-8 files. Glob patterns match working-directory-relative paths, and a
leading `**` also matches at depth zero (so `**/*.kt` finds top-level files too, working around a Java NIO glob
quirk). Bundling `rg` with releases — for ripgrep everywhere while staying self-contained — is tracked in
[future.md](../future.md).

## Safety & approval

Three independent boundaries apply in order:

1. **Enablement.** `--tools` / `--exclude-tools` select the registry's active set
   ([configuration.md](configuration.md)). Read-only mode (`--tools read,ls,find,grep`) removes every mutating tool from
   both advertisement and lookup. Approval can never enable an unknown or disabled tool.
2. **Authorization.** Every enabled local tool has harness-owned, non-model-facing mutability metadata. The `read`,
   `ls`, `find`, and `grep` tools are **read-only**; `bash`, `write`, and `edit` are **mutating**. Every `bash` call
   remains mutating even when a particular command appears read-only. Future tools must classify explicitly; absent or
   unrecognized classification fails closed as mutating. Read-only calls execute without an approval prompt; mutating calls enter
   the shared approval policy.
3. **Containment.** After authorization, path arguments are resolved and must stay within `cwd`; `..` and symlink
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

Unknown/disabled calls retain the stable error `unknown or disabled tool: <name>`. Every explicit denial or unavailable
headless approval path returns the stable, nonlocalized error
`tool call denied by policy: <name>; do not retry without new user approval` with `isError = true` and the original call
id. The ordinary started/completed event and transcript shape is retained so the model can recover. Cancelling while an
approval is pending instead propagates `CancellationException`; it creates no synthetic denial result, though the
already-observed tool-call entry may remain in the activity transcript.

Authorization is the final harness gate before possible side effects. One exact pending call owns the session's gate,
and the existing Prompt loop services multiple calls sequentially. Approval/cancellation is linearized at execution
admission: cancellation before admission performs no side effect and does not cache a session allow; once execution is
admitted, later cancellation follows the tool's existing best-effort cleanup and no-rollback rules. Stale UI/protocol
responses and overlapping approval attempts fail closed and cannot authorize a different call.

The TUI and ACP presentation/mapping are specified in [tui.md](tui.md#tool-approval-overlay) and
[acp.md](acp.md#mutating-tool-permissions). Hosted container/server tools are outside this local executor policy.

## Adding a tool

1. Implement `Tool` (spec + explicit mutability + `execute`).
2. Register it in `ToolRegistry`.
3. It automatically appears in `AgentContext.tools` and is callable by the model.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [agent-context.md](agent-context.md) ·
[configuration.md](configuration.md)

**Sample:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/FunctionCallSync.java`.
