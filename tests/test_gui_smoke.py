"""Qt desktop smoke tests; run with QT_QPA_PLATFORM=offscreen.

python -m unittest discover -s tests -p test_gui_smoke.py
"""
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gui"))

try:
    from PySide6.QtWidgets import QApplication
    from nethervpn_qt import Window
except ImportError:
    QApplication = None
    Window = None


@unittest.skipIf(QApplication is None, "PySide6 unavailable")
class QtDesktopSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.qtapp = QApplication.instance() or QApplication([])

    def test_navigation_profile_settings_and_diagnostics(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "profiles"
            with patch("nethervpn_qt.BASE", root):
                window = Window()
                window.timer.stop()
                try:
                    self.assertEqual(window.windowTitle(), "NetherVPN")
                    self.assertEqual(window.pages.count(), 5)
                    self.assertEqual(len(window.nav_buttons), 5)
                    self.assertFalse(any("Сервер" in btn.text() for btn, _ in window.nav_buttons))
                    self.assertFalse(window.windowIcon().isNull(), "NetherVPN icon should render")
                    self.assertFalse(window.current)
                    self.assertEqual(window.profile_label.text(), "Добавить сервер")
                    p = root / "laptop.conf"
                    p.write_text(
                        "[Interface]\nName = nvpn0\nAddress = 10.77.0.3/32\n"
                        "[Peer]\nEndpoint = 192.0.2.1:51830\nAllowedIPs = 10.77.0.1\n"
                    )
                    p.chmod(0o600)
                    window.refresh_profiles()
                    self.assertEqual(window.current, p)
                    self.assertIn("laptop", window.profile_label.text())
                    window.go(1, window.nav_buttons[1][0])
                    selected = [btn for btn, _ in window.nav_buttons if btn.property("active")]
                    self.assertEqual(selected, [window.nav_buttons[1][0]])
                    self.assertIn("Профили", window.nav_buttons[1][0].text())
                    window.go(3)
                    self.assertEqual(window.pages.currentIndex(), 3)
                    window.mode_box.setCurrentIndex(0)
                    window.dns_edit.setText("9.9.9.9")
                    self.assertTrue(window.save_settings(notify=False))
                    pref = root / "laptop.prefs"
                    self.assertIn("Mode = full", pref.read_text())
                    self.assertIn("DNS = 9.9.9.9", pref.read_text())
                    window.go(2)
                    self.assertEqual(window.pages.currentIndex(), 2)
                    window.show()
                    self.qtapp.processEvents()
                    rendered = Path(tmp) / "nethervpn-qt-smoke.png"
                    self.assertTrue(window.grab().save(str(rendered)))
                    self.assertGreater(rendered.stat().st_size, 10000)
                    window.ring.state("Подключить", "Отключено", "#95b0c5")
                    self.assertEqual(window.ring.title, "Подключить")
                finally:
                    window.close()


if __name__ == "__main__":
    unittest.main()
