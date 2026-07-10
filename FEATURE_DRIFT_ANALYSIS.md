# Feature Drift Analysis

_Last reviewed: 2026-07-10_

This compares Konductor `main` at `2b6163f` with the locally installed pi coding agent
`@earendil-works/pi-coding-agent` **0.80.3**. PowerShell `gcm pi` resolved
`C:\nvm4w\nodejs\pi.ps1`; that launcher points to
`C:\nvm4w\nodejs\node_modules\@earendil-works\pi-coding-agent`.

Direct pi references included `README.md`, `docs/usage.md`, `docs/sessions.md`, `docs/session-format.md`,
`docs/compaction.md`, `docs/security.md`, `docs/rpc.md`, `docs/json.md`, `docs/sdk.md`, and the relevant `dist/`
CLI/resource/session/runtime artifacts. Konductor's `docs/burndown.md` is the local progress baseline; source and
tests are implementation truth.

Validation on current `main`: `mvn -q -o test` passed (**189 tests, 0 failures/errors, 3 skipped**).

## TL;DR

Konductor has **substantially reduced product drift** since the previous 2026-07-08 assessment. It is no longer an
M2-era tool-loop prototype:

- The Prompt path has the pi-like core: streamed inference, the 7 local coding tools, faithful tool history,
  append-as-produced JSONL sessions, resume/name controls, automatic/manual compaction, and cancelable TUI turns.
- Opt-in persisted PromptAgents and the Hosted provider deepen Azure SDK dogfooding without changing the intended
  local Prompt-loop ownership model.
- ACP now persists/list/loads sessions, streams text/tool/hosted-log updates, and cancels active turns.
- The TUI now has multiline input, basic markdown/code rendering, tool summaries, model switching, token/context/cost
  status, retry visibility, and `Esc` cancellation.

The previous report's largest claims about missing sessions, compaction, cancellation, ACP tools, and PromptAgents
were stale. The most harmful drift on current `main` is now:

1. **ACP runtime ownership is not workspace/session isolated.** `Main` builds one cwd-bound tool/context stack and one
   stateful provider, then shares them across client-created ACP sessions. This can target the wrong workspace and
   can leak Hosted provider state across sessions. Tracked in
   [issue #16](https://github.com/jpalvarezl/Konductor/issues/16).
2. **Context-file discovery and project trust are missing.** Pi loads global/ancestor/cwd `AGENTS.md`/`CLAUDE.md`
   and gates project settings/resources. Konductor loads `<cwd>/.konductor/settings.json` unconditionally but does
   not load the repository instructions that make a coding agent effective.
3. **ACP remains less observable/control-rich than the real loop.** Permission requests, usage/compaction updates,
   replay-on-load, and a golden protocol transcript are absent.
4. **Linear sessions are durable, but not tree sessions.** There is no `/tree`, `/fork`, `/clone`, branch summary,
   import/export/share, or structured entry/tree query surface.
5. **Runtime customization is intentionally deferred.** Konductor has internal seams, but no extensions, skills,
   custom commands/tools/UI hooks, packages, or prompt-template loading.

Two focused remediation PRs were opened during this refresh:

- [PR #17](https://github.com/jpalvarezl/Konductor/pull/17): reject overlapping per-session turns, make ACP
  cancellation target-safe, add partial failure/cancel persistence tests, and add stable JSONL goldens.
- [PR #18](https://github.com/jpalvarezl/Konductor/pull/18): config-free `--help`/`--version`, strict CLI parsing,
  tool gates, Maven Wrapper, package CI, and shaded-jar smoke.

Both are intentionally unmerged pending owner review.

## Latest-change drift check

### Drift reduced

- **Durable sessions (M3):** `JsonlSessionStore`, `SessionCodec`, `SessionHistory`, startup resume flags, and session
  slash commands are implemented. `Session.cwd` is `java.nio.file.Path`, persisted as an absolute normalized string.
- **Compaction (M4):** `ContextWindowTracker` and `Compactor` implement threshold/manual compaction, safe cut points,
  `CompactionEntry`, JSONL rewrite, and summary reconstruction.
- **PromptAgent dogfooding (M2.5):** the persisted-agent path is a separate input-only agent-scoped inference client
  behind `SwappableInferenceClient`; `/agent` and session header restoration are implemented and live-verified.
- **Cancelable interactive runtime (M6 partial):** `TuiApp` uses `submitAsync()` and a background turn `Job`; the
  event loop remains responsive and `Esc` cancels the turn.
- **ACP Phase C basics:** create/load/list persistence, tool calls/results, hosted logs, and cancellation are mapped.
- **TUI/editor baseline:** multiline input and basic markdown rendering now exist, so the old "mostly absent" claim
  is no longer accurate.
- **Provider/config honesty:** `ProviderFactory` routes Prompt vs Hosted; config fields for PromptAgent and Hosted
  behavior are honored rather than merely parsed.

### Intentional divergence

- **Azure-only provider/auth surface:** Foundry project endpoints plus `DefaultAzureCredential` are the product
  focus, not an accidental lack of multi-vendor support.
- **Persisted PromptAgents:** pi has no equivalent server-side agent definition, but this preserves the client-owned
  transcript/tool loop while exercising Azure agent versioning.
- **Hosted provider:** server-owned execution is an Azure-specific second model. It is aligned while it remains
  isolated from Prompt session/compaction semantics.
- **ACP instead of pi RPC:** both provide a machine-driven stdio mode; ACP is Konductor's chosen compatibility
  contract.
- **JVM distribution:** shaded jar + `jpackage` is the appropriate analogue to pi's npm/binary distribution.

### Risk / harmful drift

- **ACP construction is process-scoped, not session/cwd-scoped.** `Main.kt` constructs tools/context from the process
  cwd before ACP receives `SessionCreationParameters.cwd`; `KonductorAgentSupport` then creates multiple loops over
  that shared stack. `HostedProvider` is also stateful. This conflicts with ACP's multi-session model (#16).
- **Hosted loops receive Prompt compaction settings.** Client-side transcript compaction is a Prompt concern; a
  server-owned Hosted loop should not accidentally acquire Prompt session semantics.
- **Project-local settings are trusted implicitly.** This is currently configuration influence rather than code
  execution, but it becomes a larger security problem as prompt files, skills, packages, or extensions land.
- **Docs had become an onboarding hazard.** `AGENTS.md`, `README.md`, `docs/index.md`, provider sketches, ACP status,
  and hero scenarios contradicted current code. This refresh narrows volatile status duplication and points back to
  the burndown.

### Expected missing surface

- Context-file discovery and system append/replace files.
- Project trust and explicit resource-loading policy.
- Session trees, branching/fork/clone, export/import/share.
- ACP permission requests, usage/compaction updates, history replay, and protocol golden tests.
- File references/path completion, external editor/images, shell shorthands, richer diff/code rendering, and
  collapsible tool output.
- Thinking-level controls and richer model selection.
- Runtime extensions/custom tools/commands/UI hooks/skills/packages/themes.

These are real parity gaps, but most are roadmap/future work rather than evidence that the implemented architecture
is moving in the wrong direction.

## Coverage matrix vs. pi 0.80.3

| Area | Pi baseline | Konductor `main` (2026-07-10) | Assessment / next step |
|---|---|---|---|
| Core coding loop | Streamed model loop with local tools | Streamed Foundry Responses + harness-owned tool loop | Strong core alignment |
| Built-in tools | `read`, `write`, `edit`, `bash`, `grep`, `find`, `ls` | Same 7 tools; cwd/symlink containment, truncation, allow-list | Covered; CLI gates are in PR #18 |
| Providers/auth | Many providers and auth methods | Foundry only via Azure SDK + Entra ID | Intentional divergence |
| Persisted agent definition | No direct equivalent | Opt-in PromptAgent, input-only agent-scoped client | Intentional Azure dogfooding |
| Hosted/server-owned agent | No default equivalent | Hosted versions/sessions/logs behind provider seam | Aligned, but fix ACP/provider ownership (#16) |
| Sessions | JSONL, resume/names, tree branches, fork/clone | JSONL create/load/list/resume/name, linear `parentId` chain | Durable foundation done; tree surface missing |
| Compaction | Auto/manual, overflow recovery, branch summaries | Auto/manual summary entry + JSONL rewrite | Core covered; live validation and overflow retry can improve |
| Async/cancel/queue | Abort plus steering/follow-up queues | TUI/ACP cancellation; TUI input inert while running | Cancellation covered; main overlap gap addressed by PR #17 |
| TUI rendering | Rich markdown, diffs, images, collapsible tools | Multiline + basic markdown/code + tool summaries/status | Useful MVP; rich editor affordances remain |
| Slash commands | Broad model/session/tree/settings/export surface | `/new`, `/resume`, `/name`, `/session`, `/compact`, `/model`, `/agent`, quit | Good core commands; no tree/settings/export/trust |
| CLI | Help/version, model/tools/session/context/trust/noninteractive controls | Session/model/provider flags on main | Strict help/version/tool gates + wrapper/smoke in PR #18 |
| Context files | Global + ancestor + cwd `AGENTS.md`/`CLAUDE.md`; system files | Prompt override/append only | High-value missing surface |
| Trust/safety | Project trust gates resources/settings; no sandbox claim | Tool containment + settings allow-list; no trust gate | Add before project-local executable resources |
| Headless integration | Print/JSON/RPC/SDK; entry/tree queries | ACP stdio with sessions, tools, logs, cancel | Strong alternative; finish observability/permissions/goldens |
| Customization | Extensions, skills, prompts, themes, packages | Internal seams only | Expected hackathon omission |
| Distribution | npm/binary | Shaded jar, `jpackage`, release workflow | Covered differently; wrapper/smoke in PR #18 |
| Tests | Broad product/session/runtime coverage | 189 tests on main; focused fakes and live probes | Healthy; PR #17/#18 add repo-health coverage |

## Pi reference details that matter

- `docs/usage.md` documents context discovery, system prompt files, trust, sessions, and CLI controls.
- `docs/security.md` makes trust an **input-loading gate**, not a sandbox, and explicitly loads context files
  independently from protected executable resources.
- `docs/sessions.md` and `docs/session-format.md` define the append-only JSONL tree, `/tree`, `/fork`, `/clone`, and
  branch summaries.
- `docs/rpc.md` includes `get_entries` and `get_tree`, providing durable machine-readable inspection beyond final
  text.
- `dist/cli/args.js` and `dist/core/slash-commands.js` show a discoverable, strict command surface.
- `dist/core/resource-loader.*`, `dist/core/session-manager.*`, and `dist/core/agent-session.*` show the separation
  between resource loading, tree persistence, and turn/queue/cancel runtime.

Konductor should borrow these ownership and safety patterns, not pi's TypeScript implementation or every product
feature.

## Important risks

### 1. ACP session isolation and Hosted state ownership (#16)

ACP accepts a cwd per session, but the current process-wide tool/context stack was built from the launch cwd.
Prompt sessions can therefore execute against the wrong workspace. Sharing one stateful Hosted provider is more
dangerous because the provider owns server session/version lifecycle. Fix this before treating ACP as safely
multi-session.

### 2. Context instructions without a trust/resource model

The highest-leverage coding-quality feature is loading repository instructions, but project-local settings and future
resources must have an explicit trust policy. Follow pi's useful distinction:

- context text (`AGENTS.md`/`CLAUDE.md`) can be loaded under a documented prompt-injection risk model;
- executable/configurable project resources require a saved/explicit trust decision;
- noninteractive ACP behavior needs a deterministic no-prompt policy.

### 3. ACP parity is about visibility, not only execution

The underlying loop now executes tools and persists sessions, but clients still cannot see usage/context changes,
compaction notices, approval requests, or replayed history. Add a golden client-agent transcript so the protocol
contract cannot silently regress.

### 4. Partial and failed turns need a long-term persisted representation

Current policy keeps the user entry and completed tool actions but only persists an assistant entry after terminal
completion. PR #17 locks that behavior with tests. A future dedicated failed/aborted entry would make resumed and
machine-read transcripts more honest.

### 5. Keep status in one place

`docs/burndown.md` should remain the item-level implementation source. Root docs and specs should state stable
contracts and link to the burndown rather than copying fast-changing milestone detail. When implementation changes a
wire format or SDK request shape, update the owning spec in the same PR.

## Suggested next slices

1. **Review the active repo-health PRs**
   - PR #17: single-flight/cancellation/persistence goldens.
   - PR #18: CLI strictness/tool gates/Maven Wrapper/package smoke.
   - This documentation/drift refresh.
2. **Fix ACP runtime ownership (#16)**
   - Build context and `ToolContext` from each ACP session cwd.
   - Define provider lifecycle per session or prove a provider implementation is safe to share.
   - Keep Prompt compaction out of Hosted execution.
   - Add two-cwd and two-session isolation tests.
3. **Implement context files + trust as one coherent design**
   - Global, ancestor, and cwd `AGENTS.md`/`CLAUDE.md`.
   - Optional system replacement/append files.
   - Saved trust decisions and deterministic ACP/noninteractive policy.
4. **Finish ACP Phase C**
   - Permission requests for mutating tools.
   - Usage/context + compaction updates.
   - History replay on load and a golden protocol integration test.
5. **Then extend durable work**
   - Session tree navigation, fork/clone, branch summaries, and structured entry/tree queries.
6. **Defer rich product polish until foundations are safe**
   - File refs/path completion, external editor/images, richer diff rendering, customization/packages/skills.

## Bottom line

Konductor is **not drifting away from its intended product shape**. The merged work now forms a credible pi-inspired
local coding-agent core on Kotlin/JVM while adding meaningful Azure-specific PromptAgent and Hosted dogfooding.

The priority has shifted from "build sessions/compaction/cancellation" to "make the multi-session runtime ownership
honest, load workspace instructions safely, and finish protocol visibility." If the ACP cwd/provider issue and
context/trust model are addressed without entangling Hosted and Prompt semantics, the architecture remains well
aligned with the project's goals.
