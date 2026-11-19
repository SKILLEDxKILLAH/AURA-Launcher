"""AURA's original icon: a glowing aura ring over AURA's sand dunes (no third-party marks).
Writes app/build/store/icon_512_alt.png. Usage: python3 tools/store_icon.py"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

S = 2048  # draw large, then downscale for smooth edges
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "build", "store")


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def icon():
    img = Image.new("RGB", (S, S))
    d = ImageDraw.Draw(img)
    for y in range(S):  # charcoal sky to warm sand, like the AURA backdrop
        t = y / (S - 1)
        c = lerp((44, 41, 39), (72, 64, 58), t / 0.62) if t < 0.62 else lerp((72, 64, 58), (196, 176, 154), (t - 0.62) / 0.38)
        d.line([(0, y), (S, y)], fill=c)

    # Two soft dunes along the bottom.
    for top, color, alpha in ((0.70, (120, 104, 90), 200), (0.80, (210, 190, 166), 230)):
        layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        pts = [(x, S * top + math.sin(x / S * math.pi * 1.2 + top * 9) * S * 0.035) for x in range(0, S + 1, 16)]
        ld.polygon(pts + [(S, S), (0, S)], fill=color + (alpha,))
        img.paste(layer, (0, 0), layer)

    # The aura: a luminous ring, warm white fading to lavender, with a glow.
    cx, cy, r, w = S / 2, S * 0.44, S * 0.235, S * 0.05
    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    steps = 720
    for i in range(steps):
        a0, a1 = 360 * i / steps - 90, 360 * (i + 1) / steps - 90 + 0.6
        t = (math.sin(math.radians(360 * i / steps)) + 1) / 2
        col = lerp((248, 240, 228), (196, 172, 255), t)
        rd.arc((cx - r, cy - r, cx + r, cy + r), a0, a1, fill=col + (255,), width=int(w))
    glow = ring.filter(ImageFilter.GaussianBlur(S * 0.035))
    img.paste(glow, (0, 0), glow)
    img.paste(ring, (0, 0), ring)
    # A small bright point on the ring, like light catching it.
    spark = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sx, sy = cx + r * math.cos(math.radians(-45)), cy + r * math.sin(math.radians(-45))
    ImageDraw.Draw(spark).ellipse((sx - w * 0.9, sy - w * 0.9, sx + w * 0.9, sy + w * 0.9), fill=(255, 252, 245, 255))
    spark = spark.filter(ImageFilter.GaussianBlur(w * 0.35))
    img.paste(spark, (0, 0), spark)
    return img.resize((512, 512), Image.LANCZOS)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, "icon_512_alt.png")
    icon().save(p, optimize=True)
    print(p, os.path.getsize(p))
