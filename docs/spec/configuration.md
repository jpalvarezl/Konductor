# Configuration

How Konductor is configured: environment variables, authentication, settings, and pre-provider model resolution. A
first Prompt TUI run needs a project endpoint and signed-in Azure identity; it can resolve a missing model from that
project before constructing a provider. Prompt ACP remains noninteractive; each `session/new` must resolve a local
model before runtime and session publication.

> Code blocks are illustrative design sketches, not committed implementation.

## Environment variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | yes | Foundry project endpoint: `https://{resource}.ai.azure.com/api/projects/{project}` |
| `FOUNDRY_MODEL_NAME` | no (Prompt TUI); Prompt ACP `session/new` must resolve a local model from eligible sources | Model deployment name, e.g. `gpt-5-mini`; skips startup discovery |
| `FOUNDRY_AGENT_CONTAINER_IMAGE` | Hosted only | Container image for hosted-agent sessions |
| `KONDUCTOR_HOSTED_AGENT_NAME` | Hosted | Named hosted agent to deploy/select ([hosted-agents.md](hosted-agents.md)) |
| `KONDUCTOR_PROMPT_AGENT_NAME` | opt-in Prompt | Optionally bind a persisted **PromptAgent** ([providers.md](providers.md#persisted-prompt-agents-promptagent)) |
| `KONDUCTOR_CONFIG_DIR` | no | Real-process-only config-dir override after `--config-dir` (default `~/.konductor`) |
| `KONDUCTOR_LOCALE` | no | Frontend locale as a BCP-47 tag, e.g. `en`, `es`, or `fr-CA` |

## Authentication

Konductor authenticates with **Entra ID** via `DefaultAzureCredential`; the SDK applies the AAD scope
`https://ai.azure.com/.default`. After TUI trust and eligible-source parsing, a bootstrap configuration containing the
resolved endpoint and credential can construct `FoundryProjectRuntime`'s typed catalogs while a Prompt model is still
unresolved. Only that case queries deployments before provider/session publication. A locally resolved Prompt model and
all Hosted runs skip startup discovery. Each ACP session instead resolves its authoritative cwd, trust, and final
configuration before constructing a session-owned project runtime; ACP exposes no deployment-discovery protocol.

After startup, the TUI also queries those SDK-free catalogs for `/model list`, `/model <deployment>`, or
`/connections`. Those read-only failures keep the current session usable. If an explicit `/model <name>` request cannot
be validated because discovery failed, Konductor switches with a warning; if discovery proves the name absent, it
rejects the switch and points to `/model list` or deployment setup. This nonfatal command fallback differs from an
unresolved startup model, where catalog failure leaves no executable target and exits before provider/session
publication. A constructed runtime's project endpoint and credential are immutable.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
```

`DefaultAzureCredential` tries, in order: environment service-principal vars, managed identity, the Azure CLI
(`az login`), Azure Developer CLI, etc. For local dev, `az login` to the tenant/subscription that owns the Foundry
project is the simplest path. No API keys are stored by Konductor.

## Config directory

The effective user config directory contains global settings, sessions, global context, and the trust store. Resolve it
without consulting any project-controlled file:

1. use non-blank `--config-dir <path>` (or the equivalent application API input), when present;
2. otherwise use non-blank `KONDUCTOR_CONFIG_DIR` from the **real process environment** (`System.getenv`), not from a
   dotenv overlay; or
3. default to `~/.konductor`.

A present CLI/application value must contain a path; blank is a CLI/input error rather than a request to fall through.
A blank process variable is treated as absent. This same process-wide bootstrap applies before either the TUI or ACP
frontend starts and fixes global settings, sessions, global context, trust-store, lock, and temporary-file locations.
Global settings do not contain a self-relocating config-directory field and are read only after this path is fixed.
Project `.env` and project settings must never supply or redirect `KONDUCTOR_CONFIG_DIR`, even after trust. ACP startup
therefore retains the real process environment separately rather than passing one indistinguishable dotenv-overlay
lookup into configuration.

Resolve a relative override against the canonical user home, never against the launch or session cwd. For an existing
path, follow links and require its canonical target to be a directory. For a nonexistent path, canonicalize its nearest
existing ancestor, append the normalized missing suffix, create it without following a substituted path entry, and
canonicalize it again. A symlinked config directory is therefore identified by its target; dangling links, a
non-directory existing target, and a path that changes during creation are errors.

For each TUI or ACP workspace, compare canonical paths using host filesystem semantics and reject a config directory
that equals or is below the canonical workspace root. Root and config-path resolution therefore precede opening global
settings or the trust store for that workspace, and the check repeats after directory creation/canonicalization. Pin the
accepted canonical config-directory path and, where the filesystem exposes one, its directory file key for the
remainder of that resolution; trust operations revalidate both before and after each access. A TUI run fails with an
actionable config-path error; ACP rejects the affected `session/new`/`session/load` (writing no new header) or
`session/list` request, while the protocol process remains available. This strict outcome also applies when the workspace root is the user's home
(for example, a home-directory dotfiles repository): the operator must select a directory outside that root. Neither a
relative path, a nonexistent path, nor a symlink may place `workspace-trust.json` in the workspace.

## Settings file and project dotenv

Optional JSON lives at `<config-dir>/settings.json` (global) and `<canonical-cwd>/.konductor/settings.json` (project).
The optional project dotenv is `<canonical-cwd>/.env`. Both project files are project-controlled runtime configuration
and share one trust gate; neither file's contents may be opened, parsed, decoded, or used before the decision. A
no-follow directory-entry presence probe is allowed solely to decide whether the TUI needs to ask. Project configuration
paths must resolve to regular files inside the canonical workspace root; an indeterminate probe or invalid selected
file fails closed after trust and never causes contents to be read before trust.

Use two distinct environment sources:

- **real process environment** — operator-controlled values inherited by the JVM; and
- **project dotenv** — parsed values from the trusted cwd `.env`, which only fill keys absent or blank in the real
  process environment.

For unknown or untrusted workspaces, Konductor behaves as if both project files do not exist: it does not parse them,
report content errors, or use any field. For a trusted workspace, it validates and reads both, then resolves the
effective configuration. A project dotenv key or project-settings field named `KONDUCTOR_CONFIG_DIR`/`configDir` is always ignored (and the
unsupported settings field may be rejected by strict schema validation) as described above.
Plain-text `AGENTS.md`/`CLAUDE.md` context remains governed by its separate always-load policy.

Each eligible project file has a **256 KiB** byte limit and uses the same implementable target-stability property as
context files. Canonicalize the selected literal entry, require its target to be a regular file contained in the
canonical workspace root, record no-follow attributes, then boundedly open the canonical target itself with
`NOFOLLOW_LINKS` and read at most limit + 1 actual bytes. After the read, canonicalize the selected entry again and
compare the pre/post canonical target plus every stable attribute/file key available on that filesystem (for example
size and stable creation/last-modified timestamps, but not access time); repeat containment and regular-file checks, and
fail on any observed change. Use `SecureDirectoryStream` or an equivalent
secure-directory/handle primitive for the traversal and open whenever the platform supplies it. Metadata size is only
an early-rejection hint, truncation is not success, and decoding is strict UTF-8 before dotenv/JSON parsing.

The fallback property deliberately does not promise detection of an adversarial ABA replacement that restores the same
canonical target and observable attributes on a platform without a stronger primitive. Such an unobservable race is
outside the guarantee; observed changes always fail, and platforms that provide stronger secure-directory operations
must use them.

```json
{
  "provider": { "agentKind": "prompt", "model": "gpt-5-mini", "promptAgentName": null, "hostedAgentName": null, "hostedAgentContainerImage": null, "temperature": 0.2, "maxToolIterations": 30 },
  "tools": { "allow": ["read", "ls", "find", "grep", "bash", "write", "edit"], "maxOutputBytes": 16384 },
  "compaction": { "enabled": true, "reserveTokens": 16384, "keepRecentTokens": 20000, "contextWindow": 128000 },
  "systemPromptAppend": null
}
```

Configuration is resolved in two shapes after source eligibility and trust are known. The TUI bootstrap shape may lack
a Prompt model and is sufficient only for project/catalog composition. ACP may likewise retain a partial candidate
until a new session's sources or a loaded session's persisted overlay are complete, but it does not expose catalog
selection. Finalization requires a non-blank model and returns the effective `Configuration` used by providers,
contexts, loops, and frontends; downstream code does not make `Configuration.model` nullable.

```kotlin
data class BootstrapConfig(
    val projectEndpoint: String,
    val model: String?,
    val agentKind: AgentKind = AgentKind.Prompt,
    // remaining effective settings
)

data class Configuration(
    val projectEndpoint: String,
    val model: String,
    val agentKind: AgentKind = AgentKind.Prompt,
    val promptAgentName: String? = null,   // opt-in persisted PromptAgent (Prompt)
    val hostedAgentName: String? = null,   // named hosted agent to deploy/select (Hosted)
    val hostedAgentContainerImage: String? = null,
    val temperature: Double? = null,
    val toolAllow: Set<String>? = null,
    val maxToolIterations: Int = 30,      // cap on tool-call rounds per turn (Prompt loop convergence guard)
    val compaction: CompactionSettings = CompactionSettings(),
    val systemPromptOverride: String? = null,
    val systemPromptAppend: String? = null,
)
```

### Workspace identity and trust store

Trust uses the same nearest-Git-root rule as context discovery. Starting at canonical cwd, inspect each literal `.git`
entry without following its final link; the nearest non-symlink regular file or directory marks the root. A `.git`
symlink never marks or redirects a workspace. The root's absolute canonical path string is the persisted identity. A
symlink alias therefore shares trust with its target; moving or copying a workspace produces a new identity and returns
to unknown trust. The decision applies to every cwd in that workspace root; nested repositories have their own nearest
root and identity. This workspace-root identity is an intentional Konductor difference from pi 0.82.1's cwd/ancestor
trust entries.

Decisions are stored outside the workspace at `<config-dir>/workspace-trust.json` using this strict v1 document:

```json
{
  "version": 1,
  "workspaces": {
    "/canonical/path/to/workspace": "trusted",
    "/canonical/path/to/another": "untrusted"
  }
}
```

A valid v1 store is one UTF-8 JSON object with exactly the `version` and `workspaces` members, no duplicate member names
or trailing JSON, integer `version` equal to `1`, and a `workspaces` object. Every workspace key is a non-empty absolute
lexically normalized host path and every value is exactly `"trusted"` or `"untrusted"`. Keys must also be pairwise
unique under the host path provider's equality semantics after parsing and lexical normalization, not merely distinct
JSON strings; for example, case-only aliases cannot coexist where the host path provider is case-insensitive. Lookup,
duplicate detection, and insertion use that same host-provided `Path` comparator rather than a raw string map or
Konductor-invented case folding. The writer always inserts the current canonical root string; validation cannot require
old keys to resolve because moved-workspace entries are retained and may no longer exist. If the host provider cannot
parse or compare every stored key unambiguously, the store is invalid. Missing members, nulls, wrong types, unknown
top-level members, invalid or host-duplicate paths, invalid decisions, malformed UTF-8/JSON, and unsupported versions
invalidate the **whole** store; entries are never recovered piecemeal.

A missing store is a valid empty snapshot and every workspace is unknown. An unreadable, insecure, or invalid store is
a distinct **error snapshot**: no saved decision is usable, an actionable diagnostic identifies the file and problem,
and Konductor never overwrites it as recovery. Project files can neither select nor write the store. Error snapshots
do not fall through to the ordinary valid-unknown prompt described below.

The trust store enforces a local-agent boundary rather than claiming protection from the operator's own account. The
canonical config directory must remain outside the canonical workspace root and is pinned and revalidated as described
above. Where reliable host metadata exists, its directory ownership/write-access checks are part of structural safety:
on POSIX require the effective user owner and reject group/other write access; on Windows reject a clearly unrelated
owner/write grant when the available owner/DACL view can establish it, without requiring perfect effective-ACL proof.
Files writable by the effective local user—and hostile processes running as that same user—are inside the trust
boundary. The contract protects against repository-controlled path redirection, malformed state, crashes, and
cooperating Konductor writers; compromise of the local account is out of scope.

Every initial read, under-lock reread, and pre-replace reread of `workspace-trust.json` is a bounded no-follow operation
against that pinned directory. Definite no-follow absence is the only missing-store result. Otherwise the final literal
entry must be a regular file, not a symlink/reparse-point redirection, and must open with `NOFOLLOW_LINKS`
(directory-relative when the host supports it). Read at most **1 MiB + 1 byte**, reject an actual count above 1 MiB,
strict-decode UTF-8, strictly parse the complete document, and revalidate the directory, child path/type, and available
stable attributes/file key after reading. Any observed substitution or change is an error snapshot. As with context
reads, the portable fallback does not claim to detect an unobservable same-attributes ABA race.

Owner, mode, ACL, file-key, and link metadata strengthen diagnostics and rejection only where the host exposes them
reliably. The required cross-platform validation matrix is:

| Host | Required behavior |
|------|-------------------|
| POSIX with Unix attributes | Require effective-user ownership and reject group/other write access on the pinned config directory. For final children, reject a symlink/non-regular entry, owner mismatch, unsafe group/other write access, observed path/file-key substitution, and link count greater than one when `unix:nlink` is available. |
| Windows/NTFS | Reject config-directory or child path redirection and observed canonical-path/file-identity substitution. Final children must not be reparse/symlink or non-regular entries. Inspect directory/child owner, DACL, and link metadata when the JVM exposes reliable facts and reject a clearly unsafe result, but inability to prove a perfect effective ACL or obtain a hard-link count is not by itself an error. |
| Other/limited provider | Enforce canonical outside-workspace directory containment, literal-child no-follow regular-file opens, bounds, strict parsing, and observed-change rejection; report unavailable strengthening metadata diagnostically. |

Thus an observed hard link or clearly unsafe ownership/permission result can be rejected, but unsupported hard-link
counts or incomplete Windows ACL analysis do not disable trust. There is no universal ownership, private-mode, or
anti-hard-link proof prerequisite that a supported JVM/filesystem cannot implement.

All writers coordinate through an exclusive OS lock on `<config-dir>/workspace-trust.lock`, acquired within five
seconds using a monotonic deadline. The lock entry is persistent across releases: create it with create-new semantics
only when definitely absent; otherwise validate and open the existing final no-follow regular entry relative to the
pinned directory. Normal unlock closes/releases the OS lock but never unlinks the entry, preventing writers from
locking different replacement inodes. An existing symlink or other type is never followed. The TUI does not hold the
lock while asking. Under the lock, reread and strictly validate the latest store with the same bounded checks,
then:

- merge unrelated valid workspace updates into the latest snapshot;
- accept an identical decision for this workspace idempotently;
- reject an opposite same-workspace decision as a stale conflict; and
- reject unreadable, malformed, structurally unsafe, or changed-again state without replacing it.

A write serializes canonical v1 JSON to an unpredictable create-new sibling candidate in the pinned directory. The
candidate's final entry must be regular and opened no-follow; write all bytes and force the file before close. Reread
the accepted store immediately before publication, then perform a same-filesystem atomic replace while the lock is
held. There is no non-atomic fallback. Candidate cleanup is best effort. Timeout, persistence failure, or conflict never
enables project `.env` or project settings after a persistent Trust choice. The lock/reread/merge/forced-candidate/
atomic-replace sequence coordinates Konductor writers; an uncooperative same-user process remains inside the local
trust boundary.

`--approve`/`-a` and `--no-approve`/`-na` are mutually exclusive process/application inputs available in both TUI and
ACP modes. They decide every workspace for that process only, are never persisted, and suppress the normal trust prompt:

- `--no-approve` forces untrusted. After config-directory canonicalization, outside-workspace containment, and required
  reliable structural directory checks, trust-store content need not be read and gated project files are never opened.
  It can therefore provide a safe one-run bypass when the store is corrupt or otherwise unusable, without hiding the
  problem by trusting the project.
- `--approve` requests trusted and overrides a decision from a valid store, including a saved untrusted decision. It
  may take effect only after config-directory and trust-store structural safety plus strict store validity have been
  established; a missing store is a valid empty snapshot. An unreadable, malformed, redirected, or structurally unsafe
  store is an actionable startup/request error, never silently trusted. This flag neither repairs nor writes the store.

Without an override, a valid known workspace decision applies. For a valid unknown workspace, the TUI asks only when a
no-follow presence probe definitely finds `<canonical-cwd>/.env` or
`<canonical-cwd>/.konductor/settings.json`. The normal prompt names the canonical workspace root and offers **Trust**
(persist), **Trust for this session only**, **Do not trust** (persist), and **Do not trust for this session only**; the
default is the session-only untrusted choice. Persistent Trust enables project files only after a successful store
write. Session-only Trust enables them in memory without any trust-store/lock/candidate write. Either untrusted choice
opens neither project file; a failed persistent untrusted write is warned but remains untrusted for the run. With no
gated entry, valid unknown trust performs no prompt or write and continues untrusted for project configuration.
`--no-context-files` does not suppress this independent decision.

An error snapshot without an override shows a dedicated TUI repair screen before effective configuration or runtime
construction. It identifies `<config-dir>/workspace-trust.json`, explains the failed parse/read/path or available
security-metadata check, and tells the operator to back up and repair/remove invalid contents or repair the config path,
then restart. It offers only **Continue untrusted for this run** and **Quit**, defaulting to Quit. Continue opens neither
gated file and performs no trust-store/lock/candidate write; Quit exits before credential, Foundry runtime/provider,
context, or tool construction. The screen appears even without gated entries. `--approve` turns this condition into the
actionable noninteractive error above, while `--no-approve` bypasses store reading and continues untrusted without a
prompt.

ACP is always noninteractive and never writes trust. For each session cwd it applies the process override first; with
no override it uses a valid saved decision, treats valid unknown as untrusted, and returns an actionable request error
for an error snapshot. Plain-text context loading remains independent of every trust outcome.

## Precedence

For sources that are eligible, highest wins:

```
CLI flags
  > real process environment
  > trusted cwd .env
  > trusted project settings.json
  > global settings.json
  > built-in defaults
```

Project dotenv only fills gaps in the real process environment, but its resulting values outrank settings as shown.
For untrusted or unknown workspaces, both project sources are excluded before precedence is applied and cannot block
fallback to global settings or defaults. Bootstrap config-directory resolution is the narrower exception defined
[above](#config-directory): project sources never participate. ACP `session/load` has one further identity exception:
eligible sources are first parsed into a partial fresh candidate without defaulting or validating persisted identity
fields; persisted model, provider/history kind, and Prompt/Hosted binding are then overlaid before defaults and final
cross-field validation. Those header fields supersede current CLI, environment, and settings values; all runtime-only
fields still use this precedence.

### Startup model resolution

Model resolution occurs only after the relevant workspace's trust decision and eligible sources are known and finishes
before provider or fresh-session publication:

1. **Hosted** performs no deployment lookup or Prompt provenance resolution. Before ACP transport startup, reject only
   an explicit process-level `--agent-kind hosted` together with `--model`. For ACP `session/new`, merge eligible
   session-cwd sources first; if the final kind is Hosted, reject an explicit `--model` at method level and ignore only
   ambient process/dotenv/project/global model values. New Hosted sessions use canonical `hosted` in the shared non-null
   shape and header. ACP `session/load` overlays persisted kind/model/binding and does not apply the new-session
   model/kind conflict; fresh candidates cannot veto the header. A valid loaded Hosted binding preserves any legacy
   non-blank value exactly as opaque compatibility metadata; missing/blank model metadata remains corrupt.
2. **TUI resume/continue** first applies exact canonical launch-cwd selection, then requires strict persisted kind to
   match effective TUI kind. Prompt-over-Hosted and Hosted-over-Prompt fail before project/catalog construction,
   discovery, provider/service operations, or writes; `--continue` does not skip an opposite-kind newest session. A
   matched Prompt session uses its persisted non-blank model without ambient fallback or discovery. Combining
   `--model` with either resume form is a CLI conflict. If `--continue` finds no canonical-cwd match, it follows the new
   path below.
3. **New Prompt TUI** uses `--model`, then real-process `FOUNDRY_MODEL_NAME`, then trusted cwd dotenv, then trusted
   project `provider.model`, then global `provider.model`. Any value skips inventory lookup and is not startup-validated.
4. **Unresolved Prompt TUI** lists only project DTOs whose type is `ModelDeployment`. Zero produces localized setup
   guidance and a non-zero exit; one is auto-selected and reported in a visible TUI startup message; many enter the
   bounded, cancellable pre-provider selector described in [tui.md](tui.md#startup-model-bootstrap). The selector keeps
   at most the first 2,000 canonical options and visibly reports truncation plus the `--model` escape hatch. Cancellation
   exits cleanly. No outcome creates a temporary provider/session or persists separate selection state, and terminal
   zero/failure guidance is emitted after terminal restoration.
5. **ACP `session/new`** allocates `newCandidate`, resolves the requested canonical cwd's trust, and parses eligible
   sources before finalizing a Prompt model as CLI > real process environment > trusted cwd dotenv > trusted project
   settings > global settings. The process-local source tag may be `CLI`, `PROCESS_ENVIRONMENT`,
   `TRUSTED_SESSION_PROJECT`, or `GLOBAL`; the trusted-project tag covers either trust-gated project source, and only the
   unchanged value is persisted. ACP never discovers or prompts. After explicit-model conflict validation, Hosted
   ignores ambient model candidates and uses `hosted`. All local runtime/provider/binding/context/tool validation
   completes before one `persistNew`; failure closes
   provisional resources and leaves no local header or remote Hosted resource.
6. **ACP `session/load`** parses eligible fresh sources from the persisted cwd into a partial candidate, overlays exact
   persisted model/kind/binding, then defaults, validates, and constructs runtime. It does not compare persisted kind to
   a process-scoped effective kind. Prompt uses only its persisted non-blank model; Hosted preserves its non-blank
   compatibility value. Missing/blank/corrupt model metadata fails at method level with no ambient fallback, discovery,
   runtime publication, or rewrite.

A configured/bound PromptAgent does not satisfy local model resolution. Konductor does not fetch definition metadata to
infer a model, so absent local Prompt model follows the same TUI inventory flow or ACP `session/new` failure as an
ephemeral Prompt even though the service-side definition owns the request model.

### Frontend locale

`KONDUCTOR_LOCALE` is frontend configuration rather than Foundry runtime configuration. Help/version paths resolve it
without touching project files (real process environment, then the operating-system display locale). On a normal TUI
run, that operator locale renders any trust prompt; only after a trusted project dotenv is read may its locale apply to
the remaining frontend. ACP protocol content is never localized. JVM resource fallback ultimately selects the English
root bundle.

There is no localized CLI flag or settings-file field for locale yet. Commands, option names, tool/schema identifiers,
persisted data, model prompts, raw logs/tool results, and ACP protocol content remain stable regardless of locale.

## Selecting the provider / agent kind

- `--config-dir <path>` fixes the process config directory before either frontend, with the bootstrap precedence and
  project-source exclusion defined [above](#config-directory).
- `--approve`/`-a` and `--no-approve`/`-na` are mutually exclusive one-run trust inputs for both TUI and ACP. They do
  not persist or prompt; their safety behavior is defined under
  [Workspace identity and trust store](#workspace-identity-and-trust-store).
- `--agent-kind prompt|hosted` (or `provider.agentKind` in settings) picks the [provider](providers.md).
- `--model <name>` overrides `FOUNDRY_MODEL_NAME` for a new Prompt session and skips startup discovery. It conflicts
  with `--resume`/`--continue`, whose persisted model remains authoritative. TUI rejects it when effective mode is
  Hosted. ACP rejects an explicit process-level Hosted selection before transport; otherwise `session/new` applies the
  conflict after final session-cwd kind resolution, while `session/load` lets persisted identity override fresh inputs. In the TUI, `/model list` discovers project deployments after startup.
  `/model <deployment>` validates an exact name when discovery is available, rejects a confirmed missing deployment,
  and otherwise preserves free-text selection with an explicit unvalidated warning.
- `acp` and `--acp` select the headless ACP frontend; all other positional arguments are rejected.
- `--help`/`-h` and `--version`/`-V` run before Foundry configuration or provider construction.
- **Prompt (opt-in):** `KONDUCTOR_PROMPT_AGENT_NAME` / `provider.promptAgentName` binds the loop to a persisted **PromptAgent**
  (selected/created via [`/agent`](tui.md#slash-commands)); empty ⇒ ephemeral. See
  [providers.md](providers.md#persisted-prompt-agents-promptagent) and
  [M2.5](../implementation-roadmap.md#m25-prompt-persisted-agents-promptagent-opt-in).
- Hosted reads `KONDUCTOR_HOSTED_AGENT_NAME` + `FOUNDRY_AGENT_CONTAINER_IMAGE` ([hosted-agents.md](hosted-agents.md)).

## Tools & compaction knobs

- `--tools a,b,c` enables exactly those built-ins.
- `--exclude-tools x,y` subtracts names from `tools.allow`, or from all built-ins when no allow-list is configured.
- `--no-tools` enables an empty client-side tool set. These three Prompt-only flags are mutually exclusive;
  read-only = `--tools read,ls,find,grep` ([tools.md](tools.md)).
- `compaction.*` tunes the context-window behavior ([compaction.md](compaction.md)).

Session flags are TUI-only. `--no-session` is incompatible with `--resume`/`--continue`, `--resume` is incompatible
with `--continue`, and `--model` is incompatible with either resume form. Effective Hosted mode rejects explicit
`--model` for TUI and ACP new-session resolution; ambient model values are tolerated but ignored as Hosted execution
inputs, and ACP load is governed by persisted identity. ACP mode rejects TUI session flags rather than silently
ignoring them. Before ACP transport, only explicit process-level `--agent-kind hosted` plus
`--model` is a Hosted model conflict; session-cwd-selected Hosted applies it during `session/new`, and load never applies
that fresh-input conflict to persisted identity.

The config-dir, context-file, and one-run trust controls are accepted in both modes. TUI startup applies the canonical
launch-cwd check in [sessions.md](sessions.md#tui-startup-workspace-binding): explicit `--resume` cannot select a
session persisted for a different canonical cwd, and `--continue` searches only the canonical launch cwd. This happens
before trust/effective project configuration and credential, runtime, context, tool, or provider construction. ACP
`session/load` intentionally follows its separate persisted-cwd-authoritative contract.

## Related docs

[providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) · [tools.md](tools.md) ·
[compaction.md](compaction.md) · [development.md](../development.md)
