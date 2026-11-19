"""Frames the raw store screens (app/build/store/raw) into 1080x1920 Google Play phone
screenshots with a caption on top. Run after: ./gradlew :app:testDebugUnitTest --tests '*StoreAssetsTest'
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), "..", "app", "build", "store")
W, H = 1080, 1920
CAPTIONS = {
    "1_home": "A calm home screen,\nwith Gemini built in",
    "2_drawer": "Every app, A to Z,\nplus smart suggestions",
    "3_scrub": "Slide along the letters\nto jump to any app",
    "4_gemini": "Ask Gemini, or tell\nyour phone what to do",
    "5_app_menu": "Hold any app for\nshortcuts and more",
    "6_setup": "Gemini on your phone,\nor with a free key",
    "7_light_grid": "Light, dark, or your\nwallpaper's colors",
    "8_news": "Your news, weather\nand a Gemini briefing",
}
FONT = "/usr/share/fonts/opentype/inter/InterDisplay-Medium.otf"


def background():
    top, bottom = (35, 32, 30), (92, 80, 70)
    bg = Image.new("RGB", (W, H))
    px = bg.load()
    for y in range(H):
        t = y / (H - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
        for x in range(W):
            px[x, y] = c
    # A soft violet glow behind the phone, echoing Gemini's colors.
    glow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(glow).ellipse((90, 620, W - 90, 1700), fill=110)
    glow = glow.filter(ImageFilter.GaussianBlur(180))
    violet = Image.new("RGB", (W, H), (150, 120, 230))
    return Image.composite(violet, bg, glow)


def frame(name, caption):
    shot = Image.open(os.path.join(ROOT, "raw", name + ".png")).convert("RGB")
    canvas = background()
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.truetype(FONT, 64)
    draw.multiline_text((W // 2, 170), caption, font=font, fill=(246, 241, 234), anchor="mm", align="center", spacing=14)

    # The phone screen, scaled to fit under the caption, with rounded corners and a hairline edge.
    h = 1560
    w = round(shot.width * h / shot.height)
    shot = shot.resize((w, h), Image.LANCZOS)
    radius = 54
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius, fill=255)
    x, y = (W - w) // 2, 320
    shadow = Image.new("L", (W, H), 0)
    ImageDraw.Draw(shadow).rounded_rectangle((x, y + 14, x + w, y + h + 14), radius, fill=150)
    canvas.paste((10, 8, 8), (0, 0), shadow.filter(ImageFilter.GaussianBlur(28)))
    canvas.paste(shot, (x, y), mask)
    ImageDraw.Draw(canvas).rounded_rectangle((x, y, x + w - 1, y + h - 1), radius, outline=(255, 255, 255, 60), width=2)

    out = os.path.join(ROOT, "phone")
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, name + ".png")
    canvas.save(path, optimize=True)
    return path


if __name__ == "__main__":
    for n, c in CAPTIONS.items():
        print(frame(n, c))
