# DRAFT: Memory Stores (Azure AI Agents)

> Status: **DRAFT** — proposal/spec sketch for review. Not yet implemented.

## Purpose

Add support for **Azure AI Agents Memory Stores** (`azure-ai-agents` `BetaMemoryStoresClient`) to persist and retrieve
Konductor-specific “agent memory” across runs. The primary initial use case is **persisting user preferences** (e.g.
workflow/tooling preferences for a coding agent harness) while remaining compatible with autonomous/ACP execution.

This spec intentionally focuses on:
- **Identity + scoping** (who does the memory belong to?)
- **Read/write lifecycle** (when Konductor should retrieve and apply memory)
- **Store/load strategy** (when stores are created and when memory is consulted)
- **Minimal integration points** (how it fits the current M1 architecture)

It does **not** attempt to implement full “long-term memory” reasoning, embeddings, or automatic summarization.

## Non-goals

- Building an authentication system or user account database inside Konductor.
- Attempting to infer a user identity from OS user, Git config, etc. (too error-prone for shared machines/CI).
- Implementing complex “memory compaction”, ranking, safety filters, or policy engines.
- Hard-coupling memory to a specific UI; both TUI and ACP frontends must be supported.

## Background: Azure Memory Stores concepts

Azure AI Agents provides an API surface for Memory Stores:

- **Memory Store**: a container resource (think: a database).
- **Memory Item**: a stored record (e.g., user profile info, procedural instructions, summaries).
- **Scope**: a string namespace that partitions memories within a store.

Memory Stores are accessed via `com.azure.ai.agents.BetaMemoryStoresClient` / `BetaMemoryStoresAsyncClient`.

> Note: this spec treats Memory Stores as a backend persistence mechanism. Konductor still controls what it stores and
> how it injects retrieved memory into the model prompt.

## Requirements

### R1 — Explicit identity

Konductor must support a stable identity primitive for memory scoping.

- Identity MUST be supplied explicitly by the operator (env var / config file / CLI flag).
- If no identity is provided, Konductor MUST be able to run without memory.

### R2 — Scope resolution with predictable granularity

Konductor must provide a deterministic algorithm to derive `scope` used in Memory Store operations.

The algorithm MUST support:
- **User-global memory**: preferences that follow a user across projects.
- **User+repo memory**: preferences that are specific to a repository.
- **Autonomous/bot memory**: non-human identity, used in ACP or automation.
- **Explicit override**: operator can pin a scope string directly.

### R3 — Read-before-write lifecycle

When memory is enabled, Konductor MUST:
- Retrieve relevant memory at startup (or session start) and inject it into the agent’s system context.
- Allow writes only when configured (read-only mode supported).

### R4 — Frontend parity

Memory behavior MUST be consistent across:
- TUI mode
- ACP (headless) mode

ACP callers MUST be able to set identity/scope through configuration so autonomous scripts can control memory
association.

## Data model

### Identity

A minimal identity object:

```kotlin
/** Minimal operator-supplied identifier used for memory scoping. */
data class UserIdentity(
    val id: String,
)
```

> This does not imply authentication; it is a stable string key.

### Scope formats

Scopes are opaque strings to the Memory Stores service, but Konductor standardizes formats.

Recommended formats:

- **User-global**: `user:{userId}`
- **User+repo**: `user:{userId}:repo:{repoKey}`
- **Bot/autonomous**: `agent:{agentId}`
- **Explicit override**: exact string provided by operator

`repoKey` should be stable and deterministic:
- Preferred: Git remote URL normalized (e.g., `github.com/org/repo`)
- Fallback: absolute path hash

> Repo detection is best-effort; failure to determine `repoKey` must not prevent running.

### Memory kinds

Konductor will map its stored items to Azure `MemoryItemKind`:

- `USER_PROFILE`: long-lived user preferences and personal defaults.
- `PROCEDURAL`: stable operational procedures/instructions (team conventions, repo norms).
- `CHAT_SUMMARY`: optional; only if/when Konductor introduces automated session summarization.

Initially, Konductor focuses on `USER_PROFILE` and `PROCEDURAL`.

## Configuration

### Required

- `KONDUCTOR_MEMORY_STORE_ID`: ID of the Azure memory store resource to use.

### Identity and scoping

- `KONDUCTOR_USER_ID`: stable user identifier (e.g., `alice`, `jose`, `build-bot`).
- `KONDUCTOR_AGENT_ID`: stable agent identifier for autonomous runs (e.g., `ci-agent`, `nightly-bot`).
- `KONDUCTOR_MEMORY_SCOPE`: explicit scope override.
- `KONDUCTOR_MEMORY_SCOPE_MODE`: one of:
  - `USER` (default when `KONDUCTOR_USER_ID` is set)
  - `USER_REPO`
  - `AGENT`

### Read/write controls

- `KONDUCTOR_MEMORY_ENABLED`: boolean, default `false`.
- `KONDUCTOR_MEMORY_READONLY`: boolean, default `true`.

### Store lifecycle

- `KONDUCTOR_MEMORY_CREATE_IF_MISSING`: boolean, default `false`.
- `KONDUCTOR_MEMORY_STORE_NAME`: string; used only when creating.

> Defaulting to disabled + read-only + no-create is conservative.

## Store lifecycle (creation, selection)

