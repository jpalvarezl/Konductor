---
name: harness-drift-analysis
description: Evaluate Konductor feature drift and roadmap alignment against pi coding agent, Copilot CLI, and general agent-harness best practices. Use when asked about feature drift, parity gaps, product direction, roadmap priority, or whether a change is aligned with Konductor's pi-like Azure Foundry dogfooding goals.
---

# Konductor harness drift analysis

Use this skill to assess whether Konductor is drifting from its intended product shape: a **pi-inspired Java/Kotlin
terminal coding-agent harness** that dogfoods the team's **Azure AI Agents / Azure AI Projects / Foundry** SDKs.
The goal is **not** to clone pi or Copilot CLI feature-for-feature. The goal is to keep Konductor aligned with
best practices for local coding-agent harnesses while preserving its Azure dogfooding focus.

## Required orientation

Read these first, in order:

1. `AGENTS.md` — current repo-specific instructions and source-vs-spec warning.
2. `docs/burndown.md` — live implementation status; trust this over stale docs.
3. `docs/index.md` — doc map and status banner; use it to find the relevant spec files.
4. Current `src/` for the specific areas being assessed. Do **not** rely only on docs.
5. Relevant open GitHub issues and PRs for active debt; do not infer status from old closed discussions.

Useful source/doc searches:

```bash
rg -n "AgentLoop|ProviderFactory|HostedProvider|SessionStore|Compactor|ToolRegistry|runBlocking|session/cancel" src docs
rg -n "--help|--version|--tools|--resume|/model|/compact|AGENTS.md|CLAUDE.md|Context" src docs README.md AGENTS.md
find src/main/kotlin src/test/kotlin -type f | sort
```

Resolve the installed pi coding agent before using the fallback baseline:

```powershell
$piLauncher = (gcm pi).Source
$piLauncher
```

`gcm` is PowerShell's `Get-Command` alias. Read the returned launcher (`pi.ps1`/`pi.cmd`/shell wrapper) to find the
actual `@earendil-works/pi-coding-agent` package path; with the standard npm launcher it is next to the wrapper under
`node_modules/@earendil-works/pi-coding-agent`. Record the package version from `package.json`, then consult its
`README.md`, `docs/`, and relevant `dist/` source artifacts directly. At minimum, inspect the implementation behind
CLI argument parsing, sessions, compaction, context/resource loading, trust, turn cancellation/queueing, tools, and
headless JSON/RPC modes. Cite the exact pi version and files in the analysis output and any issue/PR it creates.

Only if `gcm pi` cannot resolve a launcher or the package contents are unavailable should the analysis use the
baseline dimensions below without direct pi claims. Never infer current pi behavior from an old Konductor report.

## Baseline dimensions to compare against

### pi coding agent baseline

Assess Konductor against pi-like local coding-agent harness capabilities:

- Core streamed model loop with local tool execution.
- Built-in coding tools: `read`, `write`, `edit`, `bash`, `grep`, `find`, `ls`.
- Durable JSONL sessions, resume/list/load, names, branching/fork/clone, import/export/share.
- Context compaction: automatic/manual, overflow recovery, summary entries, context usage tracking.
- Context files: global/ancestor/cwd `AGENTS.md` or `CLAUDE.md`, plus system replacement/append files.
- Interactive TUI/editor UX: multiline input, file refs, path completion, external editor, images, shell shorthands,
  markdown/code/diff rendering, collapsible tool output, footer/status with model/tokens/context/cost.
- Slash commands and CLI flags: model switching, settings, sessions, tools, compaction, export/import, help/version.
- Async turn runtime: non-blocking input, queued steering/follow-up messages, retry/abort/cancel.
- Headless/programmatic modes: print/JSON/RPC/SDK-style integrations.
- Customization: extensions, custom tools/commands/UI hooks, skills, prompt templates, themes, packages.
- Safety/trust: project trust, resource loading gates, tool allow/exclude, path containment, shell/mutation controls.
- Distribution and smoke tests.

