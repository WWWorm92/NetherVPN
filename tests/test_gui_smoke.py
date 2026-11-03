"""Headless compact Linux UI smoke and profile/route-dialog regression tests.

Run with xvfb-run -a python3 -m unittest discover -s tests -p test_gui_smoke.py
"""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gui"))
import nethervpn


class CompactDesktopSmokeTest(unittest.TestCase):
    def test_home_and_dialog_workflows(self):
        with tempfile.TemporaryDirectory() as temp:
            profiles = Path(temp) / "profiles"
            with patch.object(nethervpn, "BASE", profiles):
                app = nethervpn.App()
                try:
                    app.update_idletasks()
                    self.assertEqual(app.title(), "NetherVPN")
                    self.assertTrue(360 <= app.winfo_width() <= 470)
                    self.assertTrue(app.connect_btn.winfo_exists())
                    self.assertTrue(app.profile_btn.winfo_exists())
                    self.assertTrue(app.status_badge.winfo_exists())
                    self.assertEqual(app._app_icon.width(), 64)
                    self.assertEqual(app._app_icon.height(), 64)
                    self.assertEqual(app._header_icon.width(), 32)
                    self.assertIsNone(app.logbox)

                    conf = profiles / "laptop.conf"
                    conf.write_text(
                        "[Interface]\nName = nvpn0\nAddress = 10.77.0.3/32\n"
                        "[Peer]\nEndpoint = 192.0.2.1:51830\nAllowedIPs = 10.77.0.1\n",
                        encoding="utf-8")
                    conf.chmod(0o600)
                    app.refresh()
                    self.assertEqual(app.selected(), conf)
                    self.assertIn("laptop", app.profile_title.get())

                    app.open_settings()
                    self.assertIsNotNone(app.settings_window)
                    self.assertEqual(set(app.mode_buttons), {"full", "split"})
                    app.mode_var.set("full")
                    app.dns_var.set("9.9.9.9")
                    result = app.save_settings(notify=False)
                    self.assertEqual(result, profiles / "laptop.prefs")
                    self.assertIn("Mode = full", result.read_text(encoding="utf-8"))
                    self.assertIn("DNS = 9.9.9.9", result.read_text(encoding="utf-8"))
                    app.settings_window.destroy()
                    app.settings_window = None
                    app.mode_buttons = {}

                    app.open_profiles()
                    self.assertIsNotNone(app.profile_list)
                    self.assertEqual(app.profile_list.size(), 1)
                    app.profile_window.destroy()
                    app.profile_window = None
                    app.profile_list = None

                    app.open_logs()
                    self.assertTrue(app.logbox.winfo_exists())
                    app.log("Smoke-test diagnostic")
                    self.assertIn("Smoke-test diagnostic", app.logbox.get("1.0", "end"))
                    app.logbox.master.destroy()
                    app.logbox = None

                    app.last_service_poll = 123.0
                    app.finish_operation(lambda: None)
                    self.assertEqual(app.last_service_poll, 0.0)
                    app.mode_var.set("split")
                    app.refresh_mode_label()
                    self.assertIn("Раздельный", app.mode_text.get())
                    app.update_power(True, ready=True)
                    self.assertEqual(
                        app.connect_btn.itemcget(app.power_text, "text"), "Отключить")
                    app.update_power(False)
                    self.assertEqual(
                        app.connect_btn.itemcget(app.power_text, "text"), "Подключить")
                finally:
                    app.destroy()


if __name__ == "__main__":
    unittest.main()
