# Delivery Packets

Registry of repository-local implementation contracts. GitHub issues own coordination/status and milestones group
related outcomes or releases. This file does not duplicate either tracking surface.

When an issue or packet is already named, open it directly rather than reading this registry first.

## Packets

| Packet | Coordination/evidence | Outcome |
|---|---|---|
| [I001 — Workspace context and project trust](I001-workspace-context-and-trust.md) | [Issue #26](https://github.com/jpalvarezl/Konductor/issues/26) | Load repository instructions predictably while gating project-controlled runtime configuration |
| [I002 — Localized string assets](I002-localized-string-assets.md) | [PR #25](https://github.com/jpalvarezl/Konductor/pull/25) | Locale-aware frontend resource catalog with stable command, protocol, prompt, and persistence identifiers |
| [I028 — Durable Hosted session lifecycle][I028] | [Issue #28][issue-28] | Preserve one server-owned Hosted conversation across new, resume, and ACP load |
| [I030 — Foundry provider capabilities](I030-foundry-provider-capabilities.md) | [Issues #30](https://github.com/jpalvarezl/Konductor/issues/30) and [#29](https://github.com/jpalvarezl/Konductor/issues/29) | Explicit Foundry runtime ownership and shared Prompt/Hosted capability enforcement |
| [I031 — Workflow hardening][I031] | [Issue #31][issue-31] | Planning routes and promotion validation |
| [I053 — Canonical TUI command registry][I053] | [Issue #53][issue-53] | Canonical enumerable slash commands with controller-owned execution |
| [I055 — Foundry-first alignment][I055] | [Issue #55][issue-55] | Foundry-specific platform and SDK composition |
| [I066 — Minimal test-proxy foundation][I066] | [Issue #66][issue-66] | Local Foundry record/playback/live proof |
| [I079 — Foundry model bootstrap][I079] | [Issue #79][issue-79] | Resolve a missing Prompt model before provider/session startup |
| [I080 — Atomic session metadata updates][I080] | [Issue #80][issue-80] | Atomic-only candidate header replacement with post-persistence model/name commits |
| [I081 — Background TUI command cancellation][I081] | [Issue #81][issue-81] | Race-free pre-commit cancellation and post-commit command completion reporting |
| [I083 — TUI command palette and model option picker][I083] | [Issue #83][issue-83] | Fuzzy command discovery and SDK-free model selection |
| [I090 — Dynamic resume and PromptAgent command palette options][I090] | [Issue #90][issue-90] | Recent-session and managed PromptAgent selection with insertion-for-confirmation |
| [I092 — PromptAgent binding commit and reconciliation][I092] | [Issue #92][issue-92] | One durable binding decision followed by non-failing provider, session, and status commits |
| [I095 — Prompt context-overflow recovery][I095] | [Issue #95][issue-95] | Compact and retry one safe Prompt overflow without replaying user or tool activity |
| [I096 — Bash process-tree cancellation][I096] | [Issue #96][issue-96] | Cancel bash process trees promptly while preserving timeout partial output |
| Foundations — M0–M6 and ACP agent role | [roadmap](../implementation-roadmap.md) · [burndown](../burndown.md) | Prompt/Hosted providers, tools, sessions, compaction, TUI/ACP foundations, packaging |

[I028]: I028-hosted-session-lifecycle.md
[I031]: I031-github-planning-and-workflow-hardening.md
[I053]: I053-tui-command-registry.md
[I055]: I055-foundry-first-platform-alignment.md
[I066]: I066-foundry-test-proxy-foundation.md
[I079]: I079-foundry-model-bootstrap.md
[I080]: I080-atomic-session-metadata.md
[I081]: I081-background-command-cancellation.md
[I083]: I083-tui-command-palette.md
[I090]: I090-dynamic-command-palette-options.md
[I092]: I092-promptagent-binding-commit.md
[I095]: I095-prompt-context-overflow-recovery.md
[I096]: I096-bash-process-tree-cancellation.md
[issue-28]: https://github.com/jpalvarezl/Konductor/issues/28
[issue-31]: https://github.com/jpalvarezl/Konductor/issues/31
[issue-53]: https://github.com/jpalvarezl/Konductor/issues/53
[issue-55]: https://github.com/jpalvarezl/Konductor/issues/55
[issue-66]: https://github.com/jpalvarezl/Konductor/issues/66
[issue-79]: https://github.com/jpalvarezl/Konductor/issues/79
[issue-80]: https://github.com/jpalvarezl/Konductor/issues/80
[issue-81]: https://github.com/jpalvarezl/Konductor/issues/81
[issue-83]: https://github.com/jpalvarezl/Konductor/issues/83
[issue-90]: https://github.com/jpalvarezl/Konductor/issues/90
[issue-92]: https://github.com/jpalvarezl/Konductor/issues/92
[issue-95]: https://github.com/jpalvarezl/Konductor/issues/95
[issue-96]: https://github.com/jpalvarezl/Konductor/issues/96

## Intake

- Actionable work and design discussion start as focused [GitHub issues](https://github.com/jpalvarezl/Konductor/issues).
- Once non-trivial work is accepted and scoped, add a delivery packet when an offline implementation brief and
  targeted context pack add value; tiny obvious fixes may remain packet-free.
- Durable unscheduled direction belongs in [`future.md`](../future.md), not in a second actionable checklist.
- Large outcomes use parent/sub-issues or dependencies rather than one accumulating checklist.

Authoring and ownership rules: [`README.md`](README.md).
