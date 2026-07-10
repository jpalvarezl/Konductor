---
id: I002
title: Localized string assets
status: active
created: 2026-07-10
updated: 2026-07-10
issues: []
pull_requests:
  - 25
depends_on:
  - Foundations cycle
---

# I002 — Localized String Assets

Move frontend-owned copy behind a locale-aware resource catalog without changing stable commands, protocols, prompts,
or persisted data.

## Outcome

The interactive frontend loads user-facing copy from a JVM resource bundle selected by locale. English behavior remains
the default, additional locale bundles can be added without changing call sites, and presentation text no longer lives
in core domain types.

## Scope

- Add a `ResourceBundle`-backed Kotlin string-catalog facade and English base bundle.
- Resolve a BCP-47 locale from `KONDUCTOR_LOCALE`, falling back to the operating-system display locale.
- Inject localized copy into the TUI, conversation commands, prompt-agent commands, and CLI presentation.
- Add bundle loading, fallback, formatting, and key-coverage tests.
- Document which strings are localizable and which identifiers/content remain stable.

## Non-goals

- Translating the agent system prompt, tool schemas/results, raw provider errors, hosted logs, or ACP protocol fields.
- Localizing CLI flags, slash-command names, tool names, JSON keys, or persisted session values.
- Shipping a production translation in this iteration.
- Full right-to-left terminal layout or Unicode display-cell-width correction.
- ICU plural/select formatting; the catalog facade keeps that migration possible when a real locale requires it.

## Acceptance

- [x] The TUI and local command responses obtain user-facing copy from the English base bundle.
- [x] `KONDUCTOR_LOCALE` accepts BCP-47 tags and resource fallback works without Foundry configuration.
- [x] Core `MessageRole` contains no localized display label.
- [x] Stable commands, protocol identifiers, prompts, schemas, persisted data, logs, and raw tool results are unchanged.
- [x] Tests cover locale resolution, fallback, formatting, and complete production-bundle keys.
- [x] The localization boundary and configuration are documented in the owning specs.

## Context pack

Read these first and do not scan the whole repository unless they prove incomplete or incorrect.

### Specifications

- `docs/spec/architecture.md` — frontend/core boundary.
- `docs/spec/tui.md` — interactive presentation and local commands.
- `docs/spec/configuration.md` — environment and precedence.
- `docs/spec/acp.md` — protocol-owned headless output that remains stable in this iteration.

### Source entry points

- `src/main/kotlin/com/konductor/Main.kt` — bootstrap ordering and object graph.
- `src/main/kotlin/com/konductor/tui/TuiApp.kt` — interactive frontend composition.
- `src/main/kotlin/com/konductor/tui/component/` — render-time labels and hints.
- `src/main/kotlin/com/konductor/conversation/` — local command responses and transcript reconstruction.
- `src/main/kotlin/com/konductor/conversation/ToolRendering.kt` — presentation-only tool summaries.
- `src/main/kotlin/com/konductor/core/Message.kt` — role identity versus display label.
- `src/main/kotlin/com/konductor/Cli.kt` — help and parser errors.

### Tests

- `src/test/kotlin/com/konductor/CliTest.kt`
- `src/test/kotlin/com/konductor/conversation/ConversationControllerTest.kt`
- `src/test/kotlin/com/konductor/conversation/PromptAgentCommandTest.kt`
- Add focused catalog tests under `src/test/kotlin/com/konductor/i18n/`.

### Targeted searches

```bash
rg -n 'ChatMessage|addSystem|System\.err|KonductorCli\.help' src/main/kotlin src/test/kotlin
rg -n '"[^"]*[A-Za-z][^"]*"' src/main/kotlin/com/konductor/tui src/main/kotlin/com/konductor/conversation
```

## Decisions and constraints

- `ResourceBundle` is the storage/fallback mechanism; application code calls semantic `AppStrings` methods rather than
  raw keys.
- Catalog resolution happens before Foundry configuration so help and interactive startup do not require Azure access.
- Locale is frontend bootstrap state, not provider or session state.
- Low-level components retain stable diagnostic text; frontends localize their own labels and explanatory wrappers.
- ACP remains protocol-stable until a client-locale negotiation mechanism is specified.
- Commands and identifiers remain English/stable so scripts, documentation, persisted sessions, and model tool calls do
  not change with locale.

## Burndown

- [x] Add catalog infrastructure, English resources, locale resolution, and focused tests.
- [x] Inject localized copy through TUI components and conversation/prompt-agent commands.
- [x] Localize CLI help and presentation wrappers without changing option names.
- [x] Remove display labels from core domain types.
- [x] Add bundle completeness and packaged-resource coverage.
- [x] Update owning specs and user/developer guidance.

## Validation

```bash
./mvnw.cmd -q -Dtest=AppStringsTest,CliTest,ConversationControllerTest,PromptAgentCommandTest test
./mvnw.cmd -q test
./mvnw.cmd -q package
```

Verify the shaded jar contains `com/konductor/i18n/messages.properties`.

## Documentation impact

- Owning specs: `architecture.md`, `tui.md`, `configuration.md`, and an ACP non-goal note if needed.
- User/developer docs: CLI help and `docs/development.md`.
- Service feedback: none expected; this is JVM/application infrastructure.

## Completion

Implementation and offline validation are complete in [PR #25](https://github.com/jpalvarezl/Konductor/pull/25); the
iteration remains active until the pull request is merged. Production ships the English root bundle and a stable facade
for future translations. Unicode display-cell width, RTL layout, ICU plural/select formatting, and ACP locale
negotiation remain outside this iteration's scope.
