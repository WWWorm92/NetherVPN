#!/usr/bin/env python3
"""Render the bundled NetherVPN SVG into a reliable PNG icon for Linux launchers.

Do not depend on the optional Qt SVG image format plugin: use QtSvgRenderer.
"""
from pathlib import Path
import sys

from PySide6.QtCore import Qt
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

root = Path(__file__).resolve().parent.parent
svg = root / "assets" / "nethervpn.svg"
destination = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else (
    Path.home() / ".local/share/icons/hicolor/256x256/apps/nethervpn.png"
)
app = QGuiApplication.instance() or QGuiApplication([])
renderer = QSvgRenderer(str(svg))
if not renderer.isValid():
    raise SystemExit(f"Cannot render icon: {svg}")
destination.parent.mkdir(parents=True, exist_ok=True)
image = QImage(256, 256, QImage.Format.Format_ARGB32_Premultiplied)
image.fill(Qt.GlobalColor.transparent)
painter = QPainter(image)
renderer.render(painter)
painter.end()
if not image.save(str(destination), "PNG"):
    raise SystemExit(f"Failed to save icon: {destination}")
print(destination)
