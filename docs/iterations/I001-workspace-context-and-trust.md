---
id: I001
title: Workspace context and project trust
issue: https://github.com/jpalvarezl/Konductor/issues/26
created: 2026-07-10
---

# I001 — Workspace Context and Project Trust

Load workspace instructions deterministically while preventing untrusted project configuration from silently changing
runtime behavior.

Issue [#26](https://github.com/jpalvarezl/Konductor/issues/26) owns assignment, discussion, dependencies, blockers, and
closure. This packet owns the implementation contract. The choices under **Open design decisions** must be resolved
here before the issue returns to `status:ready`; after that, the packet must remain sufficient without GitHub access.

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

- [`Context files`](../spec/agent-context.md#context-files),
  [`Assembly order & precedence`](../spec/agent-context.md#assembly-order--precedence), and
  [`Persisted agents: stable vs dynamic preamble`](../spec/agent-context.md#persisted-agents-stable-vs-dynamic-preamble).
- [`Settings file`](../spec/configuration.md#settings-file) and
  [`Precedence`](../spec/configuration.md#precedence).
- [`How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor) and
  [`Supported ACP methods`](../spec/acp.md#supported-acp-methods).
- [`Request shape`](../spec/providers.md#request-shape) and
  [`Persisted Prompt agents`](../spec/providers.md#persisted-prompt-agents-promptagent).

### Source entry points

- `src/main/kotlin/com/konductor/agent/AgentContextFactory.kt` — `AgentContextFactory.build`, current
  base/environment/append assembly.
- `src/main/kotlin/com/konductor/config/Configuration.kt` — `Configuration.load`, which currently reads global and
  project settings eagerly.
- `src/main/kotlin/com/konductor/Cli.kt` — `CliOptions`, `parseCliArgs`, and help text for the
  `--no-context-files` control.
- `src/main/kotlin/com/konductor/Main.kt` — `runKonductor`, CLI/config resolution and TUI object-graph construction.
- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` — `ConfigurationAcpSessionRuntimeFactory.create`,
  per-session cwd/provider/context ownership boundary.
- `src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt` — `buildParams`, ephemeral
  Prompt system instructions and transcript construction.
- `src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt` —
  `buildParams`/`buildInput`, persisted PromptAgent dynamic-preamble injection.

### Tests

- `src/test/kotlin/com/konductor/config/ConfigurationTest.kt` — settings precedence and malformed input.
- `src/test/kotlin/com/konductor/CliTest.kt` — CLI parsing, conflicts, and help coverage.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — cwd-bound ACP runtime behavior.
- Add focused context-discovery, trust-store, and ephemeral/persisted Prompt request-assembly tests rather than
  expanding unrelated provider tests.

### Targeted searches

```bash
rg -n "AgentContextFactory|dynamicPreamble|systemPromptOverride|systemPromptAppend" src/main/kotlin src/test/kotlin
rg -n "settings.json|KONDUCTOR_CONFIG_DIR|Configuration.load" src/main/kotlin src/test/kotlin
rg -n "parseCliArgs|KonductorCli|no-context-files" src/main/kotlin/com/konductor src/test/kotlin/com/konductor
rg -n "ConfigurationAcpSessionRuntimeFactory|session.cwd" src/main/kotlin/com/konductor/acp src/test/kotlin/com/konductor/acp
rg -n "buildParams|buildInput|dynamicPreamble|systemPrompt" src/main/kotlin/com/konductor/provider/inference src/test/kotlin/com/konductor/provider/inference
```

## Open design decisions

Resolve these in the owning specs and this packet before implementation is claimed:

- What defines the repository root, including nested repositories and a cwd outside Git?
- At each directory, does `AGENTS.md` always suppress `CLAUDE.md`, and how are duplicates avoided across the
  root-to-cwd walk?
- What byte/file-count bounds, decoding rules, symlink policy, and read-error behavior apply to instruction files?
- What normalized workspace identity is persisted in the trust store, and how are moved or symlinked workspaces
  treated?
- For an ACP workspace with unknown trust, which project-controlled inputs are ignored and which plain-text
  instructions, if any, are still loaded?

## Decisions and constraints

- `src/` and tests remain implementation truth; specs describe the intended contract.
- Plain-text instructions are prompt input, not executable resources. Their prompt-injection risk must be documented,
  but they do not require the same policy as project-controlled settings or future executable extensions.
- Project trust gates project-owned configuration; it must not gate global operator configuration.
- Trust persistence belongs under the user-controlled Konductor config directory, never inside the repository.
- ACP cannot depend on an interactive terminal prompt. Unknown-trust behavior must be deterministic and safe.
- `ConfigurationAcpSessionRuntimeFactory` is the correct integration boundary for session-specific context.

## Validation

Run focused context/config/ACP tests during implementation, then the full suite:

```bash
./mvnw -q test
```

Manually verify one trusted and one untrusted repository in the TUI, plus an ACP session whose cwd differs from the
launch directory.

## Documentation context

- Owning specs: `agent-context.md`, `configuration.md`, `acp.md`, and possibly `providers.md`.
- User/developer docs: `README.md`, `docs/development.md`, CLI help, and `AGENTS.md` if repository guidance changes.
- Service feedback: add an entry only if Azure SDK/service behavior affects the implementation.

## Completion

The final implementing PR closes issue #26 and records the resulting trust semantics here. Move excluded follow-ups to
[`future.md`](../future.md) or focused GitHub issues rather than extending this delivery after implementation starts.
