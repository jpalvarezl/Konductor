---
id: I028
title: Durable Hosted session lifecycle
issue: https://github.com/jpalvarezl/Konductor/issues/28
created: 2026-07-28
---

# I028 — Durable Hosted Session Lifecycle

Issue [#28](https://github.com/jpalvarezl/Konductor/issues/28) owns coordination, dependencies, status, and closure.
This packet records the accepted implementation contract and the smallest useful context route. The design pull request
is intermediate (`Related to #28`); the issue remains open until the implementation and validation below are accepted.

## Outcome

A persisted Konductor Hosted session reconnects to exactly one Foundry `AgentSessionResource`, so TUI `/new` and ACP
`session/new` isolate server context while TUI resume and ACP `session/load` reconnect the server-owned conversation
instead of displaying local history over an unrelated fresh sandbox.

## Scope

- Persist a Hosted binding containing the configured Hosted agent name and server-session id in the local session
  header before the server resource is first created.
- Use the local Konductor session UUID as the caller-provided Foundry session id. On first activation, create that exact
  id with `AgentsClient.createSession(agentName, VersionRefIndicator(version), sessionId)`; reconnect with
  `getSession(agentName, sessionId)` thereafter.
- Route TUI startup, `/new`, resume/continue, ACP `session/new`, and ACP `session/load` through one session activation
  contract. A persisted Hosted session owns its binding; arbitrary service ids are not adopted through `TurnRequest`.
- Make Hosted session switching detach the previous durable binding without deleting it. Persisted bindings survive
  provider/runtime/process close and remain resumable until explicitly deleted or expired by the service.
- Keep non-persisted (`--no-session`) Hosted resources provider-owned and delete-only, best effort, when `/new` replaces
  them or their provider closes.
- Validate reconnect status and agent identity, handle first-create races safely, and reject missing or terminal remote
  sessions without silently replacing server-owned history.
- Preserve Prompt session behavior and v1 JSONL compatibility while introducing a Hosted-only v2 header.

## Non-goals

- Reconstructing Hosted server history from local JSONL entries, replaying local history into a replacement Hosted
  sandbox, or applying Prompt compaction to Hosted history.
- A generic backend/session framework. Issue [#30](https://github.com/jpalvarezl/Konductor/issues/30) owns the minimum
  Foundry agent-kind capability/lifecycle shape that carries this concrete contract.
- Changing PromptAgent binding, Prompt resume, agent/version deployment ownership, session-file transfer, or ACP
  history replay.
- Adding a TUI or ACP local-session deletion command. No such operation exists today; when one is added, it must delete
  only the remote binding owned by that local session before removing the local record.
- Scanning `listSessions` and deleting unrecognized service sessions. They may be owned by another Konductor install or
  another client.
- Cross-process JSONL locking. Existing single-flight and same-ACP-connection duplicate-load guards remain required;
  two separate Konductor processes opening one local session concurrently remain unsupported.

## Acceptance

- [x] A new persisted Hosted local session reserves a v2 Hosted binding before its first turn; first activation creates
  the exact caller-provided id, and later turns reuse it.
- [x] TUI `/new` gets a distinct Hosted binding and does not delete the previous durable resource; TUI resume and
  startup `--resume`/`--continue` reconnect the selected binding before accepting a turn.
- [x] ACP `session/new` creates an isolated binding, `session/load` reconnects it, and connection/runtime close detaches
  durable bindings without deleting them. A duplicate active load of one local id in the same ACP connection is rejected.
- [x] Turn cancellation closes the log stream and cancels local collection but preserves the remote session. The local
  transcript may end at the persisted user entry while the service finishes or retains additional state; the next turn
  continues from the service-authoritative context.
- [x] Reconnect accepts `ACTIVE` and `IDLE`; boundedly polls `CREATING` and `UPDATING`; and fails actionably for
  `FAILED`, `DELETING`, `DELETED`, `EXPIRED`, wrong-agent, and missing sessions with existing local history. None of
  those cases silently creates a replacement.
- [x] A missing, entry-free reserved binding is the only create/retry case. Creation uses the deterministic id. A
  create `409` or ambiguous transport result triggers bounded `getSession` reconciliation; the design does not assume
  create is idempotent and cannot leak a second, unreferenced id.
- [x] Replacing or closing an ephemeral `--no-session` Hosted session performs best-effort `deleteSession` only;
  durable switch/close performs no `stopSession` or `deleteSession` call.
- [x] Session codec tests retain the v1 Prompt golden and add a v2 Hosted golden. New code reads v1 as Prompt/client-
  owned, reads v2 Hosted bindings, and rejects invalid partial bindings. Old binaries reject v2 instead of ignoring and
  later dropping the Hosted identity.
- [x] Existing v1 Prompt sessions resume unchanged. Existing pre-I028 Hosted transcripts have no recoverable server id
  and fail clearly when loaded as Hosted; users must start a new Hosted session.
- [x] Focused lifecycle, TUI, ACP, codec/store, full test, package, and documentation validation pass. Live validation
  proves new → second new → resume isolation/reconnect and delete-only ephemeral cleanup against Foundry.

## Context pack

Read only this first-pass context. Expand beyond it only when implementation proves it incomplete or contradicted.

### External SDK contract

Azure Agents Java SDK **2.2.0**, pinned at commit `ee28e96b521`:

- [`AgentsClient.createSession(String, VersionIndicator, String)`](https://github.com/Azure/azure-sdk-for-java/blob/ee28e96b521/sdk/ai/azure-ai-agents/src/main/java/com/azure/ai/agents/AgentsClient.java#L3887-L3915) —
  create with a caller-provided id that must be unique within the agent endpoint. The API does not promise idempotent
  create; reconcile an ambiguous result or `409` with `getSession`.
- `AgentsClient.getSession(String, String)` — reconnect and inspect the returned `AgentSessionResource`.
- `AgentsClient.deleteSession(String, String)` — idempotent documented delete (`204` for deleted or missing); use by
  itself because live service evidence shows concurrent stop/delete returns `409`.
- `AgentsClient.stopSession(String, String)` — terminal stop, not a detach or durable-resume operation.
- `AgentSessionResource` — id, version indicator, status, creation/last-access/expiry timestamps; expiry rolls 30 days
  from last activity.
- `AgentSessionStatus` — `ACTIVE`, `IDLE` (auto-suspended and resumed by the next invocation), transient `CREATING` /
  `UPDATING`, and terminal `FAILED` / `DELETING` / `DELETED` / `EXPIRED`.
- `hostedagents/SessionsSample.java`, `SessionsAsyncSample.java`, `AgentEndpointSample.java`, and
  `utils/HostedAgentsSampleUtils.java` — get/list/delete lifecycle, `agent_session_id` invocation, endless log-stream
  ownership, and delete-only sample cleanup.

Reference checkout (read-only):
`C:/Users/josealvar/code/azure_repos/azure-sdk-for-java/sdk/ai/azure-ai-agents`. Use `git show ee28e96b521:<path>`
because that checkout's working branch may be newer than 2.2.0.

### Specifications

- [`Hosted vs Prompt`](../spec/hosted-agents.md#hosted-vs-prompt),
  [`Create a server-side session`](../spec/hosted-agents.md#3-create-a-server-side-session), and
  [`Lifecycle and ownership`](../spec/hosted-agents.md#lifecycle--ownership).
- [`Entry model and on-disk schema`](../spec/sessions.md#entry-model--on-disk-schema) and
  [`Hosted bindings and resume`](../spec/sessions.md#hosted-bindings--resume).
- [`How it maps onto Konductor`](../spec/acp.md#how-it-maps-onto-konductor),
  [`Supported ACP methods`](../spec/acp.md#supported-acp-methods), and
  [`Overlap and cancellation policy`](../spec/acp.md#overlap-and-cancellation-policy).
- [`Hosted agents — SDK/service feedback`](../service_feedback/hosted_agents.md), especially items 2, 5, and 7.

### Source entry points

- `src/main/kotlin/com/konductor/core/models/Session.kt` — add the persisted Hosted binding without making local
  entries authoritative for Hosted history.
- `src/main/kotlin/com/konductor/session/SessionCodec.kt` — v1 read compatibility and Hosted-only v2 header rules.
- `src/main/kotlin/com/konductor/session/JsonlSessionStore.kt` and `SessionStore.kt` — create/persist binding metadata
  before activation and retain it across header rewrites.
- `src/main/kotlin/com/konductor/agent/AgentLoop.kt` — activate/switch the logical session before recording and running
  a Hosted turn; `/new` and resume must not leave provider state attached to the wrong session.
- `src/main/kotlin/com/konductor/provider/TurnRequest.kt` — retire the unused caller-supplied `sessionRef` path in favor
  of activated persisted ownership, subject to the minimal lifecycle surface selected by #30.
- `src/main/kotlin/com/konductor/provider/hosted/HostedProvider.kt` — remove unconditional switch/close deletion for
  durable bindings, status-aware reconnect, and preserve cancellation semantics.
- `src/main/kotlin/com/konductor/provider/hosted/HostedAgentClient.kt` and `AzureHostedAgentClient.kt` — expose caller-id
  creation and the session status/version data returned by the exact 2.2.0 SDK calls.
- `src/main/kotlin/com/konductor/conversation/ConversationController.kt` — TUI `/new`/resume error and switching behavior.
- `src/main/kotlin/com/konductor/acp/AcpSessionRuntime.kt` and `KonductorAcpAgent.kt` — per-session binding creation,
  load activation, duplicate ownership, cancellation, and detach-only connection close.
- `src/main/kotlin/com/konductor/Main.kt` — initial Hosted create/resume/continue composition and final close ownership.

### Tests

- `src/test/kotlin/com/konductor/provider/hosted/HostedProviderTest.kt` — create/reconnect status matrix, durable detach,
  ephemeral delete, cancellation, and no switch-delete.
- `src/test/kotlin/com/konductor/agent/AgentLoopSessionTest.kt` — activation ordering plus `/new`/resume retargeting.
- `src/test/kotlin/com/konductor/conversation/SessionCommandsTest.kt` — TUI new/resume isolation and actionable failures.
- `src/test/kotlin/com/konductor/acp/AcpSessionRuntimeFactoryTest.kt` — distinct bindings, same-id duplicate rejection,
  and detach-only factory close.
- `src/test/kotlin/com/konductor/acp/KonductorAgentSessionTest.kt` — load reconnect and cancellation preservation.
- `src/test/kotlin/com/konductor/session/SessionCodecTest.kt`, `JsonlSessionStoreTest.kt`, and
  `src/test/resources/session/current-session-v1.jsonl` — retain v1 compatibility and add the v2 Hosted golden.

### Targeted searches

```bash
rg -n "sessionRef|currentSession|ensureSession|createSession|getSession|stopSession|deleteSession" \
  src/main/kotlin src/test/kotlin docs/spec docs/service_feedback
rg -n "newSession\(|resume\(|resolveInitialSession|createSession\(|loadSession\(" \
  src/main/kotlin src/test/kotlin
rg -n "SCHEMA_VERSION|HeaderLine|promptAgentName|current-session-v1" \
  src/main/kotlin src/test/kotlin src/test/resources docs/spec/sessions.md
```

## Decisions and constraints

- **Durable, not nominal resume.** Hosted history cannot be reconstructed from JSONL. Keeping the exact remote session
  or failing explicitly is more honest than showing a transcript while invoking an empty replacement.
- **The local session owns identity; Foundry owns conversation state.** JSONL remains a display/audit trail. It can lag
  the service after cancellation and is never replayed to repair or replace a Hosted session.
- **A Hosted binding is the schema discriminator.** Prompt headers remain v1 and unchanged. Hosted headers use v2 with
  both `hostedAgentName` and `hostedSessionId`; partial bindings are invalid. This avoids a generic persisted kind enum
  while ensuring old binaries reject metadata they cannot preserve.
- **Deterministic reservation closes the create/persist leak.** The Konductor UUID is reserved as the service id before
  network creation. Empty+missing means safe first creation; non-empty+missing means lost authoritative history and
  must fail. Because 2.2.0 promises uniqueness, not idempotent create, `409`/ambiguous results are reconciled by bounded
  `getSession` polling rather than treated as permission to allocate another id.
- **Durable resources are detached, not destroyed.** `/new`, resume, ACP connection close, and normal provider close do
  not imply deletion. Foundry auto-suspends idle sessions and expires them 30 days after last activity, bounding idle
  resource lifetime. An eventual explicit local delete must delete only its owned binding; no global service sweep.
- **Ephemeral resources remain runtime-owned.** With `--no-session`, no durable handle exists, so replacement/close uses
  best-effort delete-only cleanup.
- **Never stop then delete.** Preserve the live-verified workaround in service feedback: delete alone; cleanup errors do
  not mask an already completed user turn.
- **One active local owner per runtime.** Retain single-flight turns and reject duplicate activation/load of one local
  id within an ACP connection. Cross-process locking is outside this issue and remains unsupported.
- **#30 consumes this contract.** It should provide the smallest Foundry-specific activation/detach ownership surface;
  this packet does not preselect a universal nullable provider API. #29 may share that surface for capabilities but
  does not change Hosted resume semantics. The consuming #30/#28 implementation must also retire the stale
  `TurnRequest.sessionRef` sketch in `architecture.md`; this design PR deliberately avoids that agent-owned spec.

## Validation

```bash
./mvnw -q -Dtest=HostedProviderTest,AgentLoopSessionTest,SessionCommandsTest,\
AcpSessionRuntimeFactoryTest,KonductorAgentSessionTest,SessionCodecTest,JsonlSessionStoreTest test
./mvnw -q test
./mvnw -q package
node scripts/validate-docs.mjs
git diff --check
```

Live validation against the resource documented in `docs/service_feedback/hosted_agents.md`:

1. Start persisted Hosted session A, complete two turns, and record its local/server ids.
2. `/new` session B and prove B does not see A's context and has a different server id.
3. Restart and resume A; prove the same server id and server-owned context continue.
4. Repeat isolation/reconnect through ACP `session/new` and `session/load`.
5. Cancel a Hosted turn, then continue the same binding without creating or deleting another session.
6. Run `--no-session`, exit, and verify delete-only cleanup; verify normal durable close does not delete A or B.

## Completion

Record the final acceptance-completing implementation PR and resulting behavior here. Keep issue #28 open through this
design PR; use `Closes #28` only after implementation, tests, docs, and live validation satisfy acceptance.
