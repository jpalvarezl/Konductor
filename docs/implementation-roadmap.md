# Implementation Roadmap

> **Status:** Stub — milestone outline. Flesh out acceptance criteria for the hackathon. See [index.md](index.md).

After **M0**, the **Prompt track (M1–M4)** and the **Hosted track (M5)** can proceed in parallel across
contributors; **M6** polishes both.

## M0 — Dependencies & provider seam
TODO: add `azure-ai-agents`, `azure-ai-projects`, `azure-identity`, `kotlinx-coroutines`, a JSON lib to `pom.xml`;
define the `AgentProvider` interface + unified event stream + config; build clients (`buildResponsesClient`, and
`buildAgentScopedOpenAIClient` for hosted). **Acceptance:** TODO.

## M1 — Prompt: single-turn inference in the TUI
TODO: replace the echo `ConversationController`; send a prompt, render the response (non-streaming first).
**Acceptance:** TODO.

## M2 — Prompt: function-tool loop + tools
TODO: harness-owned tool loop; first tools (`read`, `ls`, `bash`, `edit`/`write`) with truncation. **Acceptance:** TODO.

## M3 — Prompt: sessions
TODO: in-memory transcript → JSONL persistence + resume. **Acceptance:** TODO.

## M4 — Prompt: compaction
TODO: token tracking via `ResponseUsage`, threshold trigger, summary entry. **Acceptance:** TODO.

## M5 — Hosted provider
TODO: deploy/select a code-based agent version, create a session, invoke via the agent-scoped Responses client
(`agent_session_id`), stream session logs into the TUI, transfer session files. **Acceptance:** TODO.

## M6 — Streaming & polish
TODO: switch Prompt responses to streaming; unify status bar (tokens/context %/cost); slash-commands;
provider/agent-kind switch. **Acceptance:** TODO.

## References

- [index.md](index.md) · [architecture.md](architecture.md) · [providers.md](providers.md) · [hosted-agents.md](hosted-agents.md)
