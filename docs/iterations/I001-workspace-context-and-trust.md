---
id: I001
title: Workspace context and project trust
issue: https://github.com/jpalvarezl/Konductor/issues/26
created: 2026-07-10
---

# I001 — Workspace Context and Project Trust

Load workspace instructions deterministically while preventing untrusted project configuration from silently changing
runtime behavior.

Issue [#26](https://github.com/jpalvarezl/Konductor/issues/26) owns assignment, discussion, dependencies, blockers,
and closure. This packet owns the implementation contract. The design contract below is complete enough for offline
implementation; issue status remains owner-controlled and the design PR does not claim delivery completion.

## Outcome

Prompt and ACP sessions receive cwd-correct `AGENTS.md` or fallback `CLAUDE.md` instructions. Plain-text instruction
loading and project-resource trust have explicit, separate policies, and headless execution never blocks on an
interactive trust prompt.

## Scope

- Discover global, repository-ancestor, and cwd instruction files in deterministic order.
- Define fallback behavior when both `AGENTS.md` and `CLAUDE.md` are present.
- Add a `--no-context-files` escape hatch.
- Treat `systemPromptAppend` as part of the stable Prompt definition: ephemeral Prompt assembles it immediately after
  base, while legacy and newly created persisted PromptAgents keep base + append baked and receive only cwd-dynamic
  context files and environment per turn.
- Persist project trust decisions outside the project and gate cwd `.env` together with project
  `.konductor/settings.json`.
- Define deterministic ACP behavior for trusted, untrusted, and unknown workspaces.
- Apply identical context/trust rules when an ACP session is created or loaded from its persisted cwd.

## Non-goals

- Executable extensions, packages, project-local skills, or custom tools.
- Azure Memory Stores or other cross-session memory.
- ACP tool permission prompts.
- A rich trust-management UI beyond the minimum interaction needed by the TUI.
- Runtime creation of new Prompt or Hosted agent definitions.

## Acceptance

- [ ] Global and canonical workspace-root-to-cwd instructions are assembled in a deterministic tested order.
- [ ] Per-level `AGENTS.md`/`CLAUDE.md` fallback, canonical-target deduplication, bounds, decoding, symlinks, and
  selected-file failures follow the resolved contract and are tested.
- [ ] `--no-context-files` disables instruction discovery in TUI and ACP without disabling the built-in prompt,
  environment header, configured append, configuration/trust processing, or tools.
- [ ] Ephemeral Prompt and both legacy and newly created persisted PromptAgent requests follow the same logical
  base → configured append → context files → environment → tools order; persisted agents retain their baked
  base + append and receive only context + environment dynamically, without version-layout inspection or metadata.
- [ ] Project `.env` and `.konductor/settings.json` are not read until the workspace is trusted; real process
  environment and global settings remain available.
- [ ] The canonical config directory and locked/CAS trust store stay outside the workspace and cannot be redirected by
  project configuration; corrupt/unreadable/insecure stores offer only repair-guided quit or one-run untrusted
  continuation and are never written.
- [ ] TUI `--resume` rejects another canonical cwd before workspace/runtime construction, and `--continue` searches
  only the canonical launch cwd; ACP new/load retains its distinct authoritative-session-cwd behavior, persists only
  defined binding fields, refreshes runtime fields, follows deterministic noninteractive trust without prompting, and
  `session/new` completes local runtime/provider/binding validation before durable header creation.
- [ ] The owning specs, CLI help, tests, and user/developer documentation describe the resulting behavior.

## Context pack

Read these first and do not scan the whole repository unless they prove incomplete or contradict source behavior.

### Specifications

- [`Context files`](../spec/agent-context.md#context-files),
  [`Assembly order & precedence`](../spec/agent-context.md#assembly-order--precedence), and
  [`Persisted agents: stable vs dynamic preamble`](../spec/agent-context.md#persisted-agents-stable-vs-dynamic-preamble).
- [`Config directory`](../spec/configuration.md#config-directory),
  [`Settings file and project dotenv`](../spec/configuration.md#settings-file-and-project-dotenv), and
  [`Precedence`](../spec/configuration.md#precedence).
- [`How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor) and
  [`Supported ACP methods`](../spec/acp.md#supported-acp-methods).
- [`Request shape`](../spec/providers.md#request-shape) and
  [`Persisted Prompt agents`](../spec/providers.md#persisted-prompt-agents-promptagent).
- [`TUI startup workspace binding`](../spec/sessions.md#tui-startup-workspace-binding),
  [`Persisted agents & resume`](../spec/sessions.md#persisted-agents--resume), and
  [`TUI slash commands`](../spec/tui.md#slash-commands).

### Source entry points

- `src/main/kotlin/com/konductor/agent/AgentContextFactory.kt` — `AgentContextFactory.build`, current
  base/append/environment assembly; preserve base + configured append as the stable definition component.
- `src/main/kotlin/com/konductor/config/Configuration.kt` — `Configuration.load`, which currently reads global and
  project settings eagerly; split source parsing from final defaulting/validation.
- `src/main/kotlin/com/konductor/config/EnvFile.kt` — current eager dotenv reader; move behind trust and the bounded
  canonical-target reader.
- `src/main/kotlin/com/konductor/core/models/AgentContext.kt` — stable base/dynamic-preamble representation.
- `src/main/kotlin/com/konductor/Cli.kt` — `CliOptions`, `parseCliArgs`, and help text for the
  `--no-context-files` control.
- `src/main/kotlin/com/konductor/Main.kt` — `runKonductor`/`resolveInitialSession`; canonical launch-cwd selection,
  cross-cwd `--resume` rejection and cwd-only `--continue` must happen after operator config/session-root bootstrap but
  before trust, effective project configuration, credentials, context/tools, Foundry runtime, or provider construction.
- `src/main/kotlin/com/konductor/session/SessionStore.kt` and
  `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt` — canonical-cwd `mostRecentForCwd` lookup and the
  header-only/load boundary needed for startup validation without cross-workspace fallback.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` — semantic normal-trust, corrupt-store repair/continue/quit, and
  cross-workspace resume copy.
- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` — `ConfigurationAcpSessionRuntimeFactory.create`,
  per-session two-phase configuration/provider/context ownership boundary.
- `src/main/kotlin/com/konductor/acp/KonductorAcpAgent.kt` — new/load ordering, provisional runtime ownership, and
  no-header-on-failure behavior; new-session local validation precedes durable creation while Hosted remote creation
  stays lazy.
- `src/main/kotlin/com/konductor/core/models/Session.kt`,
  `src/main/kotlin/com/konductor/session/SessionCodec.kt`, and
  `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt` — persisted model/kind/binding facts and header timing.
- `src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt` and
  `src/main/kotlin/com/konductor/provider/ProviderFactory.kt` — final post-overlay runtime/provider construction.
- `src/main/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClient.kt` — `buildParams`, ephemeral
  Prompt system instructions and transcript construction.
- `src/main/kotlin/com/konductor/provider/inference/PromptAgentClient.kt`, `PromptAgentBinder.kt`, and
  `SwitchableFoundryResponsesClient.kt` — preserve name-scoped configured/use/create/resume/ACP binding without adding
  a false exact-version or layout-classification contract.
- `src/main/kotlin/com/konductor/provider/inference/AzurePromptAgentClient.kt` — newly created definitions keep the
  existing base + configured-append instructions and tools; do not add layout metadata or inspect version details.
- `src/main/kotlin/com/konductor/conversation/PromptAgentCommand.kt` — creation bakes the resolved stable
  base + configured append, while use/resume continue to bind by name without version inspection or compatibility
  warnings.
- `src/main/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClient.kt` —
  `buildParams`/`buildInput`, with context files then environment as the only dynamic preamble.
- `src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt` and
  `src/main/kotlin/com/konductor/provider/ProviderFactory.kt` — compose name-scoped PromptAgent management/binding
  without leaking Azure SDK models above the adapter boundary.
- Add `src/main/kotlin/com/konductor/workspace/WorkspaceResolver.kt` (canonical cwd/root/config containment),
  `src/main/kotlin/com/konductor/workspace/BoundedWorkspaceFileReader.kt` (shared no-follow bounded/stability reads),
  `src/main/kotlin/com/konductor/agent/ContextFileLoader.kt` (candidate fallback/dedup/assembly),
  `src/main/kotlin/com/konductor/config/WorkspaceTrustStore.kt` (strict snapshots, security checks, lock/CAS writes), and
  `src/main/kotlin/com/konductor/config/WorkspaceTrustCoordinator.kt` (valid-unknown versus error-snapshot TUI/ACP
  outcomes). Do not embed filesystem or trust-choice policy in frontend/provider classes.

### Tests

- `src/test/kotlin/com/konductor/config/ConfigurationTest.kt` and
  `src/test/kotlin/com/konductor/config/EnvFileTest.kt` — eligibility, source precedence, parsing/defaulting split,
  malformed input, and bounded project reads.
- `src/test/kotlin/com/konductor/CliTest.kt`, add a focused `src/test/kotlin/com/konductor/MainTest.kt`, and
  `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — CLI/help; canonical same-cwd resume, cross-cwd early
  rejection with zero config/runtime/provider/context/tool construction, cwd-only continue/fresh fallback; semantic
  trust repair/continue/quit copy.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` and
  `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — authoritative cwd, two-phase load, provider
  construction ordering, complete new-session local validation before durable header creation, lazy Hosted remote
  creation, cleanup of provisional resources, and no-header-on-failure behavior.
- `src/test/kotlin/com/konductor/session/SessionCodecTest.kt` and
  `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — exact Prompt/Hosted header facts, persistence,
  canonical-cwd listing/most-recent lookup, and no global-recency fallback.
- `src/test/kotlin/com/konductor/foundry/project/FoundryProjectRuntimeTest.kt` — independently resolved per-session
  composition.
- Add `src/test/kotlin/com/konductor/agent/AgentContextFactoryTest.kt`; extend
  `src/test/kotlin/com/konductor/conversation/PromptAgentCommandTest.kt`,
  `src/test/kotlin/com/konductor/provider/inference/FoundryResponsesClientsTest.kt`,
  `src/test/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClientTest.kt`, and
  `src/test/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClientTest.kt` — exact ephemeral and
  persisted joins; new definitions bake base + append; persisted turns inject only context + environment; all binding
  routes remain name-scoped and perform no layout metadata/version-detail inspection.
- Add `src/test/kotlin/com/konductor/workspace/WorkspaceResolverTest.kt`,
  `src/test/kotlin/com/konductor/workspace/BoundedWorkspaceFileReaderTest.kt`,
  `src/test/kotlin/com/konductor/agent/ContextFileLoaderTest.kt`,
  `src/test/kotlin/com/konductor/config/WorkspaceTrustStoreTest.kt`, and
  `src/test/kotlin/com/konductor/config/WorkspaceTrustCoordinatorTest.kt`. Cover valid unknown versus error snapshots;
  corrupt UTF-8/JSON, unsupported version, unreadable/insecure/hard-linked store, Continue once, and Quit must assert
  no trust-store/lock/temp write and no ordinary Trust action.

### Targeted searches

```bash
rg -n "AgentContextFactory|dynamicPreamble|systemPromptOverride|systemPromptAppend" src/main/kotlin src/test/kotlin
rg -n "settings.json|KONDUCTOR_CONFIG_DIR|Configuration.load" src/main/kotlin src/test/kotlin
rg -n "parseCliArgs|KonductorCli|no-context-files|resolveInitialSession|mostRecentForCwd" src/main/kotlin src/test/kotlin
rg -n "ConfigurationAcpSessionRuntimeFactory|session.cwd" src/main/kotlin/com/konductor/acp src/test/kotlin/com/konductor/acp
rg -n "EnvFile|SessionCodec|JsonlSessionStore|hostedBinding|promptAgentName" src/main/kotlin src/test/kotlin
rg -n "createAgentVersion|buildAgentScopedOpenAIClient|PromptAgentBinder|bindAgent" src/main/kotlin src/test/kotlin
rg -n "buildParams|buildInput|dynamicPreamble|systemPrompt" src/main/kotlin/com/konductor/provider/inference src/test/kotlin/com/konductor/provider/inference
```

## Resolved design contract

### Workspace root and instruction selection

- Canonicalize the existing cwd. Walking upward, a workspace marker is a literal `.git` entry that is a non-symlink
  regular file or directory under a no-follow inspection. A `.git` symlink or other type does not qualify or redirect
  the walk; an inspection failure is an error. The nearest qualifying ancestor wins, or canonical cwd is the root.
- Consider the canonical config directory first, then each directory from canonical root through canonical cwd. At
  each location inspect literal `AGENTS.md` without following its final link. Only definite absence permits probing
  `CLAUDE.md`; an invalid/present entry, permissions error, indeterminate I/O, or post-selection disappearance fails
  rather than falling back. Host case semantics apply and there is no directory scan.
- After fallback, canonicalize targets and retain the first occurrence of each. Project targets must resolve to regular
  files inside canonical workspace root; global targets must resolve inside canonical config directory. Dangling,
  escaping, non-regular, unreadable, changed, or otherwise invalid selected entries abort the entire context load.
- Bound the deduplicated set to 32 files including global, 64 KiB per file, and 256 KiB total. Canonicalize and
  containment-check each selected target; no-follow open and boundedly read that canonical target; then compare the
  original entry's pre/post canonical target, type, containment, and all available stable attributes/file key. Fail on
  every observed change and use a secure-directory/handle primitive when the platform supplies one. Count actual read
  bytes (metadata size is only a hint), read at most limit + 1, and never truncate as success. Decode strict UTF-8,
  strip one optional BOM, and normalize CRLF/lone CR to LF. The portable fallback does not claim to detect an
  unobservable same-attributes ABA replacement.

### Prompt assembly and frontend switch

- The stable logical order is **base → configured append → context files → environment → tools**. A configured
  `systemPromptOverride` replaces base; `systemPromptAppend` immediately follows that effective base and is part of the
  stable definition component. After BOM/newline normalization, omit null/zero-length text components, retain
  whitespace-only content, and join first files and then applicable logical text components with literal `"\n\n"`;
  do not trim, label, or append a final newline. Tools remain structured.
- Ephemeral Prompt sends base, the currently configured append, context, and environment as request instructions in
  that order. Every PromptAgent version created by Konductor continues the existing compatible definition shape:
  creation-time base + configured append are baked into `PromptAgentDefinition.instructions`, and tools are baked into
  the definition. Persisted turns prepend only current context files + environment as one developer input item.
- The pinned Azure Agents 2.2.0 agent-scoped Responses construction,
  `AgentsClientBuilder.buildAgentScopedOpenAIClient(name)`, is name-only. It cannot pin an inspected version, so a
  separate latest-version metadata/definition read could race the version actually selected for invocation. Konductor
  therefore neither writes layout metadata nor inspects/classifies versions. Legacy and newly created agents use the
  same baked base + append contract, bind by name, and receive no layout mismatch warning. A changed configured base or
  append affects ephemeral requests immediately but requires explicitly creating a new persisted version to affect a
  bound PromptAgent.
- `--no-context-files` applies to both TUI and ACP and removes only discovered context text. It does not remove base,
  environment, append, tools, or configuration/trust behavior.

### Trust bootstrap, identity, persistence, and TUI behavior

- Keep real process environment distinct from cwd dotenv. Resolve the config directory only from an application/CLI
  override, real-process `KONDUCTOR_CONFIG_DIR`, or the default/global operator bootstrap; v1 global settings cannot
  relocate themselves. Project dotenv/settings never redirect it. Resolve relative overrides against canonical home;
  canonicalize existing, nonexistent, and symlinked paths; create/revalidate safely; reject a target equal to or below
  each canonical workspace root before reading global settings or trust. TUI fails that run; ACP fails only that session
  request without a new header.
- Trust identity is the absolute canonical workspace-root path. Symlink aliases share identity; moving/copying returns
  to unknown. Project `.env` and `<cwd>/.konductor/settings.json` share the gate and neither is opened before trust.
  Real process env outranks trusted dotenv, which outranks trusted project settings, global settings, and defaults.
  Project `KONDUCTOR_CONFIG_DIR` is ignored even after trust.
- Trusted project `.env` and settings targets are canonicalized/contained, bounded to 256 KiB each, read from the
  canonical target with `NOFOLLOW_LINKS`, and compared before/after by canonical target and all available stable
  attributes/file key; any observed change fails. Use stronger secure-directory operations when supplied and explicitly
  exclude unobservable adversarial ABA from the fallback guarantee.
- `workspace-trust.json` v1 is strict: exactly integer version 1 plus a `workspaces` object, no duplicate/unknown
  members or trailing JSON, absolute normalized path keys that are pairwise unique under host path semantics, and exact
  `trusted`/`untrusted` values. Any malformed UTF-8, JSON, member, key, value, host-duplicate key, or unsupported version
  invalidates the whole store. Missing is a valid empty snapshot; unreadable/invalid is an error snapshot handled only
  by repair-guided one-run untrusted continuation or quit, and is never overwritten as recovery.
- Pin the user-controlled canonical config directory. Every initial/reread/final trust-store access is a no-follow,
  regular-file, 1 MiB bounded read against that directory with pre/post type/path/attribute checks. Require effective-
  user ownership and safe mode/ACL; require link count one for store/lock/temp when exposed, or an equivalent host
  anti-hard-link guarantee when it is not. A hard-linked trust file is invalid. Use platform-specific security views
  rather than demand one nonexistent portable primitive. If operator ownership, repository write isolation, or
  anti-hard-link safety cannot be established, trust is unknown and writes are disabled. Same-user hostile processes
  remain outside the threat model.
- Writers use a five-second bounded exclusive no-follow lock on a sibling lock file and CAS against the exact initially
  read snapshot. Under lock they reread with the same checks: merge unrelated valid changes, accept an identical
  same-workspace decision idempotently, but reject an opposite decision, invalid/unreadable state, or another
  pre-replace change without overwrite. Flush a checked create-new sibling temp and atomically replace with no
  non-atomic fallback. Timeout/failure/conflict keeps the current run untrusted.
- For a valid store, TUI prompts only when trust is unknown and a no-follow presence probe definitely finds cwd `.env`
  or project settings. The normal prompt names canonical root and defaults untrusted. A trusted answer takes effect
  only after successful persistence; known decisions do not prompt and valid unknown workspaces with neither gated
  entry do not write.
- An unreadable, malformed, unsupported, insecure, or otherwise invalid trust store uses a dedicated repair screen,
  not that normal prompt. It identifies the path/problem and ownership/permission/content repair, offers only
  **Continue untrusted for this run** or **Quit** (default), and never writes store/lock/temp state. Continue opens no
  project config and stores no decision; Quit exits before runtime/context/tools/provider construction. Show this even
  without gated entries, and never offer a persisted Trust action that cannot succeed against the error snapshot.
- Plain-text context still loads in every continuing trust state unless `--no-context-files` is set. Global settings,
  real process env, CLI inputs, built-ins, and tools retain operator-controlled eligibility.

### TUI startup session policy

- Canonicalize the launch cwd and resolve the external config directory and `<config-dir>/sessions` root first. Before
  trust/project configuration, credential, context, tools, Foundry runtime, or provider construction, load an explicit
  `--resume` header and require
  its canonical persisted cwd to equal canonical launch cwd under host semantics. Same Git root is insufficient. A
  missing/invalid cwd or mismatch aborts with both paths and guidance to launch from the session workspace; never
  re-root or construct launch-workspace tools for it.
- `--continue` calls `mostRecentForCwd(canonicalLaunchCwd)` only. It does not search another cwd bucket or global
  recency; no match creates a fresh canonical-launch-cwd session. Default startup does the same, and `--name` applies
  only after selection succeeds.
- This policy is specific to the single-workspace TUI startup graph. ACP load remains persisted-cwd-authoritative and
  builds an isolated per-session graph after reading that cwd.

### ACP behavior

- Resolve trust and effective configuration independently for every authoritative session cwd. `session/new`
  canonicalizes the client cwd and, before durable header creation, completes trust/source resolution, final
  configuration validation, credential/Foundry runtime and provider construction, PromptAgent/Hosted binding-shape
  validation, context assembly, and supported tool-runtime construction. It holds the UUID/header candidate and runtime
  provisionally, closes provisional resources on any failure, and writes no header. Hosted network session creation is
  not part of this validation and remains lazy until the first prompt after the local header is durable.
  `session/load` uses persisted cwd and ignores caller/launch cwd. ACP never prompts/writes trust, and unknown/error
  state ignores both project files.
- New headers under `<config-dir>/sessions` persist UUID, canonical cwd, created time/name, effective model, and exactly
  one binding shape: optional PromptAgent for Prompt v1 or hosted agent + reserved UUID for Hosted v2. Only after all
  preceding local validation succeeds may Konductor durably create that header and publish the ACP session. A header
  write failure closes the provisional runtime; every pre-session failure is returned as the `session/new` JSON-RPC
  request error, not through an undefined session diagnostic.
- Load keeps header identity, transcript, model, provider/history kind, PromptAgent (including absent/ephemeral), and
  Hosted binding. Phase one resolves trust and parses eligible fresh sources into a partial candidate without
  defaulting, requiring, or cross-validating fresh model/kind/agent identity. Phase two overlays exact header
  model/kind/binding, then applies runtime defaults, performs final validation, and constructs the credential, Foundry
  runtime, provider, context, and tools. PromptAgent binding remains name-scoped and adds only context + environment to
  the already baked base + append; it performs no version-layout inspection. Fresh identity conflicts cannot replace
  or veto persisted identity; malformed eligible sources, inconsistent headers, missing final runtime requirements,
  and unusable local bindings fail. Exact Hosted binding is revalidated in the finally resolved Foundry project.
- `session/list` canonicalizes its caller cwd and applies the same outside-workspace config-directory check before
  accessing that cwd's session store; it does not prompt for or mutate trust.
- Plain-text global/root-to-cwd context loads for all trust states under the same race/bound rules and process
  `--no-context-files` flag.

## Decisions and constraints

- `src/` and tests remain implementation truth; specs describe the intended contract.
- Plain-text instructions are prompt input and can inject instructions. This documented risk is distinct from allowing
  project configuration to change provider, tool, or runtime behavior.
- Trust gates cwd `.env` and project `.konductor/settings.json`; it never gates global operator configuration.
- Trust persistence belongs under the user-controlled Konductor config directory, never inside the repository.
- `ConfigurationAcpSessionRuntimeFactory` remains the integration boundary for session-specific context, trust,
  configuration, provider, and tool construction.

## Validation

For the design-only PR, validate packet metadata and local Markdown links/anchors:

```bash
node scripts/validate-docs.mjs
```

During implementation, run focused context/config/ACP tests and then the full suite:

```bash
./mvnw -q test
```

Manually verify one trusted and one untrusted repository in the TUI, corrupt-store continue/quit with no write, same-
and cross-cwd `--resume`, cwd-only `--continue`, legacy and newly created PromptAgent bindings, plus unknown-trust ACP
create/load sessions whose cwd differs from the launch directory. Verify ACP new-session validation failures leave no
header and that Hosted remote creation stays lazy until the first prompt. Include symlink aliases, symlinked `.git`, a
nested repository, a cwd outside Git, relative/nonexistent/symlinked config dirs, malformed and concurrently changed trust
state, dotenv gating, ACP persisted/refreshed fields, no-follow fallback, and instruction bound/race/read failures in
focused automated coverage.

## Documentation context

- Owning specs: `agent-context.md`, `configuration.md`, `sessions.md`, `acp.md`, `providers.md`, `tui.md`, and the
  affected composition/startup boundary in `architecture.md`.
- User/developer docs for the implementing PR: `README.md`, `docs/development.md`, `docs/hero-scenario.md`, CLI help,
  and `AGENTS.md` if repository guidance changes.
- Service feedback is required when this design is implemented: extend `docs/service_feedback/prompt_agents.md` with
  the exact `com.azure:azure-ai-agents` 2.2.0 name-scoped method
  `AgentsClientBuilder.buildAgentScopedOpenAIClient(name)`, its inability to pin the version selected for Responses,
  why inspecting latest version details cannot safely classify the version later selected by a name-only invocation,
  implementation impact, Konductor's stable base + append workaround, and a suggested version-pinnable, closeable SDK
  path. Keep/update its existing README index row. This design-only PR records the requirement; it does not claim new
  SDK/service evidence.

## Completion

The final implementing PR closes issue #26 and records the resulting trust semantics here. Move excluded follow-ups to
[`future.md`](../future.md) or focused GitHub issues rather than extending this delivery after implementation starts.
