#!/usr/bin/env python3
"""Minimal, dependency-free ACP (Agent Client Protocol) client for Konductor.

Konductor speaks ACP (JSON-RPC 2.0 over stdio) when launched with the ``acp`` argument. This module
spawns that process and drives it: ``initialize`` -> ``session/new`` -> ``session/prompt``, collecting the
streamed ``session/update`` notifications and the final ``end_turn`` result.

stdout of the subprocess is the protocol channel (one JSON object per line); stderr is logs — we drain it
on a background thread and optionally echo it.

Stdlib only (subprocess, json, threading, queue) — no pip installs. See ../docs/spec/acp.md.
"""
from __future__ import annotations

import json
import queue
import subprocess
import sys
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Optional


@dataclass
class PromptResult:
    """Outcome of one ``session/prompt`` turn."""

    text: str                       # concatenated assistant message chunks
    stop_reason: Optional[str]      # e.g. "end_turn"
    updates: list[dict] = field(default_factory=list)  # raw session/update payloads (tool calls, logs, …)


class AcpClient:
    """Drives a Konductor ``acp`` subprocess over JSON-RPC.

    Use as a context manager so the subprocess is always cleaned up::

        with AcpClient(jar="target/konductor-0.1.0-SNAPSHOT.jar") as acp:
            acp.initialize()
            sid = acp.new_session(cwd=".")
            result = acp.prompt(sid, "Read demo.txt and replace foo with bar.")
            print(result.text)
    """

    def __init__(
        self,
        jar: str,
        java: str = "java",
        args: tuple[str, ...] = ("acp",),
        cwd: Optional[str] = None,
        env: Optional[dict] = None,
        echo_stderr: bool = False,
        on_update: Optional[Callable[[dict], None]] = None,
    ) -> None:
        self._echo_stderr = echo_stderr
        self._on_update = on_update
        self._next_id = 0
        self._merged: "queue.Queue[dict]" = queue.Queue()

        self._proc = subprocess.Popen(
            [java, "-jar", jar, *args],
            cwd=cwd,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,  # line-buffered
        )
        self._reader = threading.Thread(target=self._read_stdout, daemon=True)
        self._reader.start()
        self._errpump = threading.Thread(target=self._drain_stderr, daemon=True)
        self._errpump.start()

    # -- lifecycle -----------------------------------------------------------------

    def __enter__(self) -> "AcpClient":
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    def close(self) -> None:
        try:
            if self._proc.stdin and not self._proc.stdin.closed:
                self._proc.stdin.close()  # EOF -> Konductor shuts the transport down and exits
        except OSError:
            pass
        try:
            self._proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self._proc.kill()

    # -- public API ----------------------------------------------------------------

    def initialize(self, protocol_version: int = 1) -> dict:
        return self._request(
            "initialize",
            {"protocolVersion": protocol_version, "clientCapabilities": {}},
        )

    def new_session(self, cwd: str = ".", mcp_servers: Optional[list] = None) -> str:
        result = self._request("session/new", {"cwd": cwd, "mcpServers": mcp_servers or []})
        session_id = result.get("sessionId") or result.get("session_id")
        if not session_id:
            raise RuntimeError(f"session/new returned no sessionId: {result}")
        return session_id

    def prompt(self, session_id: str, text: str, timeout: float = 300.0) -> PromptResult:
        """Send one user turn; block until ``end_turn``. Returns the assembled assistant text + updates."""
        request_id = self._send("session/prompt", {
            "sessionId": session_id,
            "prompt": [{"type": "text", "text": text}],
        })

        chunks: list[str] = []
        updates: list[dict] = []
        while True:
            message = self._await_message(timeout)
            if message is None:
                raise TimeoutError("timed out waiting for the prompt to complete")

            if message.get("_kind") == "response" and message.get("id") == request_id:
                if "error" in message:
                    raise RuntimeError(f"session/prompt error: {message['error']}")
                stop = (message.get("result") or {}).get("stopReason")
                return PromptResult(text="".join(chunks), stop_reason=stop, updates=updates)

            if message.get("_kind") == "update":
                payload = message.get("params", {})
                updates.append(payload)
                if self._on_update:
                    self._on_update(payload)
                chunks.append(_extract_chunk_text(payload))

    # -- internals -----------------------------------------------------------------

    def _request(self, method: str, params: dict, timeout: float = 60.0) -> dict:
        request_id = self._send(method, params)
        while True:
            message = self._await_message(timeout)
            if message is None:
                raise TimeoutError(f"timed out waiting for a response to {method}")
            if message.get("_kind") == "response" and message.get("id") == request_id:
                if "error" in message:
                    raise RuntimeError(f"{method} error: {message['error']}")
                return message.get("result") or {}
            # ignore stray notifications that arrive before this response

    def _send(self, method: str, params: dict) -> int:
        self._next_id += 1
        request_id = self._next_id
        self._write({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
        return request_id

    def _write(self, obj: dict) -> None:
        assert self._proc.stdin is not None
        self._proc.stdin.write(json.dumps(obj) + "\n")
        self._proc.stdin.flush()

    def _await_message(self, timeout: float) -> Optional[dict]:
        try:
            return self._merged.get(timeout=timeout)
        except queue.Empty:
            return None

    def _read_stdout(self) -> None:
        assert self._proc.stdout is not None
        for line in self._proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                if self._echo_stderr:
                    print(f"[non-JSON stdout] {line}", file=sys.stderr)
                continue
            self._classify(message)

    def _classify(self, message: dict) -> None:
        if "id" in message and ("result" in message or "error" in message):
            message["_kind"] = "response"
        elif message.get("method") == "session/update":
            message["_kind"] = "update"
        elif "method" in message and "id" in message:
            # A server -> client request (e.g. permission, fs/*). We don't implement these (ACP Phase C);
            # reply with an empty result so the agent doesn't deadlock waiting on us.
            self._write({"jsonrpc": "2.0", "id": message["id"], "result": {}})
            return
        else:
            message["_kind"] = "notification"
        self._merged.put(message)

    def _drain_stderr(self) -> None:
        assert self._proc.stderr is not None
        for line in self._proc.stderr:
            if self._echo_stderr:
                print(f"[konductor] {line.rstrip()}", file=sys.stderr)


def _extract_chunk_text(update: dict) -> str:
    """Pull assistant text out of a session/update's agent_message_chunk, tolerating field-name variants."""
    update_body = update.get("update") or update
    content = update_body.get("content")
    if isinstance(content, dict) and content.get("type") == "text":
        return content.get("text", "")
    if isinstance(content, list):
        return "".join(c.get("text", "") for c in content if isinstance(c, dict))
    return ""


def default_jar() -> str:
    """The shaded jar path relative to the repo root (built by ``mvn -DskipTests package``)."""
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(here, "..", "target", "konductor-0.1.0-SNAPSHOT.jar")


def repo_root() -> str:
    import os
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_env_file(path: str) -> dict[str, str]:
    """Parse a simple ``KEY=VALUE`` .env file (ignores blanks/comments, strips quotes, tolerates ``export``).

    Mirrors Konductor's own EnvFile parser so a script can forward the repo's gitignored Foundry creds to a
    subprocess whose cwd is elsewhere (e.g. a temp workspace).
    """
    import os
    values: dict[str, str] = {}
    if not os.path.exists(path):
        return values
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            key = key.strip().removeprefix("export ").strip()
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            if key:
                values[key] = value
    return values


def subprocess_env(extra: Optional[dict[str, str]] = None) -> dict[str, str]:
    """os.environ + the repo-root .env (+ optional overrides), so creds survive a different subprocess cwd."""
    import os
    env = dict(os.environ)
    env.update(load_env_file(os.path.join(repo_root(), ".env")))
    if extra:
        env.update(extra)
    return env

