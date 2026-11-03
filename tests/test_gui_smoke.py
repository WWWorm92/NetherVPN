"""Headless Tkinter smoke test for dashboard creation and local preferences.

Run with: xvfb-run -a python3 -m unittest discover -s tests -p test_gui_smoke.py
"""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gui"))
import nethervpn


class DesktopDashboardSmokeTest(unittest.TestCase):
    def test_dashboard_renders_profile_and_mode_cards(self):
        with tempfile.TemporaryDirectory() as temp:
            profiles = Path(temp) / "profiles"
            with patch.object(nethervpn, "BASE", profiles):
                app = nethervpn.App()
                try:
                    app.update_idletasks()
                    self.assertEqual(app.title(), "NetherVPN")
                    self.assertEqual(set(app.mode_buttons), {"full", "split"})
                    self.assertTrue(app.connect_btn.winfo_exists())
                    self.assertTrue(app.logbox.winfo_exists())
                    self.assertTrue(app.status_badge.winfo_exists())

                    conf = profiles / "laptop.conf"
                    conf.write_text(
                        "[Interface]\nName = nvpn0\nAddress = 10.77.0.3/32\n"
                        "[Peer]\nEndpoint = 192.0.2.1:51830\nAllowedIPs = 10.77.0.1\n",
                        encoding="utf-8")
                    conf.chmod(0o600)
                    app.selected_name = conf.name
                    app.refresh()
                    self.assertEqual(app.selected(), conf)
                    app.mode_var.set("full")
                    app.dns_var.set("9.9.9.9")
                    result = app.save_settings(notify=False)
                    self.assertEqual(result, profiles / "laptop.prefs")
                    self.assertIn("Mode = full", result.read_text(encoding="utf-8"))
                    self.assertIn("DNS = 9.9.9.9", result.read_text(encoding="utf-8"))
                    app.last_service_poll = 123.0
                    app.finish_operation(lambda: None)
                    self.assertEqual(app.last_service_poll, 0.0)
                    app.mode_var.set("split")
                    app.refresh_mode_label()
                    self.assertEqual(app.mode_text.get(), "Только VPN-сервер · интернет без изменений")
                finally:
                    app.destroy()


if __name__ == "__main__":
    unittest.main()
