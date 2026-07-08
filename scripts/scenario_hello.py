#!/usr/bin/env python3
"""Hero scenario smoke: a single Prompt turn over ACP (no tools).

Mirrors docs/hero-scenario.md #3 (headless ACP, Prompt) at its simplest: send one prompt, print the streamed
answer, assert the turn ends with ``end_turn``.

Prereqs: build the jar (``mvn -DskipTests package``), set FOUNDRY_PROJECT_ENDPOINT + FOUNDRY_MODEL_NAME (a cwd
.env works too), and ``az login``.

Run (Windows):  py scripts/scenario_hello.py
Run (POSIX):    python3 scripts/scenario_hello.py
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

    root = repo_root()
    verbose = "--verbose" in sys.argv

    print("Spawning Konductor (acp) and sending one prompt…")
    with AcpClient(jar=jar, cwd=root, env=subprocess_env(), echo_stderr=verbose) as acp:
        acp.initialize()
        session = acp.new_session(cwd=root)
        result = acp.prompt(session, "Say hello in one short sentence.")

    print(f"\nAssistant: {result.text}")
    print(f"Stop reason: {result.stop_reason}")

    ok = bool(result.text.strip()) and result.stop_reason == "end_turn"
    print("\nPASS" if ok else "\nFAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
