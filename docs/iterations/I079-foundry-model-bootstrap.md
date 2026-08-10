---
id: I079
title: Foundry model bootstrap
issue: https://github.com/jpalvarezl/Konductor/issues/79
created: 2026-07-30
---

# I079 — Foundry Model Bootstrap

## Outcome

A Prompt TUI can connect to an already-selected Foundry project without a locally configured model, resolve one model
deployment before provider/session creation, and then enter the existing runtime with a complete, non-null
`Configuration.model`. Existing explicit-model startup remains discovery-free, Hosted remains model-selection-free,
and ACP remains deterministic and noninteractive.

## Scope

- Split configuration into a bootstrap phase, where a Prompt model may be unresolved, and a final effective
  `Configuration`, where `model` remains non-null.
- Integrate after #26's workspace-trust contract is implemented: use its exact canonical-cwd session selection plus
  workspace-root trust result before project `.env` or `.konductor/settings.json` can contribute a model or any other
  project-owned setting, without defining or implementing a second trust policy here.
- Preserve discovery-free startup when Prompt has a model from an existing resumed/continued session or the normal
  CLI/environment/trusted-project/global settings sources.
- Let a TUI Prompt bootstrap with no such model query the configured project's `ModelDeployment` inventory before
  constructing a provider or creating/mutating a session.
- Give zero, one, many, cancellation, and discovery-failure outcomes explicit localized behavior.
- Reuse the existing SDK-free `CommandOption`/`CommandOptionProvider` model-option boundary for a dedicated
  pre-provider selector; do not expose Azure SDK types to bootstrap UI state.
- Keep ACP noninteractive and discovery-free. Retain CLI, real-process-environment, and global model candidates across
  transport bootstrap; after `session/new` resolves its authoritative cwd/trust, finalize Prompt model precedence with
  eligible trusted cwd dotenv/project settings. `session/load` instead overlays persisted identity. Hosted ACP never
  applies Prompt model-source precedence.
- Keep a bootstrap-selected deployment process-local; ordinary session creation records it through the existing
  session metadata path, but settings and separate selection state are not persisted.

## Non-goals

- Creating model deployments or implying that the Projects `DeploymentsClient` is a management-plane provisioning
  API.
- Foundry project, tenant, subscription, or credential selection.
- Reading a persisted PromptAgent definition to infer its model; PromptAgent startup still needs the same local model
  fallback as ephemeral Prompt in this slice.
- Adding an ACP model-discovery, selection, refresh, or deployment-creation extension.
- Deriving context-window size or other runtime policy from deployment metadata.
- Designing or implementing workspace identity, trust prompts/storage, or unknown-trust policy owned by #26. This
  design PR records the required downstream integration now; implementation remains hard-blocked on #26.
- Changing `/model`, PromptAgent creation/binding, Hosted provisioning, provider switching, or session file format.

## Acceptance

- [x] Bootstrap configuration can represent an unresolved Prompt model, while every configuration passed to
  `FoundryProjectRuntime.createProvider`, `AgentContextFactory`, `AgentLoop`, TUI runtime, or ACP runtime has a
  non-null,
  non-blank model.
- [x] With #26's implemented canonical-cwd/workspace-trust result as an input, project dotenv/settings are loaded only
  when permitted; untrusted/ignored project sources cannot suppress discovery or select a deployment. I079 does not add
  an interim trust store or prompt.
- [x] TUI `--resume` accepts a session only when its persisted canonical cwd equals the canonical launch cwd under host
  semantics. `--continue` selects the most recent session for that exact cwd. A mismatch/corrupt cwd fails
  before project configuration, discovery, provider construction, or writes, so trust and settings are never resolved
  for one workspace while a different workspace is resumed.
- [x] After trusted settings establish the effective TUI agent kind, a resumed or continued session must have that same
  persisted kind. Prompt-over-Hosted and Hosted-over-Prompt both fail before project/catalog construction, discovery,
  provider construction, service operations, session creation, rename, or metadata writes. `--continue` does not skip
  an opposite-kind newest session or silently start new, and this slice performs no session-kind migration.
- [x] A matched resumed/continued TUI session uses its non-blank persisted `modelName` and performs no startup
  discovery; `--model` combined with `--resume`/`--continue` is rejected rather than ignored or persisted implicitly.
  `--continue` with no match becomes an ordinary new-session path, including local-model precedence and discovery when
  unresolved.
- [x] For a new Prompt TUI session, `--model` > real process environment > trusted cwd dotenv > trusted project
  settings > global settings remains authoritative and performs no startup discovery.
- [x] Hosted TUI and ACP startup do not query deployments or show model selection. Before ACP transport startup, only
  explicit process-level `--agent-kind hosted` plus `--model` is rejected. If eligible session-cwd sources make an ACP
  `session/new` Hosted, an explicit `--model` is rejected at method level; ambient process-environment, trusted-project,
  and global model values are ignored, Prompt provenance is skipped, and canonical non-null `hosted` is persisted. ACP
  load overlays persisted identity and does not apply that new-session conflict. A valid persisted Hosted
  binding accepts and preserves any non-blank legacy model value without using it or silently rewriting metadata.
- [x] A missing TUI Prompt model lists only DTOs whose type is `ModelDeployment`, in catalog order, before provider or
  session creation: zero exits with localized setup/rerun guidance; one is selected automatically and reported; many
  opens a cancellable pre-provider selector. The selector keeps at most the first 2,000 options and visibly reports
  catalog truncation plus the explicit-`--model` escape hatch.
- [x] Selector cancellation is a clean exit and performs no settings/session writes, session creation, provider
  construction, or PromptAgent/Hosted service operation.
- [x] Zero and discovery failure are printed as localized, sanitized, actionable stderr output after any bootstrap
  terminal is restored; auto-selection is added to the TUI's visible startup messages. No terminal outcome that
  requires durable reporting exists only in an alternate-screen/transient selector state. The truncation notice is
  informational selector copy: it stays visible while selection is active but need not survive selection or
  cancellation. Existing in-session `/model` discovery-failure fallback remains unchanged.
- [x] A bound/configured PromptAgent does not bypass local model resolution or trigger agent-definition metadata
  inference.
- [x] ACP process bootstrap reads model candidates only from CLI, the real process environment, and global settings; it
  never reads launch-cwd project inputs and does not require a Prompt process default before transport. After #26
  resolves a Prompt `session/new` cwd, its model is finalized as CLI > real process environment > trusted cwd dotenv >
  trusted project settings > global settings. Resolution returns a process-local source tag and writes only the value
  unchanged to the new Prompt header. Finalization and all request-local runtime/provider/binding validation happen
  before one `persistNew`; a method error leaves no listable/loadable local session or remote Hosted resource.
- [x] ACP never discovers or prompts. `session/load` derives kind from the strict persisted header, parses eligible
  fresh sources from the persisted cwd, overlays exact persisted model/kind/binding, and only then validates/builds;
  process configuration cannot veto persisted identity. Missing/blank/corrupt model metadata receives a sanitized
  method-level failure with no ambient fallback, discovery, runtime publication, or rewrite. Hosted loads preserve a
  non-blank compatibility value and reconnect their exact persisted binding.
- [x] `CliTest`, `FoundryModelBootstrapTest`, focused config/catalog/TUI/ACP tests, the full suite, package,
  documentation validation, and diff checks pass.

## Context pack

Read these exact headings and symbols first. Expand only if source contradicts them.

### Specifications

