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

Prompt and ACP sessions receive cwd-correct, path-attributed `AGENTS.md` or fallback `CLAUDE.md` instructions.
Plain-text instruction loading and project-resource trust have explicit, separate policies, one-run trust controls are
available to both frontends, and headless execution never blocks on an interactive trust prompt.

## Scope

- Discover global, repository-ancestor, and cwd instruction files as ordered structured records and render deterministic
  path-bearing provenance boundaries.
- Define fallback behavior when both `AGENTS.md` and `CLAUDE.md` are present.
- Add `--no-context-files`, explicit `--config-dir <path>`, and process-only `--approve`/`--no-approve` controls to both
  TUI and ACP application inputs.
- Treat `systemPromptAppend` as part of the stable Prompt definition: ephemeral Prompt assembles it immediately after
  base, while legacy and newly created persisted PromptAgents keep base + append baked and receive the same rendered
  cwd-dynamic context block plus environment per turn.
- Persist project trust decisions outside the project; gate cwd `.env` together with project
  `.konductor/settings.json`; support persistent and session-only normal TUI choices.
- Define implementable POSIX/Windows trust-store path, read, lock, merge, force, and atomic-replace behavior at a local
  same-user trust boundary.
- Define provisional `SessionStore.newCandidate`/`persistNew` publication and prohibit create-then-delete rollback.
- Define deterministic ACP behavior for trusted, untrusted, unknown, and erroneous trust state.
- Apply identical context/trust rules when an ACP session is created or loaded from its persisted cwd.

## Non-goals

- Executable extensions, packages, project-local skills, or custom tools.
- Azure Memory Stores or other cross-session memory.
- ACP tool permission prompts.
- A rich trust-management UI beyond the minimum interaction needed by the TUI.
- Runtime creation of new Prompt or Hosted agent definitions.

## Acceptance

- [ ] Global and canonical workspace-root-to-cwd instructions load as ordered `(canonical display path, normalized
  content)` records in deterministic tested order.
- [ ] Per-level `AGENTS.md`/`CLAUDE.md` fallback, canonical-target deduplication, bounds, decoding, symlinks, and
  selected-file failures follow the resolved contract and are tested.
- [ ] One exact renderer emits escaped path attributes and deterministic `<project_context>` /
  `<project_instructions>` boundaries, including specified empty/whitespace behavior; ephemeral and persisted Prompt
  paths consume the same rendered block and never raw-concatenate file bodies.
- [ ] `--no-context-files` disables instruction discovery in TUI and ACP without disabling the built-in prompt,
  environment header, configured append, configuration/trust processing, or tools.
- [ ] `--config-dir <path>` outranks only real-process `KONDUCTOR_CONFIG_DIR` and the default; project dotenv/settings
  can never supply it, and CLI/application/help/tests cover both frontends.
- [ ] Project `.env` and `.konductor/settings.json` are not read until the workspace is trusted; real process
  environment and global settings remain available. Normal valid-unknown TUI flow offers persistent and session-only
  trust/untrust choices.
- [ ] `--approve`/`-a` and `--no-approve`/`-na` are mutually exclusive one-run inputs in TUI and ACP, never prompt or
  persist, and follow the distinct corrupt-store safety behavior in the resolved contract.
- [ ] The canonical config directory and trust-store/lock/candidate entries stay outside the workspace, reject path
  redirection, use bounded strict no-follow reads plus lock/reread/merge/forced-candidate/atomic-replace, and are tested
  on POSIX and Windows without impossible universal ACL or hard-link proofs.
- [ ] TUI `--resume` rejects another canonical cwd before workspace/runtime construction, and `--continue` searches
  only the canonical launch cwd; ACP load retains its distinct authoritative-session-cwd behavior and refreshed runtime
  fields.
- [ ] New TUI and ACP sessions use `newCandidate` then one `persistNew` header commit after all local validation;
  header inspection/load remains separate, NoOp commit performs no I/O, and no failure path creates then deletes a
  durable header.
- [ ] The owning specs, CLI help, tests, and user/developer documentation describe the resulting behavior.

