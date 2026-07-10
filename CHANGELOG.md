# Changelog

Notable Konductor changes are recorded here by release. Because pull requests are squash-merged, the included PR list
is also the release's commit-level history.

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

[0.1.1]: https://github.com/jpalvarezl/Konductor/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/jpalvarezl/Konductor/releases/tag/v0.1.0
