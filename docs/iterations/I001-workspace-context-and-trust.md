---
id: I001
title: Workspace context and project trust
status: ready
created: 2026-07-10
updated: 2026-07-10
issues: []
pull_requests: []
depends_on:
  - Foundations cycle
---

# I001 — Workspace Context and Project Trust

Load workspace instructions deterministically while preventing untrusted project configuration from silently changing
runtime behavior.

## Outcome

Prompt and ACP sessions receive cwd-correct `AGENTS.md` or fallback `CLAUDE.md` instructions. Plain-text instruction
loading and project-resource trust have explicit, separate policies, and headless execution never blocks on an
interactive trust prompt.

## Scope

- Discover global, repository-ancestor, and cwd instruction files in deterministic order.
- Define fallback behavior when both `AGENTS.md` and `CLAUDE.md` are present.
- Add a `--no-context-files` escape hatch.
- Preserve the persisted PromptAgent split between stable baked instructions and the cwd-specific dynamic preamble.
- Persist project trust decisions outside the project and gate project `.konductor/settings.json`.
- Define deterministic ACP behavior for trusted, untrusted, and unknown workspaces.
- Apply identical context/trust rules when an ACP session is created or loaded from its persisted cwd.

## Non-goals

- Executable extensions, packages, project-local skills, or custom tools.
- Azure Memory Stores or other cross-session memory.
- ACP tool permission prompts.
- A rich trust-management UI beyond the minimum interaction needed by the TUI.
- Runtime creation of new Prompt or Hosted agent definitions.

## Acceptance

- [ ] Global, repository-root-to-cwd, and cwd instructions are assembled in a deterministic tested order.
- [ ] `AGENTS.md`/`CLAUDE.md` fallback and duplicate-file behavior are explicitly specified and tested.
- [ ] `--no-context-files` disables instruction discovery without disabling the built-in prompt or tools.
- [ ] Ephemeral Prompt and persisted PromptAgent requests receive equivalent workspace instructions through their
  respective prompt paths.
- [ ] Project `.konductor/settings.json` is ignored until the workspace is trusted; global settings remain available.
- [ ] Trust decisions are stored outside the workspace and cannot be changed by editing repository files.
- [ ] ACP create/load uses the session cwd and follows a deterministic noninteractive trust policy without prompting.
- [ ] The owning specs, CLI help, tests, and user/developer documentation describe the resulting behavior.

## Context pack

Read these first and do not scan the whole repository unless they prove incomplete or contradict source behavior.

### Specifications

- [`docs/spec/agent-context.md`](../spec/agent-context.md) — context-file discovery, assembly order, and the persisted
  PromptAgent stable/dynamic split.
- [`docs/spec/configuration.md`](../spec/configuration.md) — global/project settings and precedence.
- [`docs/spec/acp.md`](../spec/acp.md) — cwd-owned ACP session runtime and noninteractive constraints.
- [`docs/spec/providers.md`](../spec/providers.md) — ephemeral versus persisted Prompt request shapes.

### Source entry points

- `src/main/kotlin/com/konductor/agent/AgentContextFactory.kt` — current base/environment/append assembly.
- `src/main/kotlin/com/konductor/config/Configuration.kt` — currently reads global and project settings eagerly.
- `src/main/kotlin/com/konductor/Main.kt` — CLI parsing and TUI object-graph construction.
- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` — per-session cwd/provider/context ownership boundary.
- `src/main/kotlin/com/konductor/provider/inference/ResponsesMapping.kt` — ephemeral and persisted Prompt input mapping.

### Tests

- `src/test/kotlin/com/konductor/config/ConfigurationTest.kt` — settings precedence and malformed input.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — cwd-bound ACP runtime behavior.
- `src/test/kotlin/com/konductor/provider/inference/ResponsesMappingTest.kt` — dynamic preamble request mapping.
- Add focused context-discovery and trust-store tests rather than expanding unrelated provider tests.

### Targeted searches

```bash
rg -n "AgentContextFactory|dynamicPreamble|systemPromptOverride|systemPromptAppend" src/main/kotlin src/test/kotlin
rg -n "settings.json|KONDUCTOR_CONFIG_DIR|Configuration.load" src/main/kotlin src/test/kotlin
rg -n "ConfigurationAcpSessionRuntimeFactory|session.cwd" src/main/kotlin/com/konductor/acp src/test/kotlin/com/konductor/acp
```

## Decisions and constraints

- `src/` and tests remain implementation truth; specs describe the intended contract.
- Plain-text instructions are prompt input, not executable resources. Their prompt-injection risk must be documented,
  but they do not require the same policy as project-controlled settings or future executable extensions.
- Project trust gates project-owned configuration; it must not gate global operator configuration.
- Trust persistence belongs under the user-controlled Konductor config directory, never inside the repository.
- ACP cannot depend on an interactive terminal prompt. Unknown-trust behavior must be deterministic and safe.
- `ConfigurationAcpSessionRuntimeFactory` is the correct integration boundary for session-specific context.

## Burndown

- [ ] Refresh the context/trust contract in `agent-context.md`, `configuration.md`, and `acp.md`.
- [ ] Implement deterministic context-file discovery and bounded prompt assembly.
- [ ] Add CLI/config controls for context loading.
- [ ] Introduce persisted project trust and gate project settings.
- [ ] Integrate trust/context resolution into both TUI startup and ACP create/load.
- [ ] Add focused unit and integration coverage.
- [ ] Update CLI help, README/development guidance, and service feedback if SDK limitations are encountered.

## Validation

Run focused context/config/ACP tests during implementation, then the full suite:

```bash
./mvnw -q test
```

Manually verify one trusted and one untrusted repository in the TUI, plus an ACP session whose cwd differs from the
launch directory.

## Documentation impact

- Owning specs: `agent-context.md`, `configuration.md`, `acp.md`, and possibly `providers.md`.
- User/developer docs: `README.md`, `docs/development.md`, CLI help, and `AGENTS.md` if repository guidance changes.
- Service feedback: add an entry only if Azure SDK/service behavior affects the implementation.

## Completion

Record merged PRs and final trust semantics here. Move excluded follow-ups to [`future.md`](../future.md) or a focused
GitHub issue rather than extending this iteration after implementation starts.
