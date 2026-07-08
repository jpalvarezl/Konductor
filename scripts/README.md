# scripts/

Tiny, dependency-free Python scripts that drive Konductor **headlessly over ACP** to replicate the
[hero scenarios](../docs/hero-scenario.md) and some tests. They let you (and, later, CI) verify end-to-end
behavior without the interactive TUI — the TUI takes over the terminal and can't be scripted, but the `acp`
frontend speaks JSON-RPC over stdio, which scripts *can* drive.

These are **stdlib-only** (`subprocess`, `json`, `threading`) — nothing to `pip install`.

## Prerequisites

1. **Build the jar:** `mvn -DskipTests package` → `target/konductor-0.1.0-SNAPSHOT.jar`.
2. **Foundry creds:** `FOUNDRY_PROJECT_ENDPOINT` + `FOUNDRY_MODEL_NAME` in the environment or a gitignored
   repo-root `.env` (the scripts forward the repo `.env` to the subprocess automatically). Plus `az login`.
3. **Python 3.9+.** On this Windows box use `py`; on POSIX use `python3`.

## Scripts

| Script | Scenario | What it checks |
|--------|----------|----------------|
| `scenario_hello.py` | [#3 headless ACP (Prompt)](../docs/hero-scenario.md) | one prompt → streamed answer → `end_turn` |
| `scenario_read_edit.py` | [#1 read & edit](../docs/hero-scenario.md) over ACP | agent reads a temp file and edits it via tools; asserts the on-disk diff |
| `acp_client.py` | — | the reusable ACP JSON-RPC client the scenarios import |

## Run

```powershell
# Windows
py scripts\scenario_hello.py
py scripts\scenario_read_edit.py
py scripts\scenario_read_edit.py --verbose   # also echo Konductor's stderr logs
```

```bash
# POSIX
python3 scripts/scenario_hello.py
python3 scripts/scenario_read_edit.py
```

Each prints a human-readable trace and exits `0` on PASS, non-zero on FAIL — so they double as smoke tests.
Override the jar with `KONDUCTOR_JAR=/path/to/other.jar`.

## Writing a new scenario

Import the client and drive a turn:

```python
from acp_client import AcpClient, default_jar, repo_root, subprocess_env

with AcpClient(jar=default_jar(), cwd=repo_root(), env=subprocess_env()) as acp:
    acp.initialize()
    session = acp.new_session(cwd=repo_root())
    result = acp.prompt(session, "…your prompt…")
    print(result.text, result.stop_reason)
```

Keep the assertion tied to an **observable signal** (streamed text, `stop_reason`, or a file diff) so the
script is a real check, not just a demo. Mirror a block from
[docs/hero-scenario.md](../docs/hero-scenario.md) so the two stay in sync — these scripts are the intended
seed for the planned "generate samples from hero scenarios" CI check.

## Notes & limits

- **cwd = tool scope.** Konductor scopes its tools to its process cwd, so `scenario_read_edit.py` launches it
  in a throwaway temp dir and forwards the repo `.env` creds via `subprocess_env()`.
- **Tool-call visibility.** Tools *execute* over ACP, but structured `tool_call` updates aren't surfaced yet
  (ACP Phase C) — so scenarios assert the *effect* (file diff), not the tool events.
- **Hosted scenarios** aren't scripted here yet — they need a running hosted container. See
  [docs/service_feedback/hosted_agents.md](../docs/service_feedback/hosted_agents.md) for the
  `wr-load -Resource foundry-sdk-deployment -Flavor java` setup.
