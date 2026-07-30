# Agent Context (Preamble / System Prompt)

The **agent context** is everything the model sees *before* the running transcript: the system/developer prompt,
project context files, and the tool surface. It is assembled once per turn into
[`AgentContext`](architecture.md#agentcontext-the-preamble) and handed to the provider.

> Code blocks are illustrative design sketches, not committed implementation.

## What goes into the preamble

```
AgentContext.systemPrompt =
    [1] base system prompt          (Konductor's built-in coding-agent instructions)
  + [2] configured append           (operator/project instructions, when configured)
  + [3] context files               (AGENTS.md / CLAUDE.md content, concatenated)
  + [4] environment header          (cwd, OS, shell, date)

AgentContext.tools = ToolRegistry.enabled()   // rendered as FunctionTool definitions
```

For the Prompt provider this maps to Responses `instructions` (+ `tools`) sent per turn. For an opt-in **persisted
PromptAgent** ([providers.md](providers.md#persisted-prompt-agents-promptagent)) the **stable** base + configured append
and tool declarations are baked into `PromptAgentDefinition.setInstructions(...)` / `.setTools(...)`, while context
files + environment form the **dynamic preamble** sent per turn (see
[below](#persisted-agents-stable-vs-dynamic-preamble)).

## Base system prompt

A concise coding-agent prompt (kept small; workflow detail lives in tools/skills). Suggested default:

```text
You are Konductor, a terminal coding agent operating in the user's current working directory.
- Prefer using tools to inspect and edit files rather than guessing.
- Read a file before editing it. Make minimal, correct changes.
- Use `bash` for commands; keep outputs small (they are truncated).
- Explain briefly, then act. Stop when the task is done.
```

The base is deliberately invariant: cwd, OS, shell, and date belong only to the separately assembled environment
header. This prevents a persisted PromptAgent version from freezing a stale environment in its stable instructions.

Overridable via `--system-prompt` / settings ([configuration.md](configuration.md)). An **append** hook lets
projects add rules without replacing the base.

## Context files

Context-file discovery is cwd-specific and independent of project trust. Konductor first resolves the existing cwd to
an absolute canonical directory. Walking upward from it, the **workspace root** is the nearest directory whose literal
`.git` entry, inspected without following the final link, is a non-symlink regular file or directory. A `.git` symlink
never marks a workspace and cannot redirect root discovery; other entry types do not qualify. Failure to inspect an
entry is an error rather than evidence that it is absent. When no ancestor qualifies, canonical cwd itself is the
root. A nested repository therefore wins over an outer repository, and each non-Git cwd is its own workspace.

Candidates are considered in this stable order:

1. the canonical effective user config directory defined in [configuration.md](configuration.md#config-directory);
   then
2. every directory from the canonical workspace root to the canonical cwd, inclusive.

At each location, inspect the literal `AGENTS.md` directory entry without following its final link. Select it whenever
the entry is definitely present, including when it is a symlink, directory, dangling link, or otherwise invalid file.
Inspect `CLAUDE.md` only when the `AGENTS.md` lookup reports definite absence (`NoSuchFileException` or its platform
equivalent). Permission failures, indeterminate I/O, or an entry that disappears after selection are errors and never
permit fallback. Apply the same presence rule to `CLAUDE.md`. Lookup uses the host filesystem's normal case semantics;
Konductor adds no case folding or directory scan.

After per-location selection, retain the selected entry path, resolve its target canonically, and keep only the first
occurrence of each canonical target under host filesystem path semantics. A selected project target must be a regular
file inside the canonical workspace root; a selected global target must be a regular file inside the canonical config
directory. A symlink is allowed only when its resolved target meets that rule. Dangling, escaping, non-regular,
unreadable, or otherwise invalid selected entries fail the complete context load with an actionable path-specific
error. Missing candidates are not errors. This validation and canonical-target deduplication preserve global-to-cwd
order and prevent aliases from adding the same file twice.

The deduplicated selection is bounded to **32 files, including the global file**, **64 KiB per file**, and **256 KiB
total**. For each selected entry, the implementable read contract is:

1. before opening, canonicalize the selected entry, containment-check that target, and read its regular-file attributes
   without following the target's final link;
2. open the canonical target itself for bounded reading with `NOFOLLOW_LINKS` (not the possibly symlinked selected
   entry); when the platform supplies `SecureDirectoryStream` or an equivalent secure-directory/handle primitive, use
   that stronger primitive for parent traversal, opening, and handle validation;
3. read at most the remaining per-file/aggregate limit plus one byte; and
4. after reading, canonicalize the original selected entry again, repeat containment and no-follow regular-file checks,
   and require the canonical target and all stable attributes available on that filesystem (including file key, size,
   and stable creation/last-modified timestamps, but not access time) to match the pre-read observations. Any observed
   change fails the whole load.

Count the **actual bytes returned by reads** toward both limits. Reported metadata size is only an early-rejection hint;
it never authorizes the read, and truncation is never success. This contract detects changes exposed by canonical paths
or stable attributes and uses a stronger directory-relative primitive wherever the platform provides one. It does not
claim to detect an adversarial, unobservable ABA replacement that restores the same target and observable attributes on
a filesystem without such a primitive; that case is outside the portable fallback guarantee.

Files are decoded as strict UTF-8. A single leading UTF-8 BOM is accepted and removed, and CRLF or lone CR line endings
are normalized to LF before assembly. Any selected-file validation, observed race, read, bound, or decoding failure
aborts all context assembly in every trust state.

`--no-context-files` bypasses this discovery and reading in both TUI and ACP modes. It removes only context-file text:
the built-in/overridden base prompt, environment header, configured append, configuration/trust processing, and tools
remain active. Plain-text context can contain prompt injection and should be reviewed, but it always loads regardless
of trusted, untrusted, or unknown project-config trust.

## Assembly order & precedence

The logical request order is deterministic: **stable base → configured append → context files → environment →
tools**. `systemPromptOverride`, when present in resolved configuration, replaces the built-in stable base;
`systemPromptAppend` does not replace base and immediately follows it as part of the stable definition component.
Tools are structured declarations rather than prompt text, but are the final assembly component.

Text joining is exact and shared by both Prompt transports. First, join the normalized decoded context-file bodies in
discovery order with the literal separator `"\n\n"`. Then join the applicable logical text components in the order
above with that same literal separator. Null and zero-length components are omitted after BOM removal and newline
normalization (so a BOM-only file is empty); whitespace-only components are retained. Joining does not trim
leading/trailing whitespace, add labels, or add an implicit final newline. Thus the algorithms are equivalent to:

```text
contextText = joinNonEmpty(normalizedContextFileBodies, "\n\n")
stableDefinition = joinNonEmpty([base, append], "\n\n")
ephemeralInstructions = joinNonEmpty([stableDefinition, contextText, environment], "\n\n")
persistedDynamicPreamble = joinNonEmpty([contextText, environment], "\n\n")
```

For an ephemeral Prompt request, `ephemeralInstructions` is sent as `instructions` and tools are sent in the request
tool field. `--no-context-files` supplies no context-file bodies. This places configured stable instructions before
cwd-dynamic repository and environment text, while keeping cwd/date information after repository instructions.

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

## Persisted agents: stable vs dynamic preamble

When the loop is bound to an opt-in **persisted PromptAgent**
([providers.md](providers.md#persisted-prompt-agents-promptagent),
[M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in)), the agent version freezes its
`instructions` and tool declarations:

- **Baked into the agent (stable):** the built-in base or `systemPromptOverride`, followed by the resolved
  `systemPromptAppend`, plus the tool declarations captured when that version is created.
- **Sent per turn (dynamic):** context files followed by the environment header (cwd/os/shell/date), emitted as one
  leading developer input item. With `--no-context-files`, only context text is absent.

This is both the legacy and new-version contract. Older Konductor-created PromptAgents already baked
base + configured append; newly created versions continue that compatible definition shape. Ephemeral runs apply the
same logical order each turn by sending base, current append, context files, and environment as `instructions`, with
structured tools last. A configured base/append change therefore affects ephemeral requests immediately but affects a
persisted PromptAgent only after the user explicitly creates a new version.

The pinned Azure Agents 2.2.0 agent-scoped Responses client is built by name and offers no exact-version parameter.
Inspecting a latest version's metadata or definition and then invoking the name-scoped endpoint cannot prove that the
same version will serve the request if versions change between those operations. Konductor therefore does not write,
inspect, or classify prompt-layout metadata, compare frozen instructions, or issue layout mismatch warnings. It binds
persisted agents by name and always sends only context + environment dynamically, preserving exactly one baked append
for legacy and newly created versions.

## Related docs

[architecture.md](architecture.md) · [providers.md](providers.md) · [tools.md](tools.md) ·
[compaction.md](compaction.md) · [configuration.md](configuration.md)