Konductor must define when a Memory Store is created vs. reused.

### Store selection

Konductor SHOULD treat the **Memory Store** as an operator-owned resource and prefer reusing an existing store.

- In most deployments, `KONDUCTOR_MEMORY_STORE_ID` is configured and stable.
- Konductor MUST NOT create a new store implicitly unless explicitly configured to do so.

### Store creation

If explicit creation is enabled, Konductor MAY create a Memory Store at startup when the configured store does not
exist.

Rationale:
- Store creation is a provisioning operation and may have cost/permission implications.

## Scope resolution algorithm (normative)

Inputs:
- `memoryEnabled`
- `memoryScopeOverride`
- `scopeMode`
- `userId`
- `agentId`
- `repoKey` (optional)

Algorithm:

1. If `memoryEnabled` is `false`: memory is not used.
2. If `memoryScopeOverride` is set: use it.
3. If `scopeMode == USER_REPO` and `userId` is set and `repoKey` is available: `user:{userId}:repo:{repoKey}`.
4. If `scopeMode == USER` and `userId` is set: `user:{userId}`.
5. If `scopeMode == AGENT` and `agentId` is set: `agent:{agentId}`.
6. Otherwise: memory is disabled for this run (do not error).

Rationale:
- Explicit override always wins.
- A missing identity should not block running.

## Memory load strategy (when to consult memory)

Konductor must define a deterministic, low-surprise strategy for when it reads memory and how much it injects.

### Recommended default strategy

When memory is enabled:

1. **Session start preload (small)**
   - At app startup (TUI) or session start (ACP session), resolve scope and load a small set of memories intended for
     “always-on” guidance (preferences/procedures).
   - Inject a bounded appendix into the system prompt.

2. **On-demand retrieval (optional)**
   - Allow explicit commands or tools to query memory for a topic.

3. **Write-back (optional, gated)**
   - Only update memories when writes are enabled (not read-only).

### Preload content budget

Konductor SHOULD enforce a size/quantity budget for preloaded memory, for example:
- `KONDUCTOR_MEMORY_PRELOAD_MAX_ITEMS` (default 10)
- `KONDUCTOR_MEMORY_PRELOAD_MAX_CHARS` (default 4_000)

If the budget is exceeded, Konductor should prefer:
- `USER_PROFILE` and `PROCEDURAL` over `CHAT_SUMMARY`
- most recently updated items

## Integration points (current codebase)

Konductor currently builds system context via `AgentContextFactory` (see `AGENTS.md`).

### Memory read path

At startup/session initialization:

1. Build `MemoryScope` via the scope resolution algorithm.
2. Query Memory Store for items relevant to preferences/procedures.
3. Format retrieved content into a system-prompt appendix.
4. Inject the appendix into the system prompt produced by `AgentContextFactory`.

### Memory write path

Konductor may expose commands to update stored preferences:

- TUI: `/pref set <key> <value>`
- ACP: future: a tool / command message to set preferences

When writes are enabled (`!readOnly`):
- Create or update a `MemoryItem` in the resolved `scope`.

## Prompt injection format

Memory injection must both (a) provide useful guidance and (b) inform the model what memory capabilities exist.

### Memory Availability snippet (required)

Konductor MUST inject a short **Memory Availability** snippet into the system prompt when memory is enabled.

Example:

```
Memory stores: enabled
Scope: user:alice:repo:github.com/org/repo
Available memory kinds: USER_PROFILE, PROCEDURAL
How to use: treat these as persistent preferences/procedures. Do not invent memories not listed here.
```

This snippet is intentionally small and is included even if no memory items are currently present.

### Memory Content appendix (bounded)

If preload returns items, Konductor SHOULD inject them in a clearly delimited block, e.g.:

```
Persistent preferences (from memory store):
- Do not commit automatically; leave changes unstaged unless asked.
- Prefer concise answers.

Procedures (from memory store):
- Run mvn test before finalizing.
```

### Priority and behavior

Memory content MUST be:
- Clearly marked as “persistent preferences” / “procedures”.
- Treated as higher priority than general model behavior but lower priority than explicit user instructions in the
  current conversation.

Konductor SHOULD:
- Include a short header describing that the content is retrieved from persistent memory.
- Avoid injecting large volumes of memory by default.

## Safety and privacy considerations

- Memory stores may contain sensitive user data. Konductor MUST require explicit enablement.
- Operators should be able to run in read-only mode.
- Konductor SHOULD provide a command to clear/delete memory scope.

## Open questions

- Should Konductor maintain one memory item per preference key, or a single consolidated “preferences” item?
- How should repoKey normalization be specified (Git remote parsing rules)?
- Should ACP requests be able to supply scope/identity per-session instead of via env/config?
- Should memory be used for tool-policy enforcement (e.g., never run destructive commands) or only as soft guidance?
- What is the ideal “Memory Availability snippet” format for maximum model compliance?

## Implementation sketch (non-normative)

- Add `MemoryStoreClientFactory` that creates `BetaMemoryStoresClient` using the same credential stack.
- Add `MemoryService` with:
  - `fun preload(scope): MemoryAppendix`
  - `fun upsertPreference(scope, key, value)`
  - `fun search(scope, query): List<MemoryItem>`
- Update `AgentContextFactory` to accept a `MemoryAppendix` string.
