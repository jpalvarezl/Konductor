# Architecture

> **Status:** Stub — outline only. Author this for the hackathon. See [index.md](index.md) for the confirmed
> decisions, terminology map, and SDK grounding facts. This is the **keystone** doc: define the interfaces here,
> the other docs elaborate them.

**Purpose:** the big picture — how the TUI, session/agent loop, provider seam, and Azure SDK fit together, and how
one turn flows end to end.

## Layers

TODO: describe the layers and their responsibilities. Target shape:
`TUI → Conversation/Agent loop → AgentProvider → Azure SDK (ResponsesClient / agent-scoped OpenAI client)`.

TODO: component diagram (ASCII or image under `docs/images/`).

## The `AgentProvider` seam

TODO: define the interface every provider implements and the **unified event stream** it emits (text deltas, tool
calls, tool results, log frames, usage, turn-complete, errors). See [providers.md](providers.md).

## Two execution models

TODO: contrast the **harness-owned loop** (Prompt provider — client runs the tool loop, owns history + compaction)
with the **server-owned loop** (Hosted provider — container runs the loop; harness streams logs + bridges files).
See [hosted-agents.md](hosted-agents.md).

## One-turn data flow

TODO: step-by-step for a Prompt turn, including function-tool round-trips:
submit → build request `input` from transcript → `createAzureResponse` → detect `functionCall` items → execute tools
locally → append `ResponseFunctionToolCallOutputItem` → re-request → assistant text → persist → render.

## Threading & concurrency

TODO: coroutine model — the agent loop runs off the Lanterna input thread; how events marshal back to the render
loop; cancellation/abort.

## Lifecycle, errors & retry

TODO: startup/shutdown, provider construction, error surfaces, retry/backoff, context-overflow → compaction.

## References

- [index.md](index.md) · [providers.md](providers.md) · [sessions.md](sessions.md) · [tui.md](tui.md)
- Seam to replace: `conversation/ConversationController.submit()`.
