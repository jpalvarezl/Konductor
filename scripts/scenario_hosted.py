#!/usr/bin/env python3
"""Hero scenario: a single turn against a Hosted agent, driven over ACP.

Mirrors docs/hero-scenario.md #5 (Hosted over ACP): launches Konductor with ``--agent-kind hosted`` so the
provider stack is the server-side agent (a container image running in Foundry) instead of stateless Prompt
inference. Sends one prompt and prints whatever the hosted agent streams back. With the sample
``responses-echo-agent`` container this echoes the prompt, so the assertion is simply that the turn produced
some text and ended with ``end_turn``.

Hosted needs three settings beyond the Prompt ones (a cwd .env works — see the repo root .env):
  FOUNDRY_PROJECT_ENDPOINT, FOUNDRY_AGENT_CONTAINER_IMAGE, KONDUCTOR_HOSTED_AGENT_NAME
plus ``az login``. Provisioning a fresh agent version can take a minute or two (image pull + activation), so
the prompt timeout is generous; re-runs against an already-active version are fast.

Run (Windows):  py scripts/scenario_hosted.py
Run (POSIX):    python3 scripts/scenario_hosted.py
"""
from __future__ import annotations

import os
import sys

from acp_client import AcpClient, default_jar, repo_root, subprocess_env


def main() -> int:
    jar = os.environ.get("KONDUCTOR_JAR", default_jar())
    if not os.path.exists(jar):
        print(f"jar not found: {jar}\nBuild it first: mvn -DskipTests package", file=sys.stderr)
        return 2

    env = subprocess_env()
    missing = [k for k in ("FOUNDRY_PROJECT_ENDPOINT", "FOUNDRY_AGENT_CONTAINER_IMAGE", "KONDUCTOR_HOSTED_AGENT_NAME")
               if not env.get(k)]
    if missing:
        print(f"missing hosted settings: {', '.join(missing)}\nSet them in .env or the shell (see the module "
              "docstring).", file=sys.stderr)
        return 2

    root = repo_root()
    verbose = "--verbose" in sys.argv

    print(f"Spawning Konductor (acp, hosted) — agent '{env['KONDUCTOR_HOSTED_AGENT_NAME']}'…")
    print("(first run may take a minute or two while the agent version activates)")
    # args select the Hosted provider; --agent-kind is parsed before the `acp` frontend is chosen.
    with AcpClient(
        jar=jar,
        cwd=root,
        env=env,
        args=("acp", "--agent-kind", "hosted"),
        echo_stderr=verbose,
    ) as acp:
        acp.initialize()
        session = acp.new_session(cwd=root)
        result = acp.prompt(session, "Hello from the hosted scenario script.", timeout=600.0)

    print(f"\nHosted agent: {result.text.strip()}")
    print(f"Stop reason: {result.stop_reason}")

    ok = bool(result.text.strip()) and result.stop_reason == "end_turn"
    print("\nPASS — the hosted agent replied over ACP" if ok else "\nFAIL — no hosted reply")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
