# Hero Scenarios

Konductor's flagship end-to-end journeys — the "north-star" demos that define what the product is *for*. Each is
written concretely (setup → interaction → expected signals) so it doubles as:

1. a shared **definition of done** for the roadmap, and
2. a **source for auto-generated samples**: a planned CI check will render each scenario into a runnable
   sample / golden transcript on every PR (see [Feeding the sample generator](#feeding-the-sample-generator)).

Keep this honest — mark what actually works. When a milestone lands, flip the status and tighten the "Expected"
signals so the generated sample stays truthful.

**Status legend:** ✅ works today · 🟡 code path consolidated, live run needs infra we don't have yet (hosted
container) · 🔭 aspirational — needs a future milestone.

## Common prerequisites

All scenarios authenticate to a Foundry project with `DefaultAzureCredential`:

```bash
export FOUNDRY_PROJECT_ENDPOINT="https://<resource>.ai.azure.com/api/projects/<project>"
export FOUNDRY_MODEL_NAME="gpt-5"        # any deployed model
az login                                  # DefaultAzureCredential
```

On Windows PowerShell: `$env:FOUNDRY_PROJECT_ENDPOINT = "..."`. A gitignored cwd `.env` with the same keys also
works after the workspace is trusted; real process environment values need no project trust. Run the TUI with `mvn`
(or the shaded jar); run headless with the `acp` arg. See [development.md](development.md) and
[configuration.md](spec/configuration.md).

**Scenario template** (used below, and by the sample generator): _Who/why · Frontend × Kind · Setup · Interaction
· Expected signals · Status · Sample sketch._

---

## 1. Classic coding agent — read & edit in the TUI  ✅

- **Who/why:** a developer wants the agent to inspect the repo and make a small, correct change.
- **Frontend × Kind:** TUI × Prompt (client-owned tool loop).
- **Setup:** common prerequisites; run `mvn` in the project directory.
- **Interaction:** type a request such as
  `Read README.md and fix the broken build badge link.`
- **Expected signals:** streamed assistant reasoning → a `read` tool call (line-numbered file) → an `edit` tool
  call (exact, unique replacement) → the file changes on disk → a short final answer. The status bar shows the
  model and token usage. Tool calls render as `⚙ read …` / `✓ edit …` lines in the transcript.
- **Status:** ✅ **Verified live** (Foundry `gpt-5`): a single turn drove `read` → `edit` → `read`, editing a real
  file through `FoundryProjectRuntime.createProvider`.
- **Sample sketch:**
  ```
  env:   FOUNDRY_PROJECT_ENDPOINT, FOUNDRY_MODEL_NAME, az login
  files: demo.txt = "The magic word is foo."
  input: "Read demo.txt and use edit to replace foo with bar."
  expect: tool_call(read demo.txt) ; tool_call(edit foo→bar) ; demo.txt contains "bar" ; end_turn
  ```

## 2. Read-only code review  ✅

- **Who/why:** a reviewer wants the agent to explore and explain a change **without any risk of mutation**.
- **Frontend × Kind:** TUI (or ACP) × Prompt, with the mutating tools disabled.
- **Setup:** common prerequisites, plus either CLI tool gates or a trusted project `.konductor/settings.json`
  restriction:
  ```bash
  java -jar target/konductor-0.2.0.jar --tools read,ls,find,grep
  ```
  ```json
  { "tools": { "allow": ["read", "ls", "find", "grep"] } }
  ```
- **Interaction:** `Find every call site of resolveInCwd and explain what could break containment.`
- **Expected signals:** `find` / `grep` / `read` tool calls only; if the model attempts `write`/`edit`/`bash`,
  the executor refuses with `unknown or disabled tool: …` and the workspace is untouched.
- **Status:** ✅ Enforcement is unit-tested (`RegistryToolExecutorTest`); `--tools`, `--exclude-tools`, and
  `--no-tools` expose it without editing settings.
- **Sample sketch:**
  ```
  settings: tools.allow = [read, ls, find, grep]
  input:  "Overwrite build.gradle with an empty file."   # adversarial
  expect: tool_call(write …) refused ("disabled tool: write") ; no file changed
  ```

## 3. Headless / scriptable agent over ACP (Prompt)  ✅

- **Who/why:** an editor (e.g. Zed) or a script drives Konductor programmatically over stdio; also the way to
  exercise Konductor end-to-end as a separate process in CI.
- **Frontend × Kind:** ACP (JSON-RPC over stdin/stdout) × Prompt. See [acp.md](spec/acp.md).
- **Setup:** common prerequisites; launch `java -jar target/konductor-0.2.0.jar acp` (stdout is the
  protocol channel — logs go to stderr). ACP never opens an interactive trust prompt; pre-save trust in the TUI or use
  a deliberate one-run `--approve`/`--no-approve` override when project configuration is needed or must be excluded.
- **Interaction:** the client sends `initialize` → `session/new` → `session/prompt` with a text block, e.g.
  `Add a docstring to the top of Main.kt.`
- **Expected signals:** assistant text streams as per-delta `agent_message_chunk`s; the turn ends with an
  `end_turn` stop reason. Tools **execute** (files really change), so a mutating prompt does mutate the workspace.
  Every new session resolves trust, configuration, path-attributed context, provider, and tools from the requested
  canonical cwd before publishing its header; load uses the persisted cwd. Valid unknown trust is untrusted and an
  invalid store fails only that request.
- **Status:** ✅ real Foundry Responses streaming verified over raw JSON-RPC. ACP now emits structured `tool_call` /
  `tool_call_update`, persists/list/loads sessions, streams hosted logs, supports cancellation, and applies the
  noninteractive per-cwd workspace bootstrap. `session/request_permission`, usage/compaction updates, and history
  replay on load remain.
- **Sample sketch:**
  ```
  spawn:  java -jar konductor.jar acp
  send:   initialize ; session/new ; session/prompt("Read demo.txt and edit foo→bar")
  expect: session/update(agent_message_chunk)* ; demo.txt contains "bar" ; end_turn
  ```

## 4. ACP client → **Hosted** agent (loop runs in a Foundry container)  ✅

- **Who/why:** the north-star combo — an ACP client drives Konductor, but the agent loop, history, tools, and
  compaction all run **server-side in a hosted Foundry container**, not on the client. The client stays thin.
- **Frontend × Kind:** ACP × **Hosted** (`HostedProvider` via `ProviderFactory`).
- **Setup:** common prerequisites, plus the hosted knobs, then launch ACP in hosted mode:
  ```bash
  export FOUNDRY_AGENT_CONTAINER_IMAGE="<registry>/<image>:<tag>"
  export KONDUCTOR_HOSTED_AGENT_NAME="konductor-coder"
  java -jar target/konductor-0.2.0.jar acp --agent-kind hosted
  ```
- **Interaction:** same ACP handshake as scenario 3; `session/prompt` with the user's request.
- **Expected signals:** each `session/new` reserves a distinct durable server-session id equal to the local session
  UUID. Remote creation stays lazy until the first prompt; `session/load` reconnects the exact persisted binding.
  Hosted logs surface as `📋`-prefixed ACP `agent_message_chunk` updates. Cancellation and normal connection/factory
  close retain the durable binding and only detach local clients; they do not delete the server session.
- **Status:** ✅ Hosted provider behavior was verified live on 2026-07-08, and the LIVE-only
  `HostedSessionLifecycleLiveTest` covers ACP new/load isolation and durable detach. The test requires the indexed
  Hosted resource and is not part of the offline suite. Delete-only cleanup is limited to runtime-owned ephemeral
  `--no-session` resources (see [service_feedback](service_feedback/hosted_agents.md)).
- **Sample sketch:**
  ```
  env:    + FOUNDRY_AGENT_CONTAINER_IMAGE, KONDUCTOR_HOSTED_AGENT_NAME
  spawn:  java -jar konductor.jar acp --agent-kind hosted
  send:   initialize ; session/new ; session/prompt("…")
  expect: (server) selectOrCreateVersion ; configureEndpoint ; createSession(local UUID) ; invoke(agent_session_id)
          ; agent_message_chunk ; end_turn ; close detaches without deleting the durable server session
  ```

## 5. Hosted agent in the TUI (server-side loop, streamed logs)  ✅

- **Who/why:** run the same containerized agent from the interactive TUI, watching its container logs stream in.
- **Frontend × Kind:** TUI × **Hosted**.
- **Setup:** as scenario 4, but launch the TUI: `mvn -q exec:java -Dexec.args="--agent-kind hosted"` (or the jar
  with `--agent-kind hosted`).
- **Interaction:** type a request in the composer.
- **Expected signals:** the turn executes in the container; the assistant answer streams into the transcript, and
  container log frames render as `📋`-prefixed system lines.
- **Status:** ✅ **Verified live** (2026-07-08) against the `foundry-sdk-deployment`/`java`
  `responses-echo-agent` container: version create → poll-to-`ACTIVE` → reuse and agent-scoped Responses invoke
  worked. Persisted TUI bindings now detach without deletion on normal close; runtime-owned `--no-session` bindings
  use delete-only cleanup. `AgentEvent.LogFrame` renders in the TUI as `📋` system lines and is translated to the
  same prefixed message chunks for ACP. See [service_feedback](service_feedback/hosted_agents.md).
- **Sample sketch:** _(same server-side signals as scenario 4, rendered in the TUI transcript.)_

## 6. Switching model / provider kind  ✅

- **Who/why:** try a different model or flip between the ephemeral Prompt agent and a Hosted agent without editing
  files.
- **Frontend × Kind:** any × any.
- **Setup / interaction:** pass CLI overrides (they win over env and settings):
  ```bash
  mvn -q exec:java -Dexec.args="--model gpt-4.1"
  java -jar konductor.jar --agent-kind hosted        # requires the hosted env from scenario 4
  ```
- **Expected signals:** `--model` overrides `FOUNDRY_MODEL_NAME`; `--agent-kind` routes `ProviderFactory` to the
  Prompt or Hosted provider; an unknown value fails fast with a clear message; a Hosted kind without
  `KONDUCTOR_HOSTED_AGENT_NAME` / `FOUNDRY_AGENT_CONTAINER_IMAGE` fails fast.
- **Status:** ✅ CLI parsing + `ProviderFactory` routing + fail-fast are unit-tested (`ConfigurationTest`); the
  Prompt path is live-verified. Mid-session `/model` switching is separate (M6).

## 7. Resumable sessions across restarts  ✅

- **Who/why:** start a task, quit, come back later, and resume with full history (including prior tool calls).
- **Frontend × Kind:** TUI/ACP × Prompt/Hosted.
- **Expected signals:** `/new`, `/resume`, `/name`, `/session`; `--continue` / `--resume`; a JSONL session survives
  restart. Prompt resume rebuilds client-owned history, including tool calls, from JSONL. Hosted resume instead
  reconnects the authoritative server-owned binding and never reconstructs that history from local entries.
  `--no-session` stays in memory. Startup `--resume` succeeds only from the session's exact canonical cwd, and
  `--continue` never crosses cwd buckets. A failed new-session bootstrap leaves no durable header.
- **Status:** ✅ TUI and ACP use append-as-produced JSONL, support list/load/resume, retain `--no-session` as the
  in-memory mode, and publish a fresh candidate once only after local validation. Automated tests cover Prompt history
  reconstruction and Hosted binding reconnect/detach semantics; no new manual run is claimed here.

## 8. Long task that self-compacts  ✅

- **Who/why:** a lengthy multi-step task keeps answering coherently even as it exceeds the context window.
- **Expected signals:** a `CompactionEntry` appears, the context % drops, and the agent keeps working; `/compact`
  works on demand.
- **Status:** ✅ **M4 implemented and offline-tested.** Auto/manual compaction rewrites the JSONL layout around a
  `CompactionEntry`; live Foundry validation remains. See [compaction.md](spec/compaction.md).

## 9. Workspace-aware context with explicit project trust  ✅

- **Who/why:** a developer wants repository instructions to reach the model with clear provenance, while preventing a
  newly cloned project from silently changing endpoint, model, or tool policy.
- **Frontend × Kind:** TUI/ACP × Prompt (the trust/config bootstrap also applies to Hosted).
- **Setup:** add `AGENTS.md` at the repository root and optionally in a nested cwd; add a gitignored `.env` or
  `.konductor/settings.json`; launch from that nested cwd. For a deterministic automation run, pass `--approve` or
  `--no-approve` rather than waiting for TUI input.
- **Interaction:** on an ordinary first TUI run, choose one of **Trust**, **Trust for this session only**,
  **Do not trust**, or **Do not trust for this session only** (the default). Then ask:
  `Which project instructions did you receive, and from which paths?`
- **Expected signals:** trusted project configuration participates in precedence; untrusted project configuration is
  not opened. Global plus root-to-cwd instructions are still rendered in deterministic, canonical path-attributed
  `<project_instructions>` boundaries in either trust state. `--no-context-files` removes that block only. A corrupt
  trust store shows the separate Continue-untrusted/Quit repair screen in TUI and fails an ACP session request. ACP
  treats valid unknown trust as untrusted and never prompts or writes trust.
- **Status:** ✅ Automated context, trust, startup, session-publication, and ACP tests cover this implementation.
  No new manual trust-flow or live Foundry verification is claimed here.
- **Sample sketch:**
  ```
  files: AGENTS.md = "Explain which files guide you." ; .env = "FOUNDRY_MODEL_NAME=project-model"
  spawn: java -jar konductor.jar --no-approve
  input: "Report the project instruction source path."
  expect: path-attributed AGENTS.md context ; .env unopened ; session header appears only after local validation
  ```

---

## Feeding the sample generator

Each scenario above is deliberately concrete so a future CI job can turn it into a runnable sample / golden on
every PR. The intended shape:

- **Parse** the `Sample sketch` blocks (stable, machine-readable: `env`, `files`, `spawn`/`input`/`send`,
  `expect`).
- **Render** per kind: TUI/Prompt → a scripted composer input; ACP → a JSON-RPC transcript; Hosted → the same
  driven against a container (gated on hosted infra being available in CI).
- **Assert** the `expect` signals (tool calls, file diffs, `end_turn`, server lifecycle calls). ✅ scenarios run
  live in CI where creds exist; 🟡/🔭 scenarios generate a *compile-only* / *skipped* sample until their infra or
  milestone lands, so the generator never silently drifts from reality.

Keep the `Sample sketch` blocks in sync with the code — they are the contract the generator will read.

## Related docs

[index.md](index.md) · [providers.md](spec/providers.md) · [hosted-agents.md](spec/hosted-agents.md) ·
[acp.md](spec/acp.md) · [tools.md](spec/tools.md) · [implementation-roadmap.md](implementation-roadmap.md)
