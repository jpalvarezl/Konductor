import tempfile
import unittest
from pathlib import Path

from marker_protocol import INVALID, NONE, MarkerProtocol


class MarkerProtocolTest(unittest.TestCase):
    def test_recall_is_none_then_returns_remembered_marker(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            protocol = MarkerProtocol(Path(root) / "marker")

            self.assertEqual(NONE, protocol.respond("recall"))
            self.assertEqual("remembered marker-a", protocol.respond("remember marker-a"))
            self.assertEqual("marker-a", protocol.respond("recall"))

    def test_fixture_instances_do_not_share_state(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            first = MarkerProtocol(Path(root) / "sandbox-a" / "marker")
            second = MarkerProtocol(Path(root) / "sandbox-b" / "marker")

            first.respond("remember marker-a")

            self.assertEqual("marker-a", first.respond("recall"))
            self.assertEqual(NONE, second.respond("recall"))

    def test_rejects_commands_outside_the_exact_protocol(self) -> None:
        with tempfile.TemporaryDirectory() as root:
            protocol = MarkerProtocol(Path(root) / "marker")

            self.assertEqual(INVALID, protocol.respond("remember"))
            self.assertEqual(INVALID, protocol.respond("recall now"))
            self.assertEqual(INVALID, protocol.respond("something else"))
            self.assertEqual(NONE, protocol.respond("recall"))


if __name__ == "__main__":
    unittest.main()
