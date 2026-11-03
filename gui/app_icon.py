"""Small dependency-free NetherVPN application icon for Tk titlebars.

Desktop launcher loads assets/nethervpn.svg. Tk cannot natively show SVG
in its title bar, so render the same monogram with simple antialiased geometry.
Never read profile keys or use any platform-specific image package here.
"""
from math import hypot
import tkinter as tk


def _distance_to_segment(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    length_sq = dx * dx + dy * dy
    t = 0.0 if length_sq == 0 else max(
        0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length_sq))
    return hypot(px - ax - t * dx, py - ay - t * dy)


def _rounded_square(x, y, radius=13):
    cx = min(64 - radius, max(radius, x))
    cy = min(64 - radius, max(radius, y))
    return hypot(x - cx, y - cy) <= radius


def _pixel(x, y):
    if not _rounded_square(x, y):
        return None
    # Surface and subtle raised panel inside the metal-orange tunnel ring.
    base = (25, 43, 62) if y < 30 else (16, 27, 41)
    distance = hypot(x - 32.0, y - 32.0)
    if distance < 21.3:
        base = (20, 44, 60)
    if 20.8 <= distance <= 23.1:
        base = (118, 217, 199)
    if 18.9 <= distance < 20.8:
        base = (49, 91, 103)
    # The N is three continuous strokes like the vector launcher asset.
    segments = [
        (22.0, 42.0, 22.0, 22.0),
        (22.0, 22.0, 42.0, 42.0),
        (42.0, 42.0, 42.0, 22.0),
    ]
    if min(_distance_to_segment(x, y, *line) for line in segments) <= 2.6:
        base = (137, 231, 210)
    return base


def create_icon(master, size=64):
    """Build a scalable, transparent-corner PhotoImage (no Pillow needed)."""
    if not 16 <= size <= 256:
        raise ValueError("icon size must be 16..256")
    icon = tk.PhotoImage(master=master, width=size, height=size)
    transparent = []
    scale = 64.0 / size
    for y in range(size):
        cells = []
        for x in range(size):
            # 2x2 subsample keeps small titlebar icons readable.
            samples = [
                _pixel((x + ox) * scale, (y + oy) * scale)
                for ox, oy in ((.25, .25), (.75, .25), (.25, .75), (.75, .75))
            ]
            opaque = [c for c in samples if c is not None]
            if not opaque:
                transparent.append((x, y))
                cells.append("#101b29")
            else:
                r, g, b = (round(sum(c[i] for c in opaque) / len(opaque))
                           for i in range(3))
                cells.append(f"#{r:02x}{g:02x}{b:02x}")
        icon.put("{" + " ".join(cells) + "}", to=(0, y))
    for x, y in transparent:
        icon.transparency_set(x, y, True)
    return icon