## Context pack

Read these first and do not scan the whole repository unless they prove incomplete or contradict source behavior.

### Specifications

- [`Context files`](../spec/agent-context.md#context-files),
  [`Assembly order & precedence`](../spec/agent-context.md#assembly-order--precedence), and
  [`Persisted agents: stable vs dynamic preamble`](../spec/agent-context.md#persisted-agents-stable-vs-dynamic-preamble).
- [`Config directory`](../spec/configuration.md#config-directory),
  [`Settings file and project dotenv`](../spec/configuration.md#settings-file-and-project-dotenv),
  [`Workspace identity and trust store`](../spec/configuration.md#workspace-identity-and-trust-store), and
  [`Precedence`](../spec/configuration.md#precedence).
- [`How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor) and
  [`Supported ACP methods`](../spec/acp.md#supported-acp-methods).
- [`Request shape`](../spec/providers.md#request-shape) and
  [`Persisted Prompt agents`](../spec/providers.md#persisted-prompt-agents-promptagent).
- [`TUI startup workspace binding`](../spec/sessions.md#tui-startup-workspace-binding),
  [`SessionStore API`](../spec/sessions.md#sessionstore-api),
  [`Persisted agents & resume`](../spec/sessions.md#persisted-agents--resume), and
  [`TUI slash commands`](../spec/tui.md#slash-commands).

### Source entry points

- `src/main/kotlin/com/konductor/agent/AgentContextFactory.kt` — `AgentContextFactory.build`, current
  base/append/environment assembly; preserve base + configured append as the stable definition component and consume
  the single rendered context block in both request shapes.
- `src/main/kotlin/com/konductor/config/Configuration.kt` — `Configuration.load`, which currently reads global and
  project settings eagerly; split source parsing from final defaulting/validation.
- `src/main/kotlin/com/konductor/config/EnvFile.kt` — current eager dotenv reader; move behind trust and the bounded
  canonical-target reader.
- `src/main/kotlin/com/konductor/core/models/AgentContext.kt` — stable base/dynamic-preamble representation.
- `src/main/kotlin/com/konductor/Cli.kt` — `CliOptions`, `parseCliArgs`, validation, and localized help text for
  `--no-context-files`, `--config-dir <path>`, `--approve`/`-a`, and `--no-approve`/`-na`; trust flags conflict, while
  config/context/trust controls are valid in both TUI and ACP.
- `src/main/kotlin/com/konductor/Main.kt` — `runKonductor`/`resolveInitialSession`; process config-dir/trust inputs and
  session-root bootstrap precede canonical launch-cwd selection; cross-cwd `--resume` rejection and cwd-only
  `--continue` happen before workspace construction; fresh TUI candidates commit only after trust/configuration,
  credentials, context/tools, Foundry runtime, provider, and binding validation.
- `src/main/kotlin/com/konductor/session/SessionStore.kt`,
  `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt`, and
  `src/main/kotlin/com/konductor/session/NoOpSessionStore.kt` — replace durable `create` with allocation-only
  `newCandidate` plus one `persistNew`; add the header-only/load boundary needed for startup validation; preserve
  canonical-cwd `mostRecentForCwd`; NoOp commit is no I/O.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and
  `src/main/resources/com/konductor/i18n/messages.properties` — semantic/localized CLI help for all new flags,
  four-choice normal-trust copy, corrupt-store repair/continue/quit, override/config-dir diagnostics, and
  cross-workspace resume copy.
- Add `src/main/kotlin/com/konductor/tui/WorkspaceTrustPrompt.kt` as the minimal frontend chooser for coordinator-
  supplied normal/error options. It owns selection/default presentation only; filesystem/store policy remains in the
  coordinator.
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
  `buildParams`/`buildInput`, with the shared rendered context block then environment as the only dynamic preamble.
- `src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt` and
  `src/main/kotlin/com/konductor/provider/ProviderFactory.kt` — compose name-scoped PromptAgent management/binding
  without leaking Azure SDK models above the adapter boundary.
- Add `src/main/kotlin/com/konductor/workspace/WorkspaceResolver.kt` (canonical cwd/root/config containment),
  `src/main/kotlin/com/konductor/workspace/BoundedWorkspaceFileReader.kt` (shared no-follow bounded/stability reads),
  `src/main/kotlin/com/konductor/agent/ContextFileLoader.kt` (candidate fallback/dedup and ordered structured records),
  `src/main/kotlin/com/konductor/agent/ContextBlockRenderer.kt` (the sole path normalization/escaping/boundary renderer),
  `src/main/kotlin/com/konductor/config/WorkspaceTrustStore.kt` (strict snapshots, local-boundary checks,
  lock/reread/merge/forced-candidate writes), and
  `src/main/kotlin/com/konductor/config/WorkspaceTrustCoordinator.kt` (overrides, session/persistent normal choices,
  and error-snapshot TUI/ACP outcomes). Do not embed filesystem, rendering, or trust-store policy in frontend/provider
  classes.

### Tests

- `src/test/kotlin/com/konductor/config/ConfigurationTest.kt` and
  `src/test/kotlin/com/konductor/config/EnvFileTest.kt` — eligibility, source precedence, parsing/defaulting split,
  malformed input, and bounded project reads.
- `src/test/kotlin/com/konductor/CliTest.kt`, add a focused `src/test/kotlin/com/konductor/MainTest.kt`, and
  `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — parsing/conflicts/help for all four new controls;
  config-dir precedence; same-cwd resume, cross-cwd early rejection with zero workspace construction, cwd-only
  continue/fresh candidate fallback; semantic four-choice trust, override, repair, and localized help copy. Add
  `src/test/kotlin/com/konductor/tui/WorkspaceTrustPromptTest.kt` for all four choices, the session-only-untrusted
  default, override prompt suppression, and zero writes for both session-only choices.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` and
  `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — authoritative cwd, config/trust override
  propagation, two-phase load, provider construction ordering, complete new-session local validation before exactly one
  `persistNew`, lazy Hosted remote creation, provisional cleanup, and no-header/no-create-delete rollback on failure.
- `src/test/kotlin/com/konductor/session/SessionCodecTest.kt`,
  `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt`, and
  `src/test/kotlin/com/konductor/session/NoOpSessionStoreTest.kt` — exact Prompt/Hosted header facts; non-durable
  candidate invisibility; header-only inspection; one forced/atomic create-new publication; NoOp commit; canonical-cwd
  listing/most-recent lookup; and no global-recency or create/delete fallback.
- `src/test/kotlin/com/konductor/foundry/project/FoundryProjectRuntimeTest.kt` — independently resolved per-session
  composition.
- Add `src/test/kotlin/com/konductor/agent/AgentContextFactoryTest.kt`; extend
  `src/test/kotlin/com/konductor/conversation/PromptAgentCommandTest.kt`,
  `src/test/kotlin/com/konductor/provider/inference/FoundryResponsesClientsTest.kt`,
  `src/test/kotlin/com/konductor/provider/inference/EphemeralFoundryResponsesClientTest.kt`, and
  `src/test/kotlin/com/konductor/provider/inference/PromptAgentFoundryResponsesClientTest.kt` — exact ephemeral and
  persisted joins; new definitions bake base + append; persisted turns inject only the shared rendered context block +
  environment; all binding routes remain name-scoped and perform no layout metadata/version-detail inspection.
- Add `src/test/kotlin/com/konductor/workspace/WorkspaceResolverTest.kt`,
  `src/test/kotlin/com/konductor/workspace/BoundedWorkspaceFileReaderTest.kt`,
  `src/test/kotlin/com/konductor/agent/ContextFileLoaderTest.kt`,
  `src/test/kotlin/com/konductor/agent/ContextBlockRendererTest.kt`,
  `src/test/kotlin/com/konductor/config/WorkspaceTrustStoreTest.kt`, and
  `src/test/kotlin/com/konductor/config/WorkspaceTrustCoordinatorTest.kt`. Cover exact path-bearing rendering,
  valid-unknown versus error snapshots, four normal choices, both overrides, corrupt UTF-8/JSON, unsupported version,
  path redirection, lock/merge/force/atomic failures, and no forbidden write/read. Use a POSIX/Windows matrix: enforce
  reliable owner/mode/link checks on POSIX and Windows no-follow/reparse/path identity while proving unavailable hard-link
  count or exhaustive ACL analysis does not disable trust.

### Targeted searches

```bash
rg -n "AgentContextFactory|dynamicPreamble|systemPromptOverride|systemPromptAppend" src/main/kotlin src/test/kotlin
rg -n "settings.json|KONDUCTOR_CONFIG_DIR|config-dir|Configuration.load" src/main/kotlin src/main/resources src/test/kotlin
rg -n "parseCliArgs|KonductorCli|no-context-files|approve|resolveInitialSession|newCandidate|persistNew|loadHeader|mostRecentForCwd" src/main/kotlin src/main/resources src/test/kotlin
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

- The context loader returns one ordered record per deduplicated selected file, including empty files. Each record holds
  the canonical target's absolute, lexically normalized display path with `/` separators and its strict-decoded,
  BOM-stripped, LF-normalized content. Order is global then root-to-cwd and remains independent of trust.
- The sole renderer emits `<project_context>` containing ordered `<project_instructions path="...">` elements with no
  indentation or unlabeled body concatenation. It XML-attribute-escapes `&`, `<`, `>`, `"`, and `'` in paths; content is
  preserved, not trimmed/escaped. Empty content still renders an empty line inside its element, whitespace-only content
  is retained, and an empty record list omits the complete block. Exact newlines follow the owning spec.
- The stable logical order is **base → configured append → rendered context block → environment → tools**. A configured
  `systemPromptOverride` replaces base; `systemPromptAppend` immediately follows that effective base and is part of the
  stable definition component. Omit null/zero-length non-context components, retain whitespace-only components, and
  join applicable components with literal `"\n\n"`; do not trim or append a final newline. Tools remain structured.
- Ephemeral Prompt sends base, current append, the rendered block, and environment as request instructions. Every
  PromptAgent version created by Konductor keeps creation-time base + append and tools baked into the definition.
  Persisted turns prepend the **same rendered block bytes** + environment as one developer input item; no second
  flattening/rendering path is allowed.
- The pinned Azure Agents 2.2.0 agent-scoped Responses construction,
  `AgentsClientBuilder.buildAgentScopedOpenAIClient(name)`, is name-only. It cannot pin an inspected version, so a
  separate latest-version metadata/definition read could race the version actually selected for invocation. Konductor
  therefore neither writes layout metadata nor inspects/classifies versions. Legacy and newly created agents use the
  same baked base + append contract, bind by name, and receive no layout mismatch warning. A changed configured base or
  append affects ephemeral requests immediately but requires explicitly creating a new persisted version to affect a
  bound PromptAgent.
- `--no-context-files` applies to both TUI and ACP and removes only the discovered records/rendered block. It does not
  remove base, environment, append, tools, or configuration/trust behavior.

### Trust bootstrap, identity, persistence, and TUI behavior

- Keep real process environment distinct from cwd dotenv. Resolve the config directory only from non-blank
  `--config-dir <path>`/application input, then non-blank real-process `KONDUCTOR_CONFIG_DIR`, then `~/.konductor`.
  Global/project settings and project dotenv never redirect it. Resolve relative overrides against canonical home;
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
  invalidates the whole store. Missing is a valid empty snapshot; unreadable/invalid is an error snapshot that is never
  overwritten as recovery. Without an override TUI offers repair-guided one-run untrusted continuation or quit and ACP
  errors; no-approve may bypass content as untrusted, while approve remains an error.
- The local trust boundary treats the effective user's own processes as trusted and protects against repository path
  redirection, malformed state, crashes, and cooperating writers. Pin the canonical outside-workspace config directory;
  apply reliable POSIX owner/mode and available Windows owner/DACL structural checks without demanding perfect Windows
  analysis; require final store/lock/candidate entries to be regular and opened no-follow; strictly bound store reads to
  1 MiB; reject observed path/type/file-identity changes. Unsupported hard-link count is not itself an error. POSIX and
  Windows follow the explicit validation matrix in the configuration spec.
- Writers acquire a five-second bounded exclusive no-follow **persistent** sibling lock (normal unlock never unlinks
  it), reread strict state under lock, merge unrelated changes, handle identical decisions idempotently, and reject
  opposite same-workspace or changed/invalid state. Write a
  create-new no-follow sibling candidate, force it, reread, and atomically replace with no non-atomic fallback.
- `--approve`/`-a` and `--no-approve`/`-na` are mutually exclusive one-run inputs in TUI and ACP; neither prompts nor
  persists. No-approve forces untrusted and may bypass store content after safe config-dir resolution. Approve overrides
  valid saved/default decisions only after structural and strict store validation; corrupt/unreadable/redirected state
  is actionable error, never silent trust.
- For a valid store without an override, TUI prompts only when trust is unknown and a no-follow presence probe finds a
  gated file. It offers persistent **Trust**, **Trust for this session only**, persistent **Do not trust**, and **Do not
  trust for this session only**, defaulting to session-only untrusted. Persistent Trust takes effect only after a
  successful write; session-only Trust writes nothing. Known decisions do not prompt, and valid unknown workspaces with
  neither gated entry continue untrusted without a write.
- An unreadable, malformed, unsupported, redirected, or otherwise invalid trust store without an override uses a
  dedicated TUI repair screen offering only **Continue untrusted for this run** or **Quit** (default), with no
  store/lock/candidate write. ACP returns an actionable request error. No-approve is the explicit noninteractive bypass;
  approve still errors.
- Plain-text context still loads in every continuing trust state unless `--no-context-files` is set. Global settings,
  real process env, CLI inputs, built-ins, and tools retain operator-controlled eligibility.

### TUI startup session policy

- Canonicalize the launch cwd and resolve the external config directory and `<config-dir>/sessions` root first. Before
  trust/project configuration, credential, context, tools, Foundry runtime, or provider construction, inspect an
  explicit `--resume` header with `loadHeader` and require
  its canonical persisted cwd to equal canonical launch cwd under host semantics. Same Git root is insufficient. A
  missing/invalid cwd or mismatch aborts with both paths and guidance to launch from the session workspace; never
  re-root or construct launch-workspace tools for it.
- `--continue` calls `mostRecentForCwd(canonicalLaunchCwd)` only. It does not search another cwd bucket or global
  recency; no match calls allocation-only `newCandidate` for that cwd. Default startup does the same, and `--name`
  applies only after selection succeeds.
- Header-only inspection is separate from full load and from allocation. For a fresh TUI candidate, complete all local
  trust/configuration/runtime/provider/binding/context/tool validation, then call `persistNew` once immediately before
  publication. Close provisional resources on failure; never create then delete a header. `/new` follows the same rule.
- This cross-cwd rejection is an intentional difference from pi 0.82.1 and is specific to the single-workspace TUI
  startup graph. ACP load remains persisted-cwd-authoritative and builds an isolated graph after reading that cwd.

### ACP behavior

- Resolve trust and effective configuration independently for every authoritative session cwd. `session/new` calls
  `newCandidate`, then completes trust/source resolution, final configuration validation, credential/Foundry runtime
  and provider construction, binding-shape validation, shared path-bearing context assembly, and supported tool-runtime
  construction before one `persistNew`. It closes provisional resources on failure and never creates/deletes rollback
  state. Hosted network creation remains lazy until the first prompt after the local header is durable. `session/load`
  uses persisted cwd and ignores caller/launch cwd. ACP never prompts/writes trust: no-approve forces untrusted; approve
  requires a structurally safe valid/missing store; otherwise saved valid decisions apply, valid unknown is untrusted,
  and error snapshots return actionable request errors.
- New headers under `<config-dir>/sessions` persist UUID, canonical cwd, created time/name, effective model, and exactly
  one binding shape: optional PromptAgent for Prompt v1 or hosted agent + reserved UUID for Hosted v2. Only after all
  preceding local validation succeeds may `persistNew` make the one durable header commit and publish the ACP session.
  A commit failure closes the provisional runtime; every pre-session failure is returned as the `session/new` JSON-RPC
  request error, not through an undefined session diagnostic.
- Load keeps header identity, transcript, model, provider/history kind, PromptAgent (including absent/ephemeral), and
  Hosted binding. Phase one resolves trust and parses eligible fresh sources into a partial candidate without
  defaulting, requiring, or cross-validating fresh model/kind/agent identity. Phase two overlays exact header
  model/kind/binding, then applies runtime defaults, performs final validation, and constructs the credential, Foundry
  runtime, provider, context, and tools. PromptAgent binding remains name-scoped and adds only the shared rendered
  context block + environment to the already baked base + append; it performs no version-layout inspection. Fresh
  identity conflicts cannot replace
  or veto persisted identity; malformed eligible sources, inconsistent headers, missing final runtime requirements,
  and unusable local bindings fail. Exact Hosted binding is revalidated in the finally resolved Foundry project.
- `session/list` canonicalizes its caller cwd and applies the same outside-workspace config-directory check before
  accessing that cwd's session store; it does not prompt for or mutate trust.
- Plain-text global/root-to-cwd context records and the shared renderer apply in all trust states under the same
  race/bound rules and process `--no-context-files` flag.

## Decisions and constraints

- `src/` and tests remain implementation truth; specs describe the intended contract.
- Plain-text instructions are prompt input and can inject instructions. This documented risk is distinct from allowing
  project configuration to change provider, tool, or runtime behavior.
- Trust gates cwd `.env` and project `.konductor/settings.json`; it never gates global operator configuration.
- Trust persistence belongs under the canonical outside-workspace Konductor config directory, never inside the
  repository. The effective user's own processes are inside the local trust boundary.
- `ConfigurationAcpSessionRuntimeFactory` remains the integration boundary for session-specific context, trust,
  configuration, provider, and tool construction.

### Intentional differences from pi 0.82.1

The drift review does not make pi a normative backend contract. Konductor deliberately retains:

1. the nearest non-symlink `.git` marker as the context walk boundary instead of walking to the filesystem root;
2. exact `AGENTS.md` then `CLAUDE.md` names only, without pi's uppercase aliases;
3. canonical workspace-root trust identity instead of cwd/nearest-ancestor trust inheritance;
4. TUI cross-cwd resume rejection instead of rebuilding the interactive application around another cwd; and
5. the Foundry PromptAgent stable/dynamic split: base + configured append + tools are baked, while the shared current
   path-bearing context block + environment is dynamic.

These are product/service constraints, not omissions to “fix” by copying pi behavior. The adopted drift is limited to
provenance-preserving context rendering, complete one-run/session trust controls, explicit config-dir CLI bootstrap, an
implementable local trust-store boundary, and provisional session publication.

## Validation

For the design-only PR, validate packet metadata and local Markdown links/anchors:

```bash
node scripts/validate-docs.mjs
```

During implementation, run focused context/config/ACP tests and then the full suite:

```bash
./mvnw -q test
```

Manually verify all four valid-unknown TUI choices; both one-run overrides in TUI and ACP; corrupt-store repair and
no-approve bypass; approve rejection; CLI/env/default config-dir precedence; same- and cross-cwd `--resume`; cwd-only
`--continue`; and legacy/new PromptAgent bindings with byte-identical rendered context blocks. Verify TUI and ACP
new-session validation failures never publish a header or invoke create-delete rollback, NoOp commit is no I/O, and
Hosted remote creation stays lazy until the first prompt. Include empty/whitespace/path-escaping context records,
symlink aliases, symlinked `.git`, nested/non-Git cwd, relative/nonexistent/symlinked config dirs, malformed/concurrently
changed trust state, dotenv gating, ACP persisted/refreshed fields, no-follow fallback, instruction bounds/races, lock
and atomic-move failures, plus the POSIX/Windows metadata validation matrix in focused automated coverage.

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
