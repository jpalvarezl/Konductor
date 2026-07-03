# Tools

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md).

**Purpose:** the built-in tool set and the client-side `FunctionTool` execution model for the Prompt provider.

## Built-in tools

TODO: spec each tool (name, parameters, output, errors): `read`, `edit`, `write`, `bash`, `grep`, `find`/`glob`,
`ls`. Mirror pi / Copilot CLI semantics.

## The `Tool` interface

TODO: define the internal `Tool` abstraction (name, JSON schema, execute) and how it maps to
`FunctionTool(name, parameters, strict)` + `ResponseFunctionToolCallOutputItem`.

## Execution model

TODO: harness-owned loop — parse `arguments`, run locally (cwd-scoped), return output. Concurrency & cancellation.

## File-I/O & text truncation

TODO: the truncation policy (e.g. cap tool output; mark truncated bytes) so large `read`/`bash` results don't blow
the context window. Coordinate with [compaction.md](compaction.md) and [agent-context.md](agent-context.md).

## Safety & approval

TODO: which tools mutate the workspace; approval/allowlist strategy; read-only mode.

## References

- [index.md](index.md) · [providers.md](providers.md) · [agent-context.md](agent-context.md)
- Sample: `sdk/ai/azure-ai-agents/src/samples/java/com/azure/ai/agents/FunctionCallSync.java`.
