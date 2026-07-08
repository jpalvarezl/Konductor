#!/usr/bin/env python3
"""Hero scenario: the classic coding agent reads a file and edits it, driven over ACP.

Mirrors docs/hero-scenario.md #1 (read & edit) and #3 (headless ACP): creates a throwaway file in a temp dir,
tells Konductor to replace a word in it via tools, then asserts the file changed on disk. Because tools
*execute* over ACP (even though tool-call updates aren't surfaced yet — Phase C), the on-disk diff is the
signal.

Prereqs: build the jar (``mvn -DskipTests package``), set FOUNDRY_PROJECT_ENDPOINT + FOUNDRY_MODEL_NAME (a cwd
.env works too), and ``az login``.

Run (Windows):  py scripts/scenario_read_edit.py
Run (POSIX):    python3 scripts/scenario_read_edit.py
"""
from __future__ import annotations

import os
import sys
import tempfile

from acp_client import AcpClient, default_jar, subprocess_env


def main() -> int:
    jar = os.environ.get("KONDUCTOR_JAR", default_jar())
    if not os.path.exists(jar):
        print(f"jar not found: {jar}\nBuild it first: mvn -DskipTests package", file=sys.stderr)
        return 2

    verbose = "--verbose" in sys.argv
    # Forward the repo-root .env (Foundry creds) since the subprocess cwd is the temp workspace, not the repo.
    env = subprocess_env()

    with tempfile.TemporaryDirectory(prefix="konductor-scenario-") as workdir:
        target = os.path.join(workdir, "demo.txt")
        with open(target, "w", encoding="utf-8") as handle:
            handle.write("The magic word is foo.\n")
        print(f"Workspace: {workdir}")
        print("Before:   The magic word is foo.")

        # cwd = the temp dir, so the agent's tools are scoped to it and can only touch demo.txt.
        with AcpClient(jar=jar, cwd=workdir, env=env, echo_stderr=verbose) as acp:
            acp.initialize()
            session = acp.new_session(cwd=workdir)
            result = acp.prompt(
                session,
                "There is a file named demo.txt in your working directory. Read it, then use the edit tool "
                "to replace the word foo with bar. Do not ask for confirmation.",
            )

        with open(target, encoding="utf-8") as handle:
            after = handle.read().strip()

    print(f"After:    {after}")
    print(f"\nAssistant: {result.text.strip()}")
    print(f"Stop reason: {result.stop_reason}")

    ok = "bar" in after and "foo" not in after
    print("\nPASS — the file was edited via tools" if ok else "\nFAIL — file unchanged")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
