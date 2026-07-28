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
| [I055 — Foundry-first alignment][I055] | [Issue #55][issue-55] | Foundry-specific platform and SDK composition |
| Foundations — M0–M6 and ACP agent role | [roadmap](../implementation-roadmap.md) · [burndown](../burndown.md) | Prompt/Hosted providers, tools, sessions, compaction, TUI/ACP foundations, packaging |

[I028]: I028-hosted-session-lifecycle.md
[I031]: I031-github-planning-and-workflow-hardening.md
[I055]: I055-foundry-first-platform-alignment.md
[issue-28]: https://github.com/jpalvarezl/Konductor/issues/28
[issue-31]: https://github.com/jpalvarezl/Konductor/issues/31
[issue-55]: https://github.com/jpalvarezl/Konductor/issues/55

## Intake

- Actionable work and design discussion start as focused [GitHub issues](https://github.com/jpalvarezl/Konductor/issues).
- Once non-trivial work is accepted and scoped, add a delivery packet when an offline implementation brief and
  targeted context pack add value; tiny obvious fixes may remain packet-free.
- Durable unscheduled direction belongs in [`future.md`](../future.md), not in a second actionable checklist.
- Large outcomes use parent/sub-issues or dependencies rather than one accumulating checklist.

Authoring and ownership rules: [`README.md`](README.md).
