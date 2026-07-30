---
id: I055
title: Foundry-first platform alignment
issue: https://github.com/jpalvarezl/Konductor/issues/55
created: 2026-07-27
---

# I055 — Foundry-First Platform Alignment

Issue [#55](https://github.com/jpalvarezl/Konductor/issues/55) owns coordination, dependencies, status, and closure.
This packet records the accepted multi-PR implementation contract and the smallest useful context route.

## Outcome

Konductor explicitly targets Azure AI Foundry as its application platform and dog-foods both Java SDK packages through
a cohesive project/agent composition boundary, while retaining narrow interfaces only for SDK adaptation, resource
lifecycle, agent-kind semantics, and deterministic testing—not backend portability.

## Scope

- Remove vendor independence, backend swappability, and multi-provider support from product and architecture goals.
- Preserve the frontend/core boundary: TUI, ACP, `AgentLoop`, local sessions, and local tools do not import raw Azure SDK
  models.
- Reframe `AgentProvider` as a seam between Foundry agent execution models such as Prompt and Hosted, not between
  service vendors.
- Reframe or rename the Prompt one-call seam so its purpose is Foundry Responses adaptation and testability rather than
  generic inference portability.
- Define a focused Foundry project composition/runtime boundary that owns project endpoint, Azure credential, preview
  policy, SDK client construction, and closeable resources without becoming a service locator.
- Dog-food `azure-ai-projects` in production through a first vertical slice covering project deployment discovery and
  the project connections needed by later Foundry tools.
- Align Hosted session lifecycle and provider capabilities with the Foundry-specific execution model.
- Reprioritize future work around Foundry projects, agents, conversations, memory, tools, evaluations, and related SDK
  surfaces.
- Capture SDK/service friction encountered during migration in `docs/service_feedback/`.

## Non-goals

- Passing Azure SDK types through frontend, protocol, persistence, or pure orchestration APIs.
- Removing fakeable network boundaries or replacing focused adapters with static/global SDK access.
- Integrating every `azure-ai-agents` or `azure-ai-projects` operation solely for API coverage.
- Implementing Memory Stores, evaluations, Workflow agents, datasets, indexes, routines, skills, or all server-side
  tools in this delivery.
- Replacing local JSONL sessions, client-owned Prompt history, or cwd-contained local tools unless a separately accepted
  Foundry mode changes their ownership.
- Supporting a second inference vendor or generic backend registry.

## Acceptance

- [x] Stable docs state that Azure AI Foundry is the only application/backend platform Konductor targets.
- [x] Provider and Prompt-call seams are documented and named according to Foundry agent-kind/SDK responsibilities,
  without vendor-neutral or backend-swappability claims.
- [x] A focused Foundry project composition boundary centralizes endpoint, credential, preview policy, and SDK client
  lifecycle without exposing a general service locator.
- [x] Production code exercises `azure-ai-projects` deployment discovery and project connection discovery through
  fakeable application services.
- [x] Model selection validates or assists against project deployments with a documented offline/service-failure
  fallback.
- [x] Foundry connection metadata is available to later server-tool configuration without exposing credentials by
  default.
- [x] Existing Prompt and Hosted paths remain functional, and related lifecycle/capability defects in #28–#30 are
  resolved or explicitly sequenced within this milestone.
- [x] Unit tests cover the Foundry composition boundary and project discovery; the full test/package suite passes.
- [x] Production TUI discovery composition is mandatory; intentionally unavailable embedder composition cannot
  masquerade as a valid empty Foundry project.
- [x] Future directions and GitHub roadmap no longer advertise multi-provider/backend portability.
- [x] SDK/service rough edges include exact Java SDK APIs and version 2.2.0 when workarounds are required.

## Context pack

Read only this first-pass context. Expand beyond it only when implementation proves it incomplete or contradicted.

### External SDK contracts

- [`azure-ai-agents` 2.2.0 README](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/README.md) —
  `AgentsClientBuilder`, Responses/OpenAI clients, agent kinds, Hosted sessions/files, Memory Stores, Toolboxes, tools,
  and preview opt-in rules.
- [`azure-ai-projects` 2.2.0 README](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-projects/README.md) —
  `AIProjectClientBuilder`, Deployments, Connections, datasets/indexes, evaluation/insight surfaces, Agents transitive
  dependency, and preview opt-in rules.

Both links pin Azure SDK commit `ee28e96b521` (the Agents/Projects 2.2.0 release), so the contract does not depend on a
particular local SDK checkout.

### Specifications

- [`Goals & non-goals`](../spec/architecture.md#goals--non-goals),
  [`System layers`](../spec/architecture.md#system-layers), and
  [`Two axes, two seams`](../spec/architecture.md#two-axes-two-seams).
- [`The seam recap`](../spec/providers.md#the-seam-recap),
  [`Selection & construction`](../spec/providers.md#selection--construction), and
  [`Client construction & auth`](../spec/providers.md#client-construction--auth-shared).
- [`Selecting the provider / agent kind`](../spec/configuration.md#selecting-the-provider--agent-kind).
- [`Hosted vs Prompt`](../spec/hosted-agents.md#hosted-vs-prompt) and
  [`Lifecycle & ownership`](../spec/hosted-agents.md#lifecycle--ownership).

### Source entry points

- `pom.xml` — current direct dependencies on `azure-ai-agents` and `azure-ai-projects` 2.2.0.
- `src/main/kotlin/com/konductor/config/Configuration.kt` — project endpoint, Azure credential, agent-kind, and settings
  resolution.
- `src/main/kotlin/com/konductor/provider/ProviderFactory.kt` — current Prompt/Hosted construction and scattered SDK
  client ownership.
- `src/main/kotlin/com/konductor/provider/AgentProvider.kt` — Foundry agent execution-model seam.
- `src/main/kotlin/com/konductor/provider/PromptProvider.kt` — client-owned Foundry Responses tool loop.
- `src/main/kotlin/com/konductor/provider/inference/FoundryResponsesClient.kt` and the
  `FoundryResponsesRequest`/`FoundryResponsesResult`/`FoundryResponsesEvent` models — the Foundry-specific one-call
  test seam.
- `src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt` and
  `src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt` — ephemeral and agent-scoped
  Foundry Responses adapters.
- `src/main/kotlin/com/konductor/provider/hosted/AzureHostedAgentClient.kt` — Hosted Agents SDK adapter and preview
  lifecycle.
- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` and `src/main/kotlin/com/konductor/Main.kt` — composition and
  per-session resource ownership.

### Tests

- `src/test/kotlin/com/konductor/provider/PromptProviderTest.kt` — mock one-call seam and client-owned tool loop.
- `src/test/kotlin/com/konductor/provider/hosted/HostedProviderTest.kt` — Foundry Hosted lifecycle adapter behavior.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — per-session composition/resource isolation.
- `src/test/kotlin/com/konductor/config/ConfigurationTest.kt` — project/auth/config precedence.
- Add focused Foundry project runtime, deployment catalog, and connection catalog tests alongside the owning source.

### Targeted searches

```bash
rg -n -i "vendor|multi-provider|backend|swapp|neutral.*seam|FoundryResponsesClient" \
  README.md AGENTS.md docs src/main/kotlin src/test/kotlin
rg -n -e "InferenceClient|InferenceRequest|InferenceResponse|InferenceChunk|AzureInferenceClient" \
  -e "AzurePromptAgentInferenceClient|SwappableInferenceClient|FakeInferenceClient" \
  README.md AGENTS.md docs src/main/kotlin src/test/kotlin
rg -n "AgentsClientBuilder|AIProjectClientBuilder|allowPreview|\.beta\(\)" src/main/kotlin src/test/kotlin
rg -n "ProviderFactory|ConfigurationAcpSessionRuntimeFactory|Configuration\.load" \
  src/main/kotlin src/test/kotlin
rg -n "DeploymentsClient|ConnectionsClient|com\.azure\.ai\.projects" src/main/kotlin src/test/kotlin pom.xml
```

The stale-reference search intentionally retains the pre-I055 symbols. Matches are acceptable only in frozen historical
records that explicitly identify them as aliases; current specs, source, and tests use the Foundry Responses names.

## Decisions and constraints

- Azure AI Foundry is the sole service platform target. Configuration and domain language may be Foundry-specific.
- Interfaces remain only when they isolate network/preview SDK behavior, own resources, distinguish Foundry execution
  models, or enable deterministic tests. They must not imply planned non-Foundry implementations.
- `AgentProvider` remains valuable because Prompt and Hosted own their loops/history differently within Foundry.
- The Prompt one-call seam may remain structurally similar for tests, but its naming/docs must identify it as a Foundry
  Responses SDK adapter.
- Prefer Azure SDK convenience surfaces such as `ResponsesClient.createAzureResponse` when they support the required
  behavior and lifecycle. Keep direct OpenAI-client use only where measured SDK limitations require it, and record the
  limitation as service feedback.
- Preview availability is explicit: non-Beta preview operations use `allowPreview(true)` and `Beta*Client` surfaces use
  the builder's `.beta()` path as documented by SDK 2.2.0.
- The first `azure-ai-projects` slice is deployment and connection discovery because it improves current model/tool
  behavior and establishes the project composition boundary for later features.

## Validation

During implementation, run focused provider/config/project tests, then:

```bash
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
```

Live validation must list deployments and non-secret connection metadata from a real Foundry project, then complete one
Prompt turn and one Hosted turn where available.

## Completion

- Contract and roadmap alignment: [PR #56](https://github.com/jpalvarezl/Konductor/pull/56).
- Project discovery adapters: [PR #62](https://github.com/jpalvarezl/Konductor/pull/62) and
  [PR #63](https://github.com/jpalvarezl/Konductor/pull/63).
- Focused project composition boundary: [PR #76](https://github.com/jpalvarezl/Konductor/pull/76).
- TUI deployment validation and credential-safe connection discovery:
  [PR #78](https://github.com/jpalvarezl/Konductor/pull/78).
- Explicit production discovery composition and unavailable-embedder handling:
  [PR #98](https://github.com/jpalvarezl/Konductor/pull/98).
- Durable Foundry Hosted session lifecycle across TUI and ACP:
  [PR #100](https://github.com/jpalvarezl/Konductor/pull/100).

This packet may span multiple focused PRs. Record the final acceptance-completing PR and resulting Foundry composition
boundary here. Follow-up service integrations remain in their focused issues and are not implicitly included.