### Copilot CLI / general agent-harness baseline

Use Copilot CLI as a best-practices comparator, not as a strict feature checklist:

- Low-friction first run: clear auth/config errors, `--help`, `--version`, predictable install/run commands.
- Strong workspace awareness: repo instructions, current cwd, git/project context, file search/read-first behavior.
- Clear command surface: discoverable commands, model/tool/session controls, non-interactive/scripting options.
- Safe execution defaults: explicit boundaries for shell and file mutation, permission/trust model where project-local
  resources can execute or influence behavior.
- Responsive UX: streaming, cancellation, progress/tool visibility, robust error/retry behavior.
- Durable work: sessions/history preserve what the model saw and did, including tool calls/results and failures.
- Integration friendliness: clean stdout/stderr separation, protocol/golden tests, stable machine-readable modes.

## Konductor-specific lens

When judging drift, keep these project goals in mind:

- **Azure dogfooding is intentional.** A Hosted provider or persisted PromptAgent path may be absent from pi but is
  aligned if it stays behind the `AgentProvider`/SDK seams and does not pollute the local Prompt loop.
- **Prompt provider should remain the pi-like local loop.** It owns client-side transcript, local tools, future
  session persistence, and compaction.
- **Hosted provider is server-owned.** Treat hosted sessions/logs as a separate execution model; do not require it
  to behave exactly like Prompt sessions.
- **Docs are target specs; source is implementation truth.** Flag docs drift separately from product drift.
- **Hackathon scope matters.** Missing pi/Copilot product polish is expected; prioritize foundational gaps that
  block future roadmap work.

## How to perform the analysis

1. Run tests when practical (`./mvnw -q test`) and record the result in the analysis output or resulting issue/PR.
2. Identify what changed since the previous analysis by reading current source, `docs/burndown.md`, and recent PRs.
3. Classify findings as:
   - **Drift reduced** — new work closes a previous parity/best-practice gap.
   - **Intentional divergence** — differs from pi/Copilot but supports Azure dogfooding or repo goals.
   - **Risk / harmful drift** — likely to confuse users, block roadmap work, or entangle layers.
   - **Expected missing surface** — not implemented yet, but already roadmap-consistent.
4. Do **not** create a standalone drift-report file by default. Persist durable findings where they belong:
   - implementation status and actionable work → `docs/burndown.md`,
   - milestone intent → `docs/implementation-roadmap.md`,
   - intentionally deferred ideas → `docs/future.md`,
   - stable behavioral contracts → the owning `docs/spec/*.md`,
   - substantial defects or cross-cutting design work → a focused GitHub issue.
5. Summarize the current comparison, citations, and prioritization in the user-facing result or the focused issue/PR.

## Common high-priority findings in this repo

Check these every time:

- Is `AgentLoop` history faithful, including tool calls/results, failed/partial turns, and future session entries?
- Are turns single-flight/cancelable, especially for ACP overlapping prompts and TUI Esc?
- Are Prompt sessions moving toward append-only JSONL persistence before compaction?
- Are context files (`AGENTS.md`/`CLAUDE.md`) loaded safely and predictably?
- Are new config fields actually honored, or merely parsed and ignored?
- Are Azure SDK types contained inside provider/inference/hosted chokepoints?
- Are Hosted-provider concerns isolated from Prompt provider session/compaction semantics?
- Does ACP expose what happened (tools/logs/usage/errors/cancel), not just final text?
- Are docs (`README.md`, `AGENTS.md`, `docs/index.md`, `docs/burndown.md`) synchronized enough for contextless agents?

## Output guidance

Be explicit about whether a gap is **bad drift** or simply **not built yet**. Favor roadmap-aware recommendations:

- M3 sessions before advanced TUI polish.
- Async/cancel/single-flight before queueing and rich steering UX.
- Context-file discovery before project-local extensions/packages.
- CLI `--help`/`--version` before packaged-jar smoke tests.
- ACP tool/log visibility before claiming editor-client parity.
