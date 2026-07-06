# Agent Context (Preamble / System Prompt)

The **agent context** is everything the model sees *before* the running transcript: the system/developer prompt,
project context files, and the tool surface. It is assembled once per turn into
[`AgentContext`](architecture.md#agentcontext-the-preamble) and handed to the provider.

> Code blocks are illustrative design sketches, not committed implementation.

## What goes into the preamble

```
AgentContext.systemPrompt =
    [1] base system prompt          (Konductor's built-in coding-agent instructions)
  + [2] context files               (AGENTS.md / CLAUDE.md content, concatenated)
  + [3] environment header          (cwd, OS, shell, date)

AgentContext.tools = ToolRegistry.enabled()   // rendered as FunctionTool definitions
```

For the Prompt provider this maps to Responses `instructions` (+ `tools`); for a registered Prompt agent it maps to
`PromptAgentDefinition.setInstructions(...)` / `.setTools(...)` ([providers.md](providers.md)).

## Base system prompt

A concise coding-agent prompt (kept small; workflow detail lives in tools/skills). Suggested default:

```text
You are Konductor, a terminal coding agent operating in the user's current working directory.
- Prefer using tools to inspect and edit files rather than guessing.
- Read a file before editing it. Make minimal, correct changes.
- Use `bash` for commands; keep outputs small (they are truncated).
- Explain briefly, then act. Stop when the task is done.
Environment: cwd={cwd}, os={os}, shell={shell}, date={date}.
```

Overridable via `--system-prompt` / settings ([configuration.md](configuration.md)). An **append** hook lets
projects add rules without replacing the base.

## Context files

Konductor discovers `AGENTS.md` (fallback `CLAUDE.md`) and appends their content to the system prompt:

1. `~/.konductor/AGENTS.md` (global)
2. Each parent directory from the repo root down to `cwd`
3. `cwd/AGENTS.md`

Later files win / append. Discovery is disabled with `--no-context-files`. Keep these files short — they cost
tokens every turn.

## Assembly order & precedence

```kotlin
class AgentContextBuilder(cfg: Config, registry: ToolRegistry) {
    fun build(cwd: Path): AgentContext {
        val prompt = buildString {
            append(cfg.systemPromptOverride ?: BASE_PROMPT.render(cwd))
            contextFiles(cwd).forEach { append("\n\n").append(it) }
            cfg.systemPromptAppend?.let { append("\n\n").append(it) }
        }
        return AgentContext(prompt, registry.enabled().map { it.spec }, cfg.model, cfg.temperature)
    }
}
```

Order is deterministic so caching and reproducibility hold: **base → context files → append → tools**.

## Tool surface & truncation

The context advertises tools by name + JSON-schema parameters ([tools.md](tools.md)). Tool **outputs** are
truncated before re-entering context (large `read`/`bash` results dominate token usage). The truncation contract is
made explicit to the model so it can request more if needed:

```text
[output truncated: showed 2000 of 18734 bytes — narrow your request or read a range]
```

The exact policy (limits, markers) lives in [tools.md](tools.md); compaction applies a second, coarser truncation
when summarizing ([compaction.md](compaction.md)).

## Structured inputs (optional)

`PromptAgentDefinition.setStructuredInputs(...)` supports template substitution / tool-argument binding. Konductor
does not need this for the basic coding loop; document it here if/when a template-driven agent is added.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [tools.md](tools.md) ·
[compaction.md](compaction.md) · [configuration.md](configuration.md)
