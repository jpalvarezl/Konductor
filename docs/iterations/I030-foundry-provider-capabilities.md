---
id: I030
title: Foundry provider capabilities and runtime ownership
issue: https://github.com/jpalvarezl/Konductor/issues/30
created: 2026-07-28
---

# I030 — Foundry Provider Capabilities and Runtime Ownership

Issue [#30](https://github.com/jpalvarezl/Konductor/issues/30) owns coordination, status, and closure. Defect
[#29](https://github.com/jpalvarezl/Konductor/issues/29) is the concrete Prompt-only feature leak closed by the same
final pull request. This packet is the canonical delivery contract for both issues.

## Outcome

Foundry provider construction returns an explicit runtime contract that describes client compaction, model switching,
local tools, PromptAgent management, and client- versus server-owned session/history semantics. Shared core enforcement
keeps Prompt behavior intact while preventing Hosted TUI and ACP sessions from invoking or advertising client-owned
operations that Hosted cannot honor.

## Scope

- Define the minimum explicit Foundry provider capability and runtime types for Prompt and Hosted execution.
- Keep `AgentKind` as configuration/factory selection input while removing frontend kind inference and concrete
  `PromptProvider` casts in this delivery's composition paths.
- Make `ProviderFactory` own the optional PromptAgent binding/management surface it constructs.
- Enforce client-compaction, model-switching, local-tool, and history-ownership behavior in shared/core composition and
  `AgentLoop`, with typed command outcomes for presentation.
- Keep local Hosted transcript persistence as a user-visible activity record while treating server session history as
  authoritative and sending only the current user input to Hosted execution.
- Apply the same runtime contract in TUI and per-session ACP composition.
- Add focused pure tests covering Prompt support and Hosted rejection/non-compaction behavior.
- Update the architecture, provider, compaction, TUI, ACP, and Hosted contracts to describe the committed boundary.

## Non-goals

- Azure test-proxy infrastructure or recordings.
- Foundry project catalogs, deployment/connection discovery, or project-runtime composition.
- Changing Hosted resume persistence or resolving Hosted `/new`, load, adoption, and cleanup policy from issue #28.
- Adding Hosted local file/tool bridging, server-side compaction controls, or model redeployment controls.
- Replacing `AgentKind` in configuration/CLI or building a generic provider/plugin registry.
- Changing Prompt transcript, compaction, local-tool, model-switching, or PromptAgent behavior.

## Acceptance

- [x] Prompt and Hosted expose explicit, test-covered values for client compaction, client model switching, local tools,
  PromptAgent management, and session/history ownership.
- [x] `ProviderFactory` returns the provider plus any typed management surface; TUI/ACP composition does not infer
  runtime behavior from `AgentKind` or cast to `PromptProvider`.
- [x] `AgentLoop` is the shared enforcement point: Hosted never auto-compacts, manual client compaction and model
  switching return typed unsupported outcomes, local tools/tool schemas are unavailable, and only current user input
  crosses the server-owned-history boundary.
- [x] Hosted TUI rejects `/compact` and `/model` without mutating local model/session state; Prompt command behavior is
  unchanged, including the bound-PromptAgent fixed-model rejection.
- [x] ACP derives Hosted non-compaction and tool/history behavior from the same runtime contract, without a frontend
  `AgentKind` guard.
- [x] Owning specs agree with source and focused Prompt/Hosted tests, full tests, package, and docs validation pass.
- [x] One final pull request links this packet and closes both #29 and #30.

## Context pack

Read only this first-pass context; expand only when source contradicts it.

### Parent alignment

- [`I055` outcome, scope, acceptance, and decisions](I055-foundry-first-platform-alignment.md) — Foundry-only platform,
  retained agent-kind seam, and capability/lifecycle alignment.

### Specifications

- [`Architecture: System layers`](../spec/architecture.md#system-layers),
  [`The AgentProvider seam`](../spec/architecture.md#the-agentprovider-seam), and
  [`Compaction integration`](../spec/architecture.md#compaction-integration).
- [`Providers: The seam recap`](../spec/providers.md#the-seam-recap),
  [`Agent-kind mapping`](../spec/providers.md#agent-kind-mapping), and
  [`Selection & construction`](../spec/providers.md#selection--construction).
- [`Compaction: Trigger`](../spec/compaction.md#trigger) and
  [`Settings`](../spec/compaction.md#settings).
- [`TUI: Slash-commands`](../spec/tui.md#slash-commands).
- [`ACP: How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor).
- [`Hosted Agents: Hosted vs Prompt`](../spec/hosted-agents.md#hosted-vs-prompt),
  [`Provider shape`](../spec/hosted-agents.md#provider-shape), and
  [`Lifecycle & ownership`](../spec/hosted-agents.md#lifecycle--ownership).

### Source entry points

- `src/main/kotlin/com/konductor/provider/AgentProvider.kt` — execution seam and new capabilities.
- `src/main/kotlin/com/konductor/provider/ProviderFactory.kt` — Prompt/Hosted runtime construction and management.
- `src/main/kotlin/com/konductor/provider/PromptProvider.kt` and
  `src/main/kotlin/com/konductor/provider/hosted/HostedProvider.kt` — capability declarations and history behavior.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — shared compaction/model/tool/history enforcement.
- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — typed command-outcome presentation.
- `src/main/kotlin/com/konductor/Main.kt` and
  `src/main/kotlin/com/konductor/acp/{AcpSessionRuntime,KonductorAcpAgent}.kt` — TUI/ACP runtime composition.

### Tests

- `src/test/kotlin/com/konductor/agent/AgentLoopCompactionTest.kt` — Prompt auto/manual compaction and Hosted guard.
- `src/test/kotlin/com/konductor/conversation/ConversationControllerTest.kt` — Prompt commands and Hosted rejection.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — per-session capability/tool composition.
- Add focused provider-runtime contract tests beside `src/test/kotlin/com/konductor/provider/`.

### Targeted searches

```bash
rg -n "provider\.kind|AgentKind\.(Prompt|Hosted)|as\? PromptProvider|ProviderFactory\.create" \
  src/main/kotlin/com/konductor/{Main.kt,agent,acp,conversation,provider}
rg -n "AgentLoop\(|\.compact\(|switchModel\(|BuiltinTools\.registry" src/main/kotlin src/test/kotlin
rg -n "client.*compaction|server.*history|PromptAgent|local tools|/compact|/model" docs/spec
```

## Decisions and constraints

- Capabilities describe behavior; they are not aliases for frontend `AgentKind` branches. `AgentKind` remains only the
  configuration/factory discriminator and provider identity.
- Core operations return typed applied/unsupported outcomes. Frontends map those reasons to localized copy instead of
  re-deciding support.
- PromptAgent management is a sealed optional runtime surface (binder + lifecycle client), not nullable management
  methods on every provider. A bound PromptAgent's baked model is a dynamic model-switch rejection owned by core.
- Local Hosted JSONL entries remain presentation/activity persistence, not the authoritative model conversation.
  Hosted server-session identifiers and resume policy remain unchanged in this delivery.
- Unsupported local tools are removed from `AgentContext` and replaced with a rejecting executor before provider
  execution; Hosted's container-owned tools are unaffected.
- No new Azure SDK workaround is expected. If implementation discovers one, record it under `docs/service_feedback/`
  per `AGENTS.md`.

## Validation

```bash
./mvnw -q -Dtest=ProviderRuntimeTest,AgentLoopCompactionTest,ConversationControllerTest,AcpSessionRuntimeFactoryTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

Perform a critical local diff review before commit/push. The final PR must request Copilot review, address actionable
feedback, resolve Copilot threads, and remain green and unmerged.

## Completion

[PR #68](https://github.com/jpalvarezl/Konductor/pull/68) completes the packet. `ProviderFactory` now returns a typed
`ProviderRuntime`; shared `AgentLoop` enforcement consumes its capabilities and management surface so Prompt retains
client-owned behavior while Hosted uses server-owned history without client compaction, model switching, local tools,
or PromptAgent controls.
