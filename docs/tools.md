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

## The `Tool` interface

```kotlin
interface Tool {
    val spec: ToolSpec                                   // name + description + JSON-schema params
    suspend fun execute(argumentsJson: String, ctx: ToolContext): ToolResult
}

data class ToolContext(val cwd: Path, val cancel: CoroutineContext)

class ToolRegistry(private val tools: Map<String, Tool>, private val allow: Set<String>?) {
    fun enabled(): List<Tool> = tools.values.filter { allow == null || it.spec.name in allow }
    fun get(name: String): Tool? = tools[name]
}
```

The registry is wired into the [`ToolExecutor`](architecture.md#the-agentprovider-seam) the agent loop passes to
`runTurn`:

```kotlin
val executor = ToolExecutor { call ->
    val tool = registry.get(call.name) ?: return@ToolExecutor ToolResult("unknown tool: ${call.name}", isError = true)
    runCatching { tool.execute(call.argumentsJson, toolCtx) }
        .getOrElse { ToolResult("tool error: ${it.message}", isError = true) }
        .let(::truncate)
}
```

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

## Safety & approval

- **Read-only mode** (`--tools read,ls,find,grep`) disables all mutating tools — good for review sessions.
- **Allowlist / denylist:** `--tools` / `--exclude-tools` select the active set ([configuration.md](configuration.md)).
- **cwd containment:** path arguments are resolved and must stay within `cwd`; reject `..` escapes.
- Hackathon scope keeps approval simple (mode + allowlist). Interactive per-call approval prompts are
  [future.md](future.md).

## Adding a tool

1. Implement `Tool` (spec + `execute`).
2. Register it in `ToolRegistry`.
3. It automatically appears in `AgentContext.tools` and is callable by the model.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [agent-context.md](agent-context.md) ·
[configuration.md](configuration.md)

**Sample:** `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/FunctionCallSync.java`.