- [`Architecture — System layers`](../spec/architecture.md#system-layers) and
  [`Startup composition`](../spec/architecture.md#startup-composition).
- [`Configuration — Authentication`](../spec/configuration.md#authentication),
  [`Settings file and project dotenv`](../spec/configuration.md#settings-file-and-project-dotenv),
  [`Precedence`](../spec/configuration.md#precedence), and
  [`Startup model resolution`](../spec/configuration.md#startup-model-resolution).
- [`Providers — Selection & construction`](../spec/providers.md#selection--construction) and
  [`Client construction & auth`](../spec/providers.md#client-construction--auth-shared).
- [`TUI — Startup model bootstrap`](../spec/tui.md#startup-model-bootstrap) and
  [`Localized copy`](../spec/tui.md#localized-copy).
- [`ACP — Run it`](../spec/acp.md#run-it) and
  [`How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor).
- [`Sessions — Lifecycle`](../spec/sessions.md#lifecycle),
  [`Entry model & on-disk schema`](../spec/sessions.md#entry-model--on-disk-schema), and
  [`Hosted bindings & resume`](../spec/sessions.md#hosted-bindings--resume).
- [`Hosted agents — Configuration`](../spec/hosted-agents.md#configuration) and
  [`Lifecycle & ownership`](../spec/hosted-agents.md#lifecycle--ownership).

### Source entry points

- `src/main/kotlin/com/konductor/Main.kt` — `runKonductor`, initial-session lookup, project/provider/session ordering,
  durable bootstrap reporting, exit handling, and resource ownership.
- `src/main/kotlin/com/konductor/config/Configuration.kt` — `Configuration.load`, settings merge, required model, and
  final non-null `Configuration.model`.
- `src/main/kotlin/com/konductor/Cli.kt` — `CliOptions`, frontend mode, model/session flags, and localized failures.
- `src/main/kotlin/com/konductor/foundry/project/FoundryProjectRuntime.kt` — project identity/catalog construction and
  the `createProvider(Configuration)` boundary.
- `src/main/kotlin/com/konductor/foundry/project/deployment/FoundryDeploymentCatalog.kt` — SDK-free deployment DTO,
  `ModelDeployment` discriminator, canonical ordering, and synchronous Projects SDK adapter.
- `src/main/kotlin/com/konductor/conversation/CommandOptions.kt` — `CommandOption`, `CommandOptionProvider`, and
  `FoundryModelOptionProvider` reusable bootstrap option boundary.
- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` — final-configuration requirement and per-session model
  copy.
- `src/main/kotlin/com/konductor/tui/palette/CommandPaletteController.kt` — `PaletteKey` and bounded option-selection
  state transitions to adapt without command staging.
- `src/main/kotlin/com/konductor/tui/palette/CommandPaletteCoordinator.kt` — `launchLoad` cancellation/generation
  pattern.
- `src/main/kotlin/com/konductor/tui/component/CommandPaletteView.kt` — stateless bounded selector rendering pattern.
- `src/main/kotlin/com/konductor/i18n/AppStrings.kt` and root message bundle — semantic startup copy and sanitized
  presentation.

### Tests

- `src/test/kotlin/com/konductor/CliTest.kt` — `--model` conflicts for resume/continue and effective Hosted mode,
  including pre-transport ACP process startup.
- `src/test/kotlin/com/konductor/FoundryModelBootstrapTest.kt` — add this named entry-point seam test for startup
  ordering, resume identity and both cross-kind mismatch directions, continue-no-match/opposite-kind-latest behavior,
  zero/one/many/truncation/failure output, and side-effect absence.
- `src/test/kotlin/com/konductor/config/ConfigurationTest.kt` — Prompt-only ACP model candidate retention, Hosted
  startup without a process-default model, and canonical Hosted finalization despite ambient model values.
- `src/test/kotlin/com/konductor/foundry/project/FoundryProjectRuntimeTest.kt`
- `src/test/kotlin/com/konductor/conversation/CommandOptionsTest.kt`
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — final per-session configuration, Prompt-only
  model provenance, canonical Hosted model finalization, and provider construction/cleanup boundaries.
- `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — Prompt `session/new` source precedence and
  trusted-project model persistence; Hosted `session/new` ignoring ambient model sources and persisting the canonical
  placeholder; no-orphan failures; and `session/load` persisted-identity overlay—including an opposite fresh process
  kind not vetoing the header—before final validation, while internally inconsistent cross-kind headers still fail.
- `src/test/kotlin/com/konductor/session/SessionCodecTest.kt` — strict kind derivation, Hosted
  placeholder/legacy-value preservation, and blank/corrupt model rejection.
- `src/test/kotlin/com/konductor/session/JsonlSessionStoreTest.kt` — `newCandidate` remains invisible and one atomic
  `persistNew` publication leaves no listable or loadable file after failure.
- `src/test/kotlin/com/konductor/tui/palette/CommandPaletteControllerTest.kt` and
  `CommandPaletteCoordinatorTest.kt` for selector behavior patterns only.
- `src/test/kotlin/com/konductor/i18n/AppStringsTest.kt` — semantic cross-kind, ACP configuration, bootstrap terminal,
  and informational truncation copy.

### Targeted searches

```bash
rg -n "Configuration\.load|FoundryProjectRuntime\.create|createProvider|resolveInitialSession|sessionStore" \
  src/main/kotlin/com/konductor src/test/kotlin/com/konductor
rg -n "ENV_MODEL_NAME|provider\.model|modelOverride|defaultModelName|session\.modelName" \
  src/main/kotlin/com/konductor src/test/kotlin/com/konductor
rg -n "FOUNDRY_MODEL_DEPLOYMENT_TYPE|FoundryModelOptionProvider|CommandOption|CommandOptionProvider" \
  src/main/kotlin/com/konductor src/test/kotlin/com/konductor
rg -n "configurationError|fatalError|sanitize|AppStrings|TuiExitCode" \
  src/main/kotlin/com/konductor src/test/kotlin/com/konductor
```

## Decisions and constraints

### Two phases and ordering

#26's trust-resolution implementation is a **hard sequencing prerequisite** for I079 implementation. If it is not
available, implementation stops rather than reading project settings eagerly or absorbing trust storage/prompt policy
into this slice. That dependency does not block accepting or merging this design-only PR: this packet deliberately
fixes I079's downstream contract while #26 owns and completes the identity/trust mechanism. GitHub issue #79 owns
coordination of the implementation prerequisite.

The bootstrap value owns inputs needed before execution-target resolution: frontend mode, cwd/trust result, effective
settings, agent kind, project endpoint, credential, and an optional local model. It is not valid provider input. The
finalization step supplies a non-blank model and produces the existing effective `Configuration`; downstream types do
not become nullable.

TUI startup order is:

1. resolve locale/help/version and parse CLI;
2. canonicalize the launch cwd, then identify a requested TUI resume/continue session without renaming or publishing
   one;
3. require an explicit resume's persisted canonical cwd to equal the canonical launch cwd under host semantics;
   `--continue` searches only that exact cwd;
4. resolve workspace-root trust for that cwd, then merge global and only permitted cwd dotenv/project settings;
5. reject a selected session whose strict persisted kind differs from the effective configured kind;
6. build the TUI Foundry project/catalog boundary from endpoint + credential;
7. resolve the model according to the rules below; and only then
8. create the final `Configuration`, provider, binding, context/loop, and frontend, publishing a provisional new
   candidate exactly once when applicable.

A missing/corrupt persisted cwd or canonical-cwd mismatch fails at step 3. Trust and project configuration,
tool/context roots, session selection, and the eventual provider therefore all refer to the same canonical cwd and
workspace root. I079 consumes #26's path and trust results; it does not redefine canonicalization, workspace identity,
symlink, moved-workspace, or trust persistence rules.

Persisted kind is derived strictly from the session schema/binding: Prompt v1 is Prompt and a valid complete Hosted v2
binding is Hosted. After eligible settings establish effective TUI kind, step 5 rejects Prompt-over-Hosted and
Hosted-over-Prompt symmetrically. For `--continue`, the newest exact-cwd session remains the selected candidate;
startup neither skips it in search of an older same-kind session nor turns the mismatch into a new session. There is no
kind migration, provider substitution, or header rewrite in this slice.

Steps 2–7 may allocate a non-durable `newCandidate` and read session/trust/source/catalog state. They must not call
`persistNew`, rename/update an accepted session, write selection/settings state, construct a provider, or perform
agent-kind service operations. A selected deployment is an in-memory bootstrap result. If startup later publishes an
ordinary new session, its existing header records `modelName`; no new persistence field is introduced.

### Resolution rules

- **Hosted:** bypass model resolution and deployment inventory entirely. Before ACP transport startup, only explicit
  process-level `--agent-kind hosted` plus `--model` is a localized CLI conflict. Hosted ACP does not require a
  process-default model. Its `session/new` path merges eligible sources to resolve final kind, rejects explicit
  `--model` if that kind is Hosted, then skips Prompt provenance and ignores ambient model values. `session/load`
  overlays persisted identity and does not apply that new-session conflict. Hosted finalization supplies the canonical
  stable `hosted` placeholder required by shared non-null shapes and writes it to every new Hosted header; providers must
  not interpret
  it as a client-selected model. For compatibility, a valid persisted Hosted binding may contain either that
  placeholder or any legacy non-blank model value. Resume/load preserves that opaque value exactly and does not rewrite
  the header merely to canonicalize it. A blank/missing value remains corrupt metadata. The binding fields/version—not
  the model string—identify Hosted.
- **Prompt TUI resume/continue:** after identity/trust/settings resolution, the selected session must be Prompt; a
  Hosted binding is an early localized mismatch error. Conversely, effective Hosted cannot resume/continue a Prompt
  header. For a kind-matched Prompt session, a valid non-blank persisted `Session.modelName` is the execution target.
  Ambient environment/settings defaults do not rewrite it. Combining `--model` with `--resume`/`--continue` is a
  localized CLI conflict, not a silent ignore or metadata rewrite. A blank persisted model is invalid session metadata
  and fails without discovery; an explicit future "resume with override" or kind migration is outside this slice. If
  `--continue` finds no session for the canonical launch cwd, it becomes the ordinary new Prompt path: local precedence
  applies and an unresolved result performs startup inventory discovery.
- **Prompt TUI new session:** use the effective local model when present. Its source precedence is `--model` > real
  process `FOUNDRY_MODEL_NAME` > trusted cwd dotenv > trusted project `provider.model` > global `provider.model`. Any
  such value skips inventory lookup and validation, preserving current startup and leaving the first service request
  authoritative.
- **Prompt TUI unresolved:** query once for `ModelDeployment` DTOs. Zero is an actionable non-zero setup exit; one is
  auto-selected with a localized report; many are presented in canonical catalog order. Esc/cancel exits successfully
  without side effects. There is no in-process refresh/create flow; guidance tells the user how to configure/deploy and
  rerun.
- **PromptAgent:** a configured or resumed PromptAgent binding does not supply a local model. The local fallback is
  still needed by current shared context/session/compaction shapes, even though the bound service definition owns the
  actual per-turn model and rejects a request-level model.
- **Prompt ACP process bootstrap:** ACP never overlays a launch-cwd `.env`, reads launch-cwd project settings, or treats
  launch cwd as a session workspace. It may open the protocol without a process-default model and retains separate CLI,
  process-environment, and global model candidates so a later trusted session cwd can supply the missing value or
  outrank only the global layer.
- **Prompt ACP `session/new`:** allocate `newCandidate`, resolve #26 canonical cwd/trust, and read only eligible trusted
  cwd dotenv/project settings. Then finalize the request model as `--model` > real `FOUNDRY_MODEL_NAME` > trusted cwd
  dotenv > trusted project `provider.model` > global `provider.model`. This precedence and its provenance are
  Prompt-only. Untrusted/ignored project input contributes nothing. Resolution returns `value` plus one process-local
  source tag: `CLI`, `PROCESS_ENVIRONMENT`, `TRUSTED_SESSION_PROJECT`, or `GLOBAL`; the trusted-project tag covers
  either gated project source. The tag is not persisted; only `value` enters final `Configuration` and the Prompt
  header. Common trust/source resolution, finalization, and all request-local runtime/provider/binding/context/tool
  validation precede exactly one `persistNew`; after explicit-model conflict validation, Hosted ignores ambient model
  candidates and uses `hosted`. Any failure closes provisional resources and leaves no accepted local header. Hosted remote creation remains lazy until the first
  prompt, so no remote orphan exists. Create-then-delete compensation is prohibited.
- **ACP `session/load`:** strict header decoding supplies the authoritative persisted cwd and identity. Resolve trust
  there, parse eligible fresh sources into a partial candidate without defaulting or cross-validating fresh identity,
  then overlay the exact persisted model, provider/history kind, and Prompt/Hosted binding before final validation and
  runtime construction. Fresh process/project kind cannot veto or replace persisted kind. An internally inconsistent
  cross-kind header/binding, unusable binding, or missing/blank model is a sanitized method-level error. A loaded Prompt
  uses its persisted non-blank model with no ambient fallback or discovery; a loaded Hosted binding preserves its
  non-blank compatibility value without canonicalizing metadata. No failure publishes a runtime or rewrites the header.

The conditional missing-model lookup is the only startup discovery added here. Existing explicit-model startup remains
free of deployment calls, and in-session `/model list`/validation retains its nonfatal discovery-outage behavior.

### Presentation and failure policy

The many-deployment screen is a dedicated pre-provider TUI state, not `TuiApp`, `AgentLoop`, a slash-command dispatch,
or a hidden temporary session. It may reuse bounded filtering/navigation/rendering patterns and the SDK-free option DTO,
but Enter returns the exact deployment value directly to bootstrap finalization rather than staging `/model ...`.

The complete filtered catalog determines zero/one/many. For many, the selector admits only the first 2,000 deployment
options in canonical catalog order, matching the existing fuzzy matcher's inspected-candidate bound (and its 100-result
render bound). If more exist, localized visible copy reports `shown/total`, says later entries are omitted from this
run, and points to explicit `--model <deployment>`; omitted options cannot become selectable through filtering. Tests
cover the exact boundary and truncation notice.

Auto-selection, empty-project guidance, cancellation, and failures use semantic `AppStrings` copy. Deployment names
remain raw stable identifiers. After any bootstrap terminal/alternate screen is restored, zero and failure print their
localized actionable user message to stderr and return non-zero. Auto-selection is handed into the subsequently
created TUI as a startup/system message that remains visible in `AppState.messages` for that TUI lifetime but is not a
conversation entry or session metadata.

The truncation notice is transient informational UI, not a terminal outcome: it remains continuously visible while the
bounded selector is active, including after filtering/navigation, but need not be repeated after selection or clean
cancellation. In contrast, any terminal outcome that requires the user to act or explains a non-zero exit—currently
zero inventory and discovery failure—must be emitted after terminal restoration and cannot exist only in selector
state. A discovery failure may additionally be logged diagnostically under existing stderr logging policy, but
user-facing copy contains a sanitized category plus actions (verify endpoint/identity/access, set a model, and rerun),
never raw exception text, response bodies, credentials, or endpoint query details. ACP stdout remains untouched until
and during protocol operation.

## Validation

During implementation, run focused tests first, then the complete checks:

```bash
./mvnw -q -Dtest=CliTest,FoundryModelBootstrapTest,ConfigurationTest,FoundryProjectRuntimeTest,\
CommandOptionsTest,AcpSessionRuntimeFactoryTest,KonductorAgentSessionTest,SessionCodecTest,JsonlSessionStoreTest,\
AppStringsTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

Manual validation covers Prompt TUI startup with explicit, zero, one, many, and more than 2,000 deployments;
cancellation; transient truncation versus durable terminal reporting; resume canonical-cwd mismatch; both TUI
cross-kind mismatch directions; `--continue` with no match and with an opposite-kind newest match; resume without
ambient model; a configured PromptAgent without a local model; Hosted explicit-model rejection and legacy model
preservation; Prompt ACP with/without a process model, every Prompt `session/new` model-source layer, and trusted
project over global; Hosted ACP without a process model and with ambient process/project/global model values still
persisting canonical `hosted`; ACP load persisted-identity overlay and inconsistent cross-kind header rejection;
no-orphan preparation failures; plus corrupt loaded metadata. Verify catalog/provider/session factory/store spies remain
untouched at the specified early-error boundaries and prepared resources close on failed ACP new.

## Completion

Implemented in [PR #118](https://github.com/jpalvarezl/Konductor/pull/118). Validation covered focused bootstrap,
configuration, catalog, selector, CLI, and ACP tests; the full Maven test and package lifecycle; documentation routes;
and diff checks. The final implementation keeps provisioning, project selection, ACP discovery extensions, PromptAgent
metadata inference, and model-metadata context sizing out of scope.
