# Agent Context (Preamble / System Prompt)

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md).

**Purpose:** how Konductor assembles what the model sees before the conversation — the system/developer message,
context files, and the tool surface.

## System / developer message

TODO: the default coding-agent system prompt; how it maps to Responses `instructions` (and/or
`PromptAgentDefinition.setInstructions`). Overriding/appending.

## Context files

TODO: `AGENTS.md`-style project context discovery (cwd + parents + global); enable/disable.

## Prompt assembly order

TODO: the exact order — system prompt → context files → tool definitions → transcript. Note truncation policy links
to [tools.md](tools.md).

## Structured inputs

TODO: if/how `setStructuredInputs` / template substitution is used.

## Tool surface

TODO: how the [tools.md](tools.md) registry is rendered into `tools` on each request, and how tool output truncation
is communicated to the model.

## References

- [index.md](index.md) · [providers.md](providers.md) · [tools.md](tools.md)
