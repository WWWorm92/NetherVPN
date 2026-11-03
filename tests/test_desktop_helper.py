import configparser
import importlib.util
import os
import pathlib
import tempfile
import unittest
from unittest import mock

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "nether-desktop-helper.py"
spec = importlib.util.spec_from_file_location("nether_desktop_helper", SOURCE)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


class HelperTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = pathlib.Path(self.tmp.name)
        self.root = self.base / "root"
        self.root.mkdir(mode=0o700)
        self.userdir = self.base / "user"
        self.userdir.mkdir(mode=0o700)
        self.name = "laptop.conf"
        self.identity = (
            "[Interface]\nPrivateKey = " + "a" * 64 +
            "\nAddress = 10.77.0.3/32\nMTU = 1280\n"
            "\n[Peer]\nPublicKey = " + "b" * 64 +
            "\nEndpoint = 77.221.132.76:51830\nAllowedIPs = 10.77.0.1\n"
        ).encode()
        self.settings = b"[Client]\nMode = full\nDNS = 1.1.1.1\nAutoConnect = false\n"
        (self.userdir / self.name).write_bytes(self.identity)
        (self.userdir / "laptop.prefs").write_bytes(self.settings)
        (self.userdir / self.name).chmod(0o600)
        (self.userdir / "laptop.prefs").chmod(0o600)

    def test_validates_profile_and_client_settings(self):
        helper.validate(self.identity, self.settings)
        with self.assertRaises(Exception):
            helper.validate(self.identity, b"[Client]\nMode = full\nDNS = example.com\nAutoConnect = false\n")
        with self.assertRaises(Exception):
            helper.validate(self.identity, b"[Client]\nMode = full\nDNS = 1.1.1.1\nAutoConnect = yes\n")
        with self.assertRaises(Exception):
            helper.validate(b"bad content", self.settings)

    def test_rejects_traversal_and_symlinked_profile(self):
        for name in ("../a.conf", "../../etc/passwd", ".hidden.conf"):
            with self.assertRaises(Exception):
                helper.safe_profile_name(name)
        target = self.userdir / "shadow.conf"
        target.symlink_to(self.userdir / self.name)
        fd = os.open(self.userdir, os.O_RDONLY | os.O_DIRECTORY)
        try:
            with self.assertRaises(OSError):
                helper.read_user_file(fd, target.name, os.getuid(), 65536)
        finally:
            os.close(fd)

    def test_single_authenticated_transaction(self):
        calls = []
        def source_dir(_uid):
            return os.open(self.userdir, os.O_RDONLY | os.O_DIRECTORY)
        with mock.patch.object(helper, "ROOT", self.root), \
             mock.patch.object(helper, "root_directory"), \
             mock.patch.object(helper, "source_directory", side_effect=source_dir), \
             mock.patch.object(helper, "call", side_effect=lambda *args: calls.append(args)):
            helper.connect(self.name, os.getuid())
        self.assertEqual(self.identity, (self.root / "client.conf").read_bytes())
        self.assertEqual(self.settings, (self.root / "client-settings.conf").read_bytes())
        self.assertEqual(["restart", "disable"], [x[1] for x in calls])
        self.assertEqual(0o600, (self.root / "client.conf").stat().st_mode & 0o777)

    def test_rollback_when_systemd_fails(self):
        calls = []
        def source_dir(_uid):
            return os.open(self.userdir, os.O_RDONLY | os.O_DIRECTORY)
        (self.root / "client.conf").write_bytes(b"old-profile")
        (self.root / "client-settings.conf").write_bytes(b"old-settings")
        def fail_restart(*args):
            calls.append(args)
            if args[1] == "restart":
                raise RuntimeError("simulated restart failure")
        with mock.patch.object(helper, "ROOT", self.root), \
             mock.patch.object(helper, "root_directory"), \
             mock.patch.object(helper, "source_directory", side_effect=source_dir), \
             mock.patch.object(helper, "call", side_effect=fail_restart):
            with self.assertRaisesRegex(RuntimeError, "restart failure"):
                helper.connect(self.name, os.getuid())
        self.assertEqual(b"old-profile", (self.root / "client.conf").read_bytes())
        self.assertEqual(b"old-settings", (self.root / "client-settings.conf").read_bytes())


if __name__ == "__main__":
    unittest.main()
