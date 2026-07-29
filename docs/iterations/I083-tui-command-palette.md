---
id: I083
title: TUI command palette and model option picker
issue: https://github.com/jpalvarezl/Konductor/issues/83
created: 2026-07-29
---

# I083 — TUI Command Palette and Model Option Picker

## Outcome

The idle TUI exposes the canonical command registry through a deterministic fuzzy palette and can stage a deployed
Foundry model name through one reusable, SDK-free suspend option-provider contract without dispatching from the overlay.

## Scope

- Open the palette from `/` in an empty composer or `Ctrl+K`, then bounded-fuzzy-filter canonical descriptors.
- Preserve the consumed slash plus typed/pasted query as the cancellation draft for literal absolute-path input.
- Render localized descriptions, stable usage, and enabled or disabled-with-reason availability.
- Navigate with arrows, stage command text with Enter, and close without dispatch through Esc.
- Add one separate suspend option-provider contract and use it for SDK-free Foundry model deployment selection.
- Keep model loading, empty, and error states inside the overlay without transcript output.
- Preserve inert input while a model turn or background command is active.

## Non-goals

- Changes to command parsing, dispatch, execution-time provider gates, or local-command cancellation semantics (#81).
- Dynamic session resume or PromptAgent option providers.
- Startup model bootstrap (#79), mouse input, path completion, or arbitrary command argument parsing.

## Acceptance

- [x] `/` opens only from an empty composer; `Ctrl+K` opens while idle; neither opens during active work.
- [x] Registry order, availability, bounded fuzzy ranking, selection, insertion, and cancellation are deterministic.
- [x] Slash-origin drafts, production option-load generations, and zero-visible-row Enter behavior are tested.
- [x] `/model` asynchronously presents only model deployments and stages `/model <deployment>` for confirmation.
- [x] Loading, empty, and sanitized error states are explicit and never append transcript messages.
- [x] Palette state stays in frontend state/input models and rendering remains a stateless TUI component.
- [x] Focused tests, full tests, package, documentation validation, and diff checks pass.

## Context pack

### Specifications

- [`TUI — Layout`](../spec/tui.md#layout)
- [`TUI — Keybindings`](../spec/tui.md#keybindings)
- [`TUI — Slash-commands`](../spec/tui.md#slash-commands)
- [`TUI — Localized copy`](../spec/tui.md#localized-copy)

### Source entry points

- `src/main/kotlin/com/konductor/conversation/CommandRegistry.kt` — canonical descriptor and availability catalog.
- `src/main/kotlin/com/konductor/conversation/FoundryDiscoveryCommand.kt` — authoritative `/model` execution gates.
- `src/main/kotlin/com/konductor/foundry/project/deployment/FoundryDeploymentCatalog.kt` — SDK-free catalog DTO seam.
- `src/main/kotlin/com/konductor/core/{AppState,InputState}.kt` — render-facing frontend state and composer editing.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — key adaptation, coroutine ownership, and rendering composition.
- `src/main/kotlin/com/konductor/tui/component/` and `tui/layout/` — stateless component and bounded layout patterns.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` — semantic localized copy.

### Tests

- `src/test/kotlin/com/konductor/conversation/CommandRegistryTest.kt`
- `src/test/kotlin/com/konductor/conversation/FoundryDiscoveryCommandTest.kt`
- `src/test/kotlin/com/konductor/core/InputStateTest.kt`
- `src/test/kotlin/com/konductor/tui/`
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt`

### Targeted searches

```bash
rg -n "CommandRegistry|FoundryDeploymentCatalog|AppState|InputState|handleKey|TuiComponent|Rectangle|AppStrings" \
  src/main/kotlin src/test/kotlin
```

## Decisions and constraints

- Palette selection replaces any existing composer draft with staged command text; Esc preserves the draft, and only
  the unchanged submission path can execute a selection.
- Availability is optional and descriptive. Existing execution-time provider checks remain authoritative.
- Option discovery is separate from `TuiCommand`; the TUI coordinator owns its coroutine and stale results cannot
  reopen a closed or replaced palette, including when a provider completes non-cooperatively after cancellation.
- Fuzzy work is bounded before normalization and across terms, candidates, and returned results; source-order ties
  remain stable.
- `/model` options are deployment names from application DTOs. Enter stages the exact name after `/model `.
- Dynamic `/resume` and `/agent use` pickers remain follow-up work after this contract is proven.

## Validation

```bash
./mvnw -q -Dtest=CommandRegistryTest,CommandOptionsTest,ConversationControllerTest,FuzzyMatcherTest,\
CommandPaletteControllerTest,CommandPaletteCoordinatorTest,CommandPaletteLayoutTest,CommandPaletteViewTest,\
TuiAppKeyTest,AppStringsTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

## Completion

[PR #91](https://github.com/jpalvarezl/Konductor/pull/91) delivers the registry-backed palette, descriptive availability,
and the SDK-free Foundry model picker while preserving insertion-for-confirmation and existing command dispatch.
Dynamic `/resume` and `/agent use` options remain tracked by
[issue #90](https://github.com/jpalvarezl/Konductor/issues/90); richer cancellation remains in issue #81.
