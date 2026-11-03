import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gui"))
from client_preferences import encode, decode


class PreferencesTest(unittest.TestCase):
    def test_roundtrip(self):
        saved = encode("full", "9.9.9.9", True)
        self.assertIn("Mode = full\n", saved)
        self.assertEqual(("full", "9.9.9.9", True), decode(saved))

    def test_migration(self):
        self.assertEqual(("split", "1.1.1.1", False), decode(""))
        self.assertEqual(("full", "8.8.8.8", False),
                         decode("", legacy_mode="full", legacy_dns="8.8.8.8"))

    def test_validation(self):
        for mode, dns in [("all", "1.1.1.1"), ("full", "8.8.8.8.8"),
                          ("full", "example.com"), ("split", "0.0.0.0")]:
            with self.assertRaises(ValueError):
                encode(mode, dns, False)
        with self.assertRaises(ValueError):
            decode("[Client]\nMode = other\n")
        with self.assertRaises(ValueError):
            decode("[Client]\nMode = full\nAutoConnect = yesmaybe\n")


if __name__ == "__main__":
    unittest.main()
