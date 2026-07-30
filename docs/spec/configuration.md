# Configuration

How Konductor is configured: environment variables, authentication, and settings. All values have safe defaults so
a first run needs only an endpoint, a model, and a signed-in Azure identity.

> Code blocks are illustrative design sketches, not committed implementation.

## Environment variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `FOUNDRY_PROJECT_ENDPOINT` | yes | Foundry project endpoint: `https://{resource}.ai.azure.com/api/projects/{project}` |
| `FOUNDRY_MODEL_NAME` | yes (Prompt) | Model deployment name, e.g. `gpt-5-mini` |
| `FOUNDRY_AGENT_CONTAINER_IMAGE` | Hosted only | Container image for hosted-agent sessions |
| `KONDUCTOR_HOSTED_AGENT_NAME` | Hosted | Named hosted agent to deploy/select ([hosted-agents.md](hosted-agents.md)) |
| `KONDUCTOR_PROMPT_AGENT_NAME` | opt-in Prompt | Optionally bind a persisted **PromptAgent** ([providers.md](providers.md#persisted-prompt-agents-promptagent)) |
| `KONDUCTOR_CONFIG_DIR` | no | Override config dir (default `~/.konductor`) |
| `KONDUCTOR_LOCALE` | no | Frontend locale as a BCP-47 tag, e.g. `en`, `es`, or `fr-CA` |

## Authentication

Konductor authenticates with **Entra ID** via `DefaultAzureCredential`; the SDK applies the AAD scope
`https://ai.azure.com/.default`. TUI startup and each ACP session resolve an effective configuration before
constructing their Foundry runtime; `FoundryProjectRuntime` then owns that resolved endpoint and credential for typed
catalog construction and its provider clients. The TUI queries those SDK-free catalogs only when `/model list`, `/model <deployment>`, or `/connections` is submitted; discovery is not
a startup prerequisite. Read-only discovery failures keep the current session usable. If an explicit `/model <name>`
request cannot be validated because discovery failed, Konductor switches to that name with a warning; if discovery
succeeds and proves the name absent, it rejects the switch and points to `/model list` or deployment setup.
A constructed runtime's project endpoint and credential are immutable. ACP does not reuse a runtime across effective
session configurations; project-controlled values can participate only after the workspace trust decision described
below.

```kotlin
val credential = DefaultAzureCredentialBuilder().build()
```

`DefaultAzureCredential` tries, in order: environment service-principal vars, managed identity, the Azure CLI
(`az login`), Azure Developer CLI, etc. For local dev, `az login` to the tenant/subscription that owns the Foundry
project is the simplest path. No API keys are stored by Konductor.

## Config directory

The effective user config directory contains global settings, sessions, global context, and the trust store. Resolve it
without consulting any project-controlled file:

1. use an explicit config-directory value supplied by the CLI/application API, when present;
2. otherwise use non-blank `KONDUCTOR_CONFIG_DIR` from the **real process environment** (`System.getenv`), not from a
   dotenv overlay; or
3. default to `~/.konductor`.

CLI inputs, the real process environment, and global settings are operator-controlled configuration sources. In the v1
schema, global settings do not contain a self-relocating config-directory field: they are read only after the path
above is fixed. A future global-only field may join this bootstrap precedence, but project `.env` and project settings
must never supply or redirect `KONDUCTOR_CONFIG_DIR`, even after trust. This also means ACP startup must retain the real
process environment separately rather than pass one indistinguishable dotenv-overlay lookup into configuration.

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
effective configuration. A project dotenv entry named `KONDUCTOR_CONFIG_DIR` is always ignored as described above.
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

```kotlin
data class Config(
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

Trust uses the same root rule as context discovery. Starting at canonical cwd, inspect each literal `.git` entry
without following its final link; the nearest non-symlink regular file or directory marks the root. A `.git` symlink
never marks or redirects a workspace. The root's absolute canonical path string is the persisted identity. A symlink
alias therefore shares trust with its target; moving or copying a workspace produces a new identity and returns to
unknown trust.

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
a distinct **error snapshot**: all decisions are unknown, an actionable warning identifies the file and problem, and
Konductor never overwrites it as recovery. Project files can neither select nor write the store. Error snapshots do
not fall through to the ordinary unknown-workspace Trust/Do-not-trust prompt described below.

The trust boundary assumes the canonical config directory is controlled by the current operator and that passive
repository contents cannot create, replace, or write its entries. Here, the operator is the process's effective user
identity (effective UID on POSIX or current user SID on Windows). The implementation establishes the precondition with
host security views: the directory and existing trust entries must be owned by that identity, and POSIX mode/ACL or the
Windows DACL must not grant create/delete/write access to other non-administrative principals. An ownership mismatch
or unsafe permission is rejected. Each trust-store, lock, and temporary regular file must also have link count one when
the host exposes link count; if it does not, trust is unknown unless another host primitive establishes the equivalent
no-external-hard-link property. A hard-linked trust file is never accepted merely because its config-directory pathname
is safe. If any ownership, write-isolation, or anti-hard-link precondition cannot be established, trust resolution is an
error/unknown snapshot and writing is disabled; project configuration remains ineligible.

This policy does **not** require one impossible portable syscall that atomically proves owner, ACL, and link count.
Platform-specific checks may be composed, and stronger secure-directory or native handle operations must be used when
available. The boundary protects against repository-controlled paths and cooperating Konductor writers, not a malicious
process already executing as the same operator or compromise of that account.

Every initial read, under-lock reread, and final CAS reread of `workspace-trust.json` is itself a bounded no-follow
operation against the pinned config directory. Definite no-follow absence is the only missing-store result. Otherwise,
Konductor requires the literal child to be a regular operator-owned file with reported size at most **1 MiB**, rejects
a final symlink and any exposed unsafe link count, records its no-follow attributes, and opens that child with
`NOFOLLOW_LINKS` (directory-relative when the host supports it). It reads at most **1 MiB + 1 byte**, rejects an actual
byte count above 1 MiB, strict-decodes UTF-8,
and revalidates the pinned directory, child type, canonical child location, and available stable attributes/file key
after the read. Any observed substitution or change is an error snapshot, never a partially accepted store. As with
project/context reads, this detects observable races but makes no claim about an unobservable same-attributes ABA on a
filesystem without a stronger handle primitive.

All Konductor writers coordinate through an exclusive OS lock on `<config-dir>/workspace-trust.lock`; they retry for at
most five seconds using a monotonic deadline, and timeout leaves the run untrusted. The lock is opened relative to the
same pinned directory with `NOFOLLOW_LINKS` and must satisfy the same regular-file, ownership, link-count, and
pre/post-identity checks. The TUI does not hold the lock while asking. The initial bounded store read returns a
compare-and-swap token over the exact accepted state (missing or exact bytes). After an answer, the writer acquires the
lock, rereads with the same no-follow checks, strictly validates the store, and compares that state with the token:

- unchanged valid/missing snapshot: apply the answer;
- changed valid snapshot with only unrelated workspace updates: merge the answer into that latest snapshot;
- the same workspace already has the same decision: succeed idempotently without rewriting;
- the same workspace has the opposite decision: report a stale-decision conflict, do not overwrite it, and keep this
  run untrusted; or
- the latest store is unreadable, invalid, or changes again before replacement: report a conflict and do not replace
  it.

A write serializes canonical v1 JSON to a create-new, no-follow sibling temporary file in the pinned directory,
requires that file to satisfy the ownership/link-count/type checks, flushes it, then performs a same-filesystem atomic
replace while still holding the lock. There is no non-atomic fallback. The final accepted-state check immediately
before replacement is another bounded no-follow reread and is part of the CAS; temporary cleanup is best effort. Failed
persistence, timeout, or conflict never enables project `.env` or project settings, even when the selected answer was
trusted. The lock and CAS define coordination among Konductor processes. An external edit observed by any reread is a
conflict; a same-user program that ignores the lock and races after the final check is outside the threat model rather
than something described as an atomic filesystem CAS. A legitimate concurrent atomic replacement can therefore make a
lock-free reader transiently return unknown; that conservative result is intentional.

For a **valid** missing/loaded store, the TUI asks only when the current workspace has no decision and at least one
gated project entry (`<canonical-cwd>/.env` or `<canonical-cwd>/.konductor/settings.json`) is definitely present. The
normal prompt identifies the canonical workspace root, offers persistent Trust or Do not trust decisions, and defaults
to **untrusted**. Known decisions are not prompted again. With no gated entry, valid unknown trust causes no prompt or
write. `--no-context-files` does not suppress this independent decision.

An error snapshot instead shows a dedicated repair screen before effective configuration or runtime construction. It
identifies `<config-dir>/workspace-trust.json`, explains the failed parse/read/ownership/permission/link check, and
tells the operator to back up and repair or remove invalid contents, or restore current-user ownership and private
permissions, then restart. It offers only **Continue untrusted for this run** and **Quit**, defaulting to Quit. Continue
untrusted opens neither gated project file, records no in-memory decision beyond that run, and performs **no trust-store
or lock/temp write**. It never offers an ineffective persistent Trust action and never follows with the normal trust
prompt, even when gated files are present. Quit exits before credential, Foundry runtime/provider, context, or tool
construction. The repair screen is shown for every error snapshot, even when no gated project entry exists, because
silently ignoring a corrupt security store would hide lost decisions.

ACP never prompts and never writes trust. It resolves the canonical root and a fresh store snapshot for each session
cwd; valid unknown or error state behaves as untrusted for that session. Plain-text context loading remains independent
of this decision.

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

### Frontend locale

`KONDUCTOR_LOCALE` is frontend configuration rather than Foundry runtime configuration. Help/version paths resolve it
without touching project files (real process environment, then the operating-system display locale). On a normal TUI
run, that operator locale renders any trust prompt; only after a trusted project dotenv is read may its locale apply to
the remaining frontend. ACP protocol content is never localized. JVM resource fallback ultimately selects the English
root bundle.

There is no localized CLI flag or settings-file field for locale yet. Commands, option names, tool/schema identifiers,
persisted data, model prompts, raw logs/tool results, and ACP protocol content remain stable regardless of locale.

## Selecting the provider / agent kind

- `--agent-kind prompt|hosted` (or `provider.agentKind` in settings) picks the [provider](providers.md).
- `--model <name>` overrides `FOUNDRY_MODEL_NAME` (Prompt). In the TUI, `/model list` discovers project deployments.
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

Session flags are TUI-only. `--no-session` is incompatible with `--resume`/`--continue`, and `--resume` is
incompatible with `--continue`; ACP mode rejects TUI session flags rather than silently ignoring them. TUI startup
also applies the canonical launch-workspace check in
[sessions.md](sessions.md#tui-startup-workspace-binding): explicit `--resume` cannot select a session persisted for a
different canonical cwd, and `--continue` searches only the canonical launch cwd. This rejection happens before
trust/effective project configuration and before credential, runtime, context, tool, or provider construction. ACP
`session/load` intentionally follows its separate persisted-cwd-authoritative contract.

## Related docs

[providers.md](providers.md) · [hosted-agents.md](hosted-agents.md) · [tools.md](tools.md) ·
[compaction.md](compaction.md) · [development.md](../development.md)
