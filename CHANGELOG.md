# Changelog

Notable Konductor changes are recorded here by release. Because pull requests are squash-merged, the included PR list
is also the release's commit-level history.

## [0.2.0] - 2026-07-30

### Highlights

- Make Azure AI Foundry the explicit platform contract, with project-scoped runtime composition and credential-safe
  deployment and connection discovery.
- Add Foundry discovery commands, a searchable TUI command palette, and an interactive model picker.
- Preserve durable Hosted agent sessions across resume and ACP load while retaining ephemeral-session behavior.
- Replace the legacy inference seam with Foundry Responses clients for ephemeral and persisted Prompt agent flows.
- Add localized string assets and atomic session metadata updates.
- Establish Azure SDK record/playback infrastructure and run Foundry playback coverage in Linux CI.

### Fixed

- Restore the OpenAI Okio runtime dependency required by packaged execution.
- Require explicit discovery composition instead of silently constructing partial Foundry runtimes.

### Included pull requests

- [#24 Use semantic version for macOS release asset](https://github.com/jpalvarezl/Konductor/pull/24)
- [#25 Add localized string asset management](https://github.com/jpalvarezl/Konductor/pull/25)
- [#27 Adopt issue and milestone delivery workflow](https://github.com/jpalvarezl/Konductor/pull/27)
- [#54 Centralize roadmap and harden delivery workflow](https://github.com/jpalvarezl/Konductor/pull/54)
- [#56 Adopt Foundry-first platform contract](https://github.com/jpalvarezl/Konductor/pull/56)
- [#62 Add Foundry project deployment catalog adapter](https://github.com/jpalvarezl/Konductor/pull/62)
- [#63 Add credential-safe Foundry connection catalog](https://github.com/jpalvarezl/Konductor/pull/63)
- [#64 Evaluate Azure Responses convenience APIs and lifecycle](https://github.com/jpalvarezl/Konductor/pull/64)
- [#65 Rename Prompt seam for Foundry Responses](https://github.com/jpalvarezl/Konductor/pull/65)
- [#67 Design durable Hosted session reconnect lifecycle](https://github.com/jpalvarezl/Konductor/pull/67)
- [#68 Make Foundry provider capabilities explicit](https://github.com/jpalvarezl/Konductor/pull/68)
- [#69 Establish minimal local Foundry record/playback](https://github.com/jpalvarezl/Konductor/pull/69)
- [#74 Run Foundry playback in Linux CI](https://github.com/jpalvarezl/Konductor/pull/74)
- [#75 Migrate connection discovery to record/playback](https://github.com/jpalvarezl/Konductor/pull/75)
- [#76 Compose Foundry project catalogs and providers](https://github.com/jpalvarezl/Konductor/pull/76)
- [#77 Migrate PromptAgent smoke test to record playback](https://github.com/jpalvarezl/Konductor/pull/77)
- [#78 Add Foundry discovery commands to TUI](https://github.com/jpalvarezl/Konductor/pull/78)
- [#84 Introduce canonical TUI command registry](https://github.com/jpalvarezl/Konductor/pull/84)
- [#86 Migrate deployment catalog scenarios to record/playback](https://github.com/jpalvarezl/Konductor/pull/86)
- [#88 Restore OpenAI Okio runtime dependency](https://github.com/jpalvarezl/Konductor/pull/88)
- [#89 Make session metadata updates atomic](https://github.com/jpalvarezl/Konductor/pull/89)
- [#91 Add TUI command palette and model picker](https://github.com/jpalvarezl/Konductor/pull/91)
- [#98 Require explicit Foundry discovery composition](https://github.com/jpalvarezl/Konductor/pull/98)
- [#99 Add ephemeral Prompt Responses playback coverage](https://github.com/jpalvarezl/Konductor/pull/99)
- [#100 Preserve durable Hosted sessions across resume and ACP load](https://github.com/jpalvarezl/Konductor/pull/100)
- [#103 Complete Foundry-first delivery packet](https://github.com/jpalvarezl/Konductor/pull/103)

## [0.1.1] - 2026-07-10

### Fixed

- Publish a GitHub Release only after every OS package succeeds, so users never see a temporary bare or partial
  release.
- Build pre-1.0 macOS packages with a `jpackage`-compatible internal app version while retaining the semantic release
  version in the tag and artifact name.
- Populate release metadata from this changelog.

### Included pull requests

- [#23 Release packaging and metadata corrections](https://github.com/jpalvarezl/Konductor/pull/23)

## [0.1.0] - 2026-07-10

### Highlights

- Streamed Prompt inference with seven cwd-contained coding tools.
- Durable JSONL sessions, resume controls, and client-side compaction.
- Opt-in persisted PromptAgents and a live-verified Hosted provider.
- Lanterna TUI plus a headless ACP agent with sessions, tools, logs, and cancellation.
- Self-contained Linux and Windows packages.

### Included pull requests

- [#1 Base spec and roadmap](https://github.com/jpalvarezl/Konductor/pull/1)
- [#2 Basic unit tests](https://github.com/jpalvarezl/Konductor/pull/2)
- [#3 Burndown docs, AGENTS.md, docs re-org and ACP scaffolding](https://github.com/jpalvarezl/Konductor/pull/3)
- [#4 M0 provider bridge, core types, provider seam, and release scripts](https://github.com/jpalvarezl/Konductor/pull/4)
- [#5 M1 Azure inference for basic chat](https://github.com/jpalvarezl/Konductor/pull/5)
- [#7 M2 function-tool loop and built-in tools](https://github.com/jpalvarezl/Konductor/pull/7)
- [#8 M5 Hosted agents](https://github.com/jpalvarezl/Konductor/pull/8)
- [#9 M3 sessions](https://github.com/jpalvarezl/Konductor/pull/9)
- [#10 M2.5 persisted PromptAgents](https://github.com/jpalvarezl/Konductor/pull/10)
- [#11 M4 compaction](https://github.com/jpalvarezl/Konductor/pull/11)
- [#12 M6 TUI/UX polish](https://github.com/jpalvarezl/Konductor/pull/12)
- [#13 Multiline input, Markdown rendering, and tool-result rendering](https://github.com/jpalvarezl/Konductor/pull/13)
- [#14 ACP Phase C sessions, tools, list/load, and cancellation](https://github.com/jpalvarezl/Konductor/pull/14)
- [#15 ACP Hosted log streaming](https://github.com/jpalvarezl/Konductor/pull/15)
- [#17 Per-session turn and cancellation safety](https://github.com/jpalvarezl/Konductor/pull/17)
- [#18 Strict CLI parsing, tool gates, and jar smoke tests](https://github.com/jpalvarezl/Konductor/pull/18)
- [#19 Harness drift and documentation refresh](https://github.com/jpalvarezl/Konductor/pull/19)
- [#20 ACP runtime isolation by workspace](https://github.com/jpalvarezl/Konductor/pull/20)
- [#21 Remaining repository-health work](https://github.com/jpalvarezl/Konductor/pull/21)
- [#22 Canonical iteration workflow](https://github.com/jpalvarezl/Konductor/pull/22)

[0.2.0]: https://github.com/jpalvarezl/Konductor/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/jpalvarezl/Konductor/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/jpalvarezl/Konductor/releases/tag/v0.1.0
