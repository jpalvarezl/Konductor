---
id: I090
title: Dynamic resume and PromptAgent command palette options
issue: https://github.com/jpalvarezl/Konductor/issues/90
created: 2026-07-30
---

# I090 — Dynamic Resume and PromptAgent Command Palette Options

## Outcome

The idle TUI can discover application-owned recent sessions and managed PromptAgents through the existing suspend
option-provider boundary, then stage an exact `/resume <full UUID>` or `/agent use <exact name>` command for user
confirmation without executing a command or mutating application state.

## Scope

- Add a `/resume` dynamic picker ordered by most-recent session first, with localized session details and full UUID
  insertion identity.
- Enable `/agent` options only for `ProviderManagement.PromptAgents`, list PromptAgent choices, and stage the minimal
  nested form `/agent use <exact name>`.
- Keep Hosted and unmanaged `/agent` entries disabled with their existing provider-availability reason.
- Compose option-source keys and ordinary insertion prefixes from canonical command descriptors, with an explicit
  insertion-prefix override only where a selected option stages the nested `/agent use ` command form.
- Localize loading, empty, sanitized error, and option-detail presentation for both option sources.
- Preserve generation checks so closed or superseded palettes ignore stale provider results.
- Add focused provider, controller/coordinator, and localization coverage and update the owning TUI specification.

## Non-goals

- Executing selections directly, changing command parsing/dispatch, or mutating sessions/providers from an overlay.
- Refreshing an already-open option catalog, background polling, pagination, session deletion, or new agent-management
  semantics.
- Adding PromptAgent choices for Hosted or unmanaged providers.

## Acceptance

- [x] Selecting `/resume` loads sessions through a suspend application-owned provider in deterministic most-recent-first
  order and stages `/resume <full UUID>` without execution or mutation.
- [x] Enabled `/agent` loads PromptAgent names and stages `/agent use <exact name>`; Hosted and unmanaged providers keep
  the disabled reason and cannot open options.
- [x] Option loading is snapshot-on-open: reopening starts a fresh generation and refreshes the catalog; an open picker
  is not refreshed in place.
- [x] Loading, empty, sanitized error, and session/agent details are localized, and stale non-cooperative completions
  cannot replace, reopen, or mutate a newer/closed palette.
- [x] Focused provider/controller/localization and production-composition tests, the full test suite as feasible, docs
  validation, and diff checks pass.

## Context pack

### Specifications

- [`TUI — Layout`](../spec/tui.md#layout)
- [`TUI — Keybindings`](../spec/tui.md#keybindings)
- [`TUI — Slash-commands`](../spec/tui.md#slash-commands)
- [`TUI — Localized copy`](../spec/tui.md#localized-copy)

### Source entry points

- `src/main/kotlin/com/konductor/conversation/CommandRegistry.kt` — command availability and option-source descriptors.
- `src/main/kotlin/com/konductor/conversation/CommandOptions.kt` — suspend application-owned option providers.
- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — provider/session application boundary.
- `src/main/kotlin/com/konductor/provider/ProviderRuntime.kt` — PromptAgent capability variants.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` and `session/SessionStore.kt` — persisted session listing and
  metadata identity.
- `src/main/kotlin/com/konductor/tui/palette/CommandPaletteController.kt` — option loading state, insertion, and
  generations.
- `src/main/kotlin/com/konductor/tui/palette/CommandPaletteCoordinator.kt` — application coroutine ownership and
  provider calls.
- `src/main/kotlin/com/konductor/tui/component/CommandPaletteView.kt` — localized option-state/details rendering.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and
  `src/main/resources/com/konductor/i18n/messages*.properties` — semantic copy.

### Tests

- `src/test/kotlin/com/konductor/conversation/CommandOptionsTest.kt`
- `src/test/kotlin/com/konductor/conversation/CommandRegistryTest.kt`
- `src/test/kotlin/com/konductor/conversation/ConversationControllerTest.kt`
- `src/test/kotlin/com/konductor/tui/PaletteOptionSourcesTest.kt`
- `src/test/kotlin/com/konductor/tui/palette/CommandPaletteControllerTest.kt`
- `src/test/kotlin/com/konductor/tui/palette/CommandPaletteCoordinatorTest.kt`
- `src/test/kotlin/com/konductor/tui/component/CommandPaletteViewTest.kt`
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt`

### Targeted searches

```bash
rg -n "CommandOption|optionSource|ProviderManagement|PromptAgents|listSessions|SessionMetadata|palette" \
  src/main/kotlin/com/konductor/{conversation,core,session,tui,i18n} \
  src/test/kotlin/com/konductor/{conversation,tui,i18n}
```

## Decisions and constraints

- Dynamic catalogs are snapshot-on-open. Every open invokes the provider and receives a new generation; reopening is
  the explicit refresh action. This avoids moving selection while preserving fresh application state.
- Palette option-source lookup identity and the `/model` and `/resume` insertion prefixes derive from the canonical
  command descriptors. `/agent` uses one option level: choosing the command opens PromptAgent choices directly and
  the sole explicit prefix override supplies the nested `use` token. No intermediate `use` picker is introduced.
- Display labels/details are presentation only. Session insertion uses the full persisted UUID and agent insertion uses
  the exact provider name; display truncation or localized metadata never changes identity.
- The option-provider DTO remains free of Lanterna and Azure SDK types. Providers may read application state but must
  not mutate it, and selection only replaces the composer draft.
- Existing command execution gates remain authoritative even though registry availability controls whether a picker
  can open.

## Validation

```bash
./mvnw -q -Dtest=CommandOptionsTest,CommandRegistryTest,ConversationControllerTest,PaletteOptionSourcesTest,\
CommandPaletteControllerTest,CommandPaletteCoordinatorTest,CommandPaletteViewTest,AppStringsTest test
./mvnw -q test
node scripts/validate-docs.mjs
git diff --check
```
