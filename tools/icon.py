#!/usr/bin/env python3
"""Builds the launcher icons from art/icon.png (1254 px, squircle 84..1168 on black).
Run from the project root: python3 -I tools/icon.py"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
src = Image.open(os.path.join(ROOT, "art", "icon.png")).convert("RGBA")
BG = (16, 16, 18, 255)                 # the squircle's own dark fill
SQ = (84, 86, 1168, 1167)              # squircle bounds in the source
RADIUS = 250

def squircle_mask(size, box, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle(box, radius=radius, fill=255)
    return m.filter(ImageFilter.GaussianBlur(1.2))

mask = squircle_mask(src.size, SQ, RADIUS)
# for the adaptive icon the launcher draws its own shape: fade the squircle's rim and glow into the fill
soft = Image.new("L", src.size, 0)
ImageDraw.Draw(soft).rounded_rectangle((SQ[0] + 40, SQ[1] + 40, SQ[2] - 40, SQ[3] - 40), radius=RADIUS - 40, fill=255)
soft = soft.filter(ImageFilter.GaussianBlur(30))
inside = Image.composite(src, Image.new("RGBA", src.size, BG), soft)

# Adaptive foreground: the 108 dp canvas shows ~72 dp, launcher masks keep a 66 dp circle.
# Photos are ~900 px wide around (635, 638); fit them in ~57 dp so round masks keep their corners.
CROP = 1700
cx, cy = 635, 638
canvas = Image.new("RGBA", (CROP, CROP), BG)
canvas.paste(inside, (CROP // 2 - cx, CROP // 2 - cy))
legacy = Image.new("RGBA", src.size, (0, 0, 0, 0))
legacy.paste(src, (0, 0), mask)
legacy = legacy.crop(SQ)

for d, k in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
    out = os.path.join(RES, f"mipmap-{d}")
    os.makedirs(out, exist_ok=True)
    canvas.resize((int(108 * k),) * 2, Image.LANCZOS).save(os.path.join(out, "ic_launcher_foreground.png"), optimize=True)
    legacy.resize((int(48 * k),) * 2, Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"), optimize=True)

os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
with open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n</adaptive-icon>\n')
with open(os.path.join(RES, "values", "colors.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#101012</color>\n</resources>\n')
print("ok")
