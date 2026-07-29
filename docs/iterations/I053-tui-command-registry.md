---
id: I053
title: Canonical TUI command registry
issue: https://github.com/jpalvarezl/Konductor/issues/53
created: 2026-07-29
---

# I053 — Canonical TUI Command Registry

## Outcome

Every supported top-level TUI slash command has one canonical, enumerable descriptor and is dispatched through one
registry while `ConversationController` remains the sole owner of immediate/background execution and coroutine
launching.

## Scope

- Parse only the leading slash-command token while preserving the original input and raw argument remainder.
- Add stable command descriptors, a command trait, typed execution actions, and deterministic case-insensitive lookup.
- Register `/quit` (`/exit`), `/new`, `/name`, `/session`, `/resume`, `/compact`, `/model`, `/connections`, and
  `/agent` exactly once.
- Preserve current command-specific argument grammars, provider gates, unknown-slash fallthrough, and blocking/async
  behavior.
- Localize command descriptions and update the owning TUI specification.

## Non-goals

- Fuzzy ranking, command overlays, completion, or keybindings (#83).
- Provider availability or disabled-reason contracts and dynamic option providers (#83).
- New cancellation, commit, or reporting semantics (#81).
- A generic argument tokenizer or changes to nested `/agent`, `/resume`, or free-text `/compact` parsing.

## Acceptance

- [ ] Blocking and async submission share registry dispatch and preserve immediate/background/quit/not-handled behavior.
- [ ] Every current top-level slash command has one descriptor and registry entry in deterministic display order.
- [ ] Lookup is case-insensitive; duplicate canonical names or aliases fail at registry construction.
- [ ] Raw input and argument casing/content are preserved for the owning command parser.
- [ ] Unknown slash input falls through to the model, including path-like input such as `/etc/hosts`.
- [ ] `/compact`, `/resume`, `/agent`, provider gates, and current sync/async execution remain unchanged.
- [ ] Command descriptions come from `AppStrings`; the TUI spec describes the registry and inert-input behavior.
- [ ] Focused tests, full tests, package, docs validation, and diff checks pass.

## Context pack

### Specifications

- [`TUI — Event loop & coroutine marshalling`](../spec/tui.md#event-loop--coroutine-marshalling)
- [`TUI — Keybindings`](../spec/tui.md#keybindings)
- [`TUI — Slash-commands`](../spec/tui.md#slash-commands)
- [`TUI — Localized copy`](../spec/tui.md#localized-copy)

### Source entry points

- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — submission, command routing, state applying,
  coroutine ownership, and turn collection.
- `src/main/kotlin/com/konductor/conversation/PromptAgentCommand.kt` — nested `/agent` grammar and provider management.
- `src/main/kotlin/com/konductor/conversation/FoundryDiscoveryCommand.kt` — `/model` and `/connections` background work.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — async controller composition and active-job ownership.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` — localized frontend copy.

### Tests

- `src/test/kotlin/com/konductor/conversation/ConversationControllerTest.kt`
- `src/test/kotlin/com/konductor/conversation/SessionCommandsTest.kt`
- `src/test/kotlin/com/konductor/conversation/PromptAgentCommandTest.kt`
- `src/test/kotlin/com/konductor/conversation/FoundryDiscoveryCommandTest.kt`
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt`

### Targeted searches

```bash
rg -n '"/(quit|exit|new|name|session|resume|compact|model|connections|agent)|handleCommand|recognizes|isAgentCommand' \
  src/main/kotlin src/test/kotlin
```

## Decisions and constraints

- `CommandInvocation` does not tokenize arguments. Each command trims/parses its raw remainder according to its
  existing grammar.
- `/agent` remains immediate in this foundation to preserve current submission behavior; #81 owns any richer
  cancellation or background-commit semantics.
- Background actions are run with `runBlocking` by blocking submission and launched as the existing cancelable
  `Submission.Turn` by async submission.
- No availability, completion, or dynamic-options surface is introduced before #83.

## Validation

```bash
./mvnw -q -Dtest=CommandRegistryTest,ConversationControllerTest,SessionCommandsTest,PromptAgentCommandTest,FoundryDiscoveryCommandTest,AppStringsTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

## Completion

[PR #84](https://github.com/jpalvarezl/Konductor/pull/84) delivers the canonical registry and controller-owned action
execution while preserving existing command grammars and behavior. Fuzzy discovery remains in #83; richer cancellation
and commit semantics remain in #81.
