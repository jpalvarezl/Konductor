"""Deterministic state protocol backed by one Hosted session sandbox."""

from __future__ import annotations

import os
import tempfile
from pathlib import Path

NONE = "none"
INVALID = "error: expected 'remember <marker>' or 'recall'"
DEFAULT_STATE_PATH = Path("/tmp/konductor-hosted-stateful/marker")


class MarkerProtocol:
    """Store one marker in this container's filesystem, never in caller-keyed process state."""

    def __init__(self, state_path: Path = DEFAULT_STATE_PATH) -> None:
        self._state_path = state_path

    def respond(self, input_text: str) -> str:
        command = input_text.strip()
        if command == "recall":
            return self._recall()

        prefix = "remember "
        if command.startswith(prefix):
            marker = command.removeprefix(prefix).strip()
            if marker and "\n" not in marker and "\r" not in marker:
                self._remember(marker)
                return f"remembered {marker}"

        return INVALID

    def _recall(self) -> str:
        try:
            marker = self._state_path.read_text(encoding="utf-8").strip()
        except FileNotFoundError:
            return NONE
        return marker or NONE

    def _remember(self, marker: str) -> None:
        self._state_path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix="marker-", dir=self._state_path.parent, text=True)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as temporary:
                temporary.write(marker)
                temporary.write("\n")
            os.replace(temporary_name, self._state_path)
        except BaseException:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise
