#!/usr/bin/env python3
# Copyright 2026 David Yonge-Mallo
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Render the app launcher icon and its derived sizes.

The icon is a red X overlapping a green O on a white square, with a
faded "ghost" copy of both marks offset 20 degrees up-and-right to
suggest the superposition that gives the game its quantum flavour:
each spooky mark in play sits in two squares at once until a
collapse settles it. The two colours are the same Material red 700
and green 800 the app uses for player marks in the board UI.

A 1024x1024 master is rendered with PIL, then Lanczos-downsampled to
the Android launcher mipmap sizes and the 512x512 web favicon.

Run from the repository root:

    python3 icons/generate.py

Outputs (overwritten in place):

    icons/icon-1024.png
    composeApp/src/wasmJsMain/resources/favicon.png  (512x512)
    composeApp/src/wasmJsMain/resources/favicon-32.png  (32x32)
    composeApp/src/androidMain/res/mipmap-mdpi/ic_launcher.png  (48)
    composeApp/src/androidMain/res/mipmap-hdpi/ic_launcher.png  (72)
    composeApp/src/androidMain/res/mipmap-xhdpi/ic_launcher.png  (96)
    composeApp/src/androidMain/res/mipmap-xxhdpi/ic_launcher.png  (144)
    composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png  (192)
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

MASTER = 1024
PAD = 120  # outer padding inside the canvas, in master pixels.
STROKE = 90  # stroke width for both X and O, in master pixels.
RED = (211, 47, 47, 255)  # Material red 700, matching `PlayerColors.X`.
GREEN = (46, 125, 50, 255)  # Material green 800, matching `PlayerColors.O`.
WHITE = (255, 255, 255, 255)

# Ghost layer: the offset direction is deliberately neither
# horizontal nor parallel to an X arm (45 degrees), so both ghost
# arms remain visible to the side of the solid X rather than sliding
# along under it. Alpha is high enough to read as a deliberate
# second copy but low enough not to dominate the solid layer.
GHOST_ANGLE_DEG = 20.0
GHOST_DISTANCE = MASTER // 12  # ~85 px at 1024.
GHOST_ALPHA = 110
GHOST_RED = (RED[0], RED[1], RED[2], GHOST_ALPHA)
GHOST_GREEN = (GREEN[0], GREEN[1], GREEN[2], GHOST_ALPHA)


def _draw_o(draw: ImageDraw.ImageDraw, cx: int, cy: int, half: int, fill) -> None:
    draw.ellipse((cx - half, cy - half, cx + half, cy + half), outline=fill, width=STROKE)


def _draw_x(draw: ImageDraw.ImageDraw, cx: int, cy: int, half: int, fill) -> None:
    a, b = cx - half, cy - half
    c, d = cx + half, cy + half
    cap_r = STROKE // 2
    for (x1, y1), (x2, y2) in (((a, b), (c, d)), ((a, d), (c, b))):
        draw.line([(x1, y1), (x2, y2)], fill=fill, width=STROKE)
        for px, py in ((x1, y1), (x2, y2)):
            draw.ellipse((px - cap_r, py - cap_r, px + cap_r, py + cap_r), fill=fill)


def render_master() -> Image.Image:
    """Draw the 1024x1024 source icon."""
    img = Image.new("RGBA", (MASTER, MASTER), WHITE)
    draw = ImageDraw.Draw(img, "RGBA")

    a = math.radians(GHOST_ANGLE_DEG)
    dx = int(GHOST_DISTANCE * math.cos(a))
    dy = -int(GHOST_DISTANCE * math.sin(a))  # screen y goes down; lift the ghost.

    center = MASTER // 2
    half = (MASTER - 2 * PAD) // 2

    # Ghost layer first (behind), then the solid layer on top. The
    # ghost O drawn before the ghost X mirrors the solid order so the
    # red-on-green crossing reads consistently in both layers.
    _draw_o(draw, center + dx, center + dy, half, GHOST_GREEN)
    _draw_x(draw, center + dx, center + dy, half, GHOST_RED)
    _draw_o(draw, center, center, half, GREEN)
    _draw_x(draw, center, center, half, RED)
    return img


def write_resized(master: Image.Image, path: Path, size: int) -> None:
    """Resample `master` to `size` and write as PNG, creating parents."""
    path.parent.mkdir(parents=True, exist_ok=True)
    master.resize((size, size), Image.Resampling.LANCZOS).save(path, "PNG")


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    master = render_master()

    # Source 1024 -- kept under version control as the canonical
    # output so a reader can see what the icon looks like without
    # running this script.
    master.save(root / "icons" / "icon-1024.png", "PNG")

    # Web favicons. 512x512 is the high-resolution variant referenced
    # by PWA and high-DPI contexts; 32x32 is what most browsers
    # actually render in the tab strip.
    write_resized(
        master,
        root / "composeApp/src/wasmJsMain/resources/favicon.png",
        512,
    )
    write_resized(
        master,
        root / "composeApp/src/wasmJsMain/resources/favicon-32.png",
        32,
    )

    # Android launcher mipmaps. Sizes follow the standard
    # mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi density buckets at the 48dp
    # launcher icon spec.
    android_sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in android_sizes.items():
        write_resized(
            master,
            root / f"composeApp/src/androidMain/res/mipmap-{density}/ic_launcher.png",
            size,
        )

    print(f"wrote master + {2 + len(android_sizes)} derived images")


if __name__ == "__main__":
    main()
