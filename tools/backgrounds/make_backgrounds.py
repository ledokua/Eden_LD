#!/usr/bin/env python3
"""Builds Eden_LD's built-in profile backgrounds (512x256 PNG, PIL only, fixed seeds).

    python3 tools/backgrounds/make_backgrounds.py

Writes src/main/resources/assets/eden_ld/textures/gui/profile_backgrounds/<name>/background.png
(512x256) plus card.png (512x128, Friends cards) and home.png (1024x128, the Home card), each scene
drawn for its own shape — the built-in backgrounds, defined in assets/eden_ld/profile_backgrounds/<name>.json. They stay
dark enough for the default white text. Replace any of them with real art of the same name, or add
new backgrounds (a JSON + picture + lang key profile_background.eden_ld.<name>; see the README).
"""
import math
import random
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 512, 256
OUT = Path(__file__).resolve().parents[2] / "src/main/resources/assets/eden_ld/textures/gui/profile_backgrounds"


# The size being drawn (set by draw()); scenes are laid out for 512x256 and scale with K (height)
# and AREA (how much more ground to fill with stars, sparks, trees).
K = 1.0
AREA = 1.0


def draw(make, w, h):
    """Draws a scene at w x h: the same picture laid out for another shape (cards)."""
    global W, H, K, AREA
    W, H, K, AREA = w, h, h / 256, (w * h) / (512 * 256)
    try:
        return make()
    finally:
        W, H, K, AREA = 512, 256, 1.0, 1.0


def gradient(stops):
    """Vertical gradient through (position 0..1, (r, g, b)) stops."""
    column = Image.new("RGB", (1, H))
    for y in range(H):
        t = y / (H - 1)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                k = (t - p0) / (p1 - p0) if p1 > p0 else 0
                column.putpixel((0, y), tuple(round(a + (b - a) * k) for a, b in zip(c0, c1)))
                break
    return column.resize((W, H), Image.BILINEAR)


def light(layer):
    """An RGBA layer as additive light: its colors weighted by its alpha (convert("RGB") would drop it)."""
    return Image.composite(layer.convert("RGB"), Image.new("RGB", layer.size, (0, 0, 0)), layer.getchannel("A"))


def glow(base, layer, radius):
    """Adds a blurred copy of an RGBA layer (light), then the sharp layer on top."""
    base = ImageChops.add(base, light(layer.filter(ImageFilter.GaussianBlur(radius))))
    base.paste(layer.convert("RGB"), (0, 0), layer)
    return base


def stars(base, count, seed, top=1.0, big=0.05):
    rng = random.Random(seed)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_ = ImageDraw.Draw(layer)
    for _ in range(int(count * AREA)):
        x, y = rng.uniform(0, W), rng.uniform(0, H * top) * rng.uniform(0.2, 1.0)
        a = rng.randint(60, 230)
        r = rng.choice([0.6, 0.8, 1.0]) if rng.random() > big else rng.uniform(1.2, 1.8)
        tint = rng.choice([(255, 255, 255), (220, 230, 255), (255, 240, 220)])
        draw_.ellipse((x - r, y - r, x + r, y + r), fill=tint + (a,))
    return glow(base, layer, 1.5)


def vignette(base, strength=0.55):
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).ellipse((-W * 0.25, -H * 0.4, W * 1.25, H * 1.4), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(60 * K))
    dark = Image.new("RGB", (W, H), (0, 0, 0))
    return Image.composite(base, Image.blend(base, dark, strength), mask)


def hills(base, color, height, seed, bumps=4):
    """Hills along the bottom; height is a fraction of the picture's."""
    rng = random.Random(seed)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    points = [(0, H)]
    phase = rng.uniform(0, 6.28)
    waves = bumps * W / 512
    for x in range(0, W + 8, 8):
        y = H - height * H - 12 * K * math.sin(x / W * math.pi * waves + phase) - 6 * K * math.sin(x / 37 + phase * 2)
        points.append((x, y))
    points.append((W, H))
    ImageDraw.Draw(layer).polygon(points, fill=color + (255,))
    base.paste(layer.convert("RGB"), (0, 0), layer)
    return base


def pines(base, color, baseline, size, count, seed):
    """A row of pines standing on baseline (fraction of the height), size in 256-tall pixels."""
    rng = random.Random(seed)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_ = ImageDraw.Draw(layer)
    baseline *= H
    size *= K
    draw_.rectangle((0, baseline, W, H), fill=color + (255,))
    for _ in range(int(count * W / 512)):
        x = rng.uniform(-10, W + 10)
        h = size * rng.uniform(0.6, 1.2)
        w = h * rng.uniform(0.28, 0.36)
        top = baseline - h
        for tier in range(4):
            ty = top + h * tier * 0.22
            tw = w * (0.45 + tier * 0.2)
            draw_.polygon([(x, ty), (x - tw, ty + h * 0.34), (x + tw, ty + h * 0.34)], fill=color + (255,))
        draw_.rectangle((x - w * 0.08, baseline - h * 0.1, x + w * 0.08, baseline), fill=color + (255,))
    base.paste(layer.convert("RGB"), (0, 0), layer)
    return base


def mist(base, y, alpha, seed):
    """A band of mist at y (fraction of the height)."""
    rng = random.Random(seed)
    layer = Image.new("RGBA", (W, H), (200, 220, 215, 0))
    draw_ = ImageDraw.Draw(layer)
    y *= H
    for _ in range(max(6, int(6 * W / 512))):
        x = rng.uniform(-100, W)
        draw_.ellipse((x, y - 10 * K, x + rng.uniform(150, 300), y + 14 * K), fill=(200, 220, 215, alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(10 * K))
    return Image.alpha_composite(base.convert("RGBA"), layer).convert("RGB")


def twilight():
    img = gradient([(0, (20, 16, 44)), (0.55, (52, 38, 88)), (0.85, (92, 48, 92)), (1, (120, 60, 90))])
    img = stars(img, 170, 1, top=0.75)
    img = hills(img, (30, 22, 48), 0.18, 2, bumps=3)
    img = hills(img, (18, 13, 30), 0.10, 3, bumps=5)
    return vignette(img, 0.45)


def parchment():
    base = Image.new("RGB", (W, H), (92, 66, 40))
    noise = Image.effect_noise((W, H), 22).convert("RGB")
    img = Image.blend(base, ImageChops.multiply(base, noise), 0.35)
    stains = Image.new("RGBA", (W, H), (40, 24, 10, 0))
    rng = random.Random(4)
    draw_ = ImageDraw.Draw(stains)
    for _ in range(max(4, int(14 * AREA))):
        x, y, r = rng.uniform(0, W), rng.uniform(0, H), rng.uniform(20, 70) * K
        draw_.ellipse((x - r, y - r * 0.7, x + r, y + r * 0.7), fill=(40, 24, 10, rng.randint(25, 60)))
    stains = stains.filter(ImageFilter.GaussianBlur(18 * K))
    img = Image.alpha_composite(img.convert("RGBA"), stains).convert("RGB")
    return vignette(img, 0.7)


def aurora():
    img = gradient([(0, (4, 10, 22)), (0.6, (8, 22, 38)), (1, (10, 30, 40))])
    img = stars(img, 150, 5, top=0.9)
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_ = ImageDraw.Draw(layer)
    for band, (color, base_y, amp, freq) in enumerate([
            ((50, 200, 130), 0.375, 26, 1.6), ((30, 160, 170), 0.29, 20, 2.3), ((110, 70, 190), 0.21, 14, 1.1)]):
        for dy in range(0, 44):
            if dy % 2:
                continue
            # Curtains: bright at the bottom edge, fading upwards, with vertical streaks.
            points = [(x, base_y * H + amp * K * math.sin(x / W * math.pi * freq * W / 512 + band) - dy * K)
                      for x in range(0, W + 4, 4)]
            alpha = max(0, int(150 * (1 - dy / 44) ** 1.5))
            draw_.line(points, fill=color + (alpha,), width=max(1, round(2 * K)))
    streaks = Image.new("L", (W, H), 0)
    sdraw = ImageDraw.Draw(streaks)
    rng = random.Random(55)
    for _ in range(int(90 * W / 512)):
        x = rng.uniform(0, W)
        sdraw.line((x, 0, x, H), fill=rng.randint(120, 255), width=rng.choice([2, 3, 4]))
    streaks = streaks.filter(ImageFilter.GaussianBlur(2))
    layer.putalpha(ImageChops.multiply(layer.getchannel("A"), streaks))
    img = ImageChops.add(img, light(layer.filter(ImageFilter.GaussianBlur(4 * K))))
    img = hills(img, (6, 14, 18), 0.12, 6, bumps=4)
    return vignette(img, 0.4)


def ember():
    img = gradient([(0, (14, 6, 6)), (0.6, (34, 10, 8)), (1, (70, 18, 8))])
    glow_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow_layer).ellipse((W * 0.1, H * 0.75, W * 0.9, H * 1.4), fill=(200, 70, 20, 140))
    img = ImageChops.add(img, light(glow_layer.filter(ImageFilter.GaussianBlur(40 * K))))
    rng = random.Random(7)
    sparks = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_ = ImageDraw.Draw(sparks)
    for _ in range(int(140 * W / 512)):
        x, y = rng.uniform(0, W), H - rng.expovariate(1 / (70 * K))
        r = rng.uniform(0.6, 1.8)
        color = rng.choice([(255, 170, 60), (255, 120, 40), (255, 210, 120)])
        draw_.ellipse((x - r, y - r, x + r, y + r), fill=color + (rng.randint(90, 230),))
    return vignette(glow(img, sparks, 3), 0.45)


def forest():
    img = gradient([(0, (18, 30, 34)), (0.5, (38, 58, 56)), (1, (60, 78, 66))])
    img = stars(img, 60, 8, top=0.5, big=0.0)
    img = pines(img, (34, 52, 46), 0.74, 90, 26, 9)
    img = mist(img, 0.72, 60, 10)
    img = pines(img, (22, 36, 32), 0.84, 110, 22, 11)
    img = mist(img, 0.84, 45, 12)
    img = pines(img, (10, 18, 16), 0.957, 130, 18, 13)
    return vignette(img, 0.4)


def ocean():
    img = gradient([(0, (6, 22, 48)), (0.5, (10, 44, 84)), (1, (4, 18, 40))])
    rays = Image.new("RGBA", (W, H), (120, 190, 255, 0))
    draw_ = ImageDraw.Draw(rays)
    rng = random.Random(14)
    for _ in range(max(3, int(7 * W / 512))):
        x = rng.uniform(0, W)
        draw_.polygon([(x - 10 * K, 0), (x + 16 * K, 0), (x + 60 * K, H), (x + 20 * K, H)], fill=(120, 190, 255, 11))
    img = ImageChops.add(img, light(rays.filter(ImageFilter.GaussianBlur(12 * K))))
    waves = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_ = ImageDraw.Draw(waves)
    step = max(6, int(18 * K))
    for i, y0 in enumerate(range(int(H * 0.59), H, step)):
        points = [(x, y0 + 5 * K * math.sin(x / 23 + i * 1.7) + 3 * K * math.sin(x / 9 + i)) for x in range(0, W + 4, 4)]
        draw_.line(points, fill=(140, 200, 255, 22 + i * 4), width=1)
    img = glow(img, waves, 2)
    return vignette(img, 0.5)


def starfall():
    img = gradient([(0, (8, 8, 26)), (0.6, (24, 18, 54)), (1, (40, 24, 66))])
    img = stars(img, 260, 15, top=1.0, big=0.08)
    rng = random.Random(16)
    streaks = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_ = ImageDraw.Draw(streaks)
    for _ in range(max(2, int(6 * W / 512))):
        x, y = rng.uniform(W * 0.2, W), rng.uniform(0, H * 0.6)
        length = rng.uniform(40, 110) * K
        for step in range(24):
            t = step / 23
            draw_.line((x - length * t, y + length * t * 0.45, x - length * (t + 0.05), y + length * (t + 0.05) * 0.45),
                       fill=(220, 225, 255, int(230 * (1 - t))), width=2 if t < 0.2 else 1)
    img = glow(img, streaks, 2)
    img = hills(img, (14, 10, 28), 0.086, 17, bumps=4)
    return vignette(img, 0.4)


def blossom(w=W, h=H, seed=21):
    """A light one (dark text): pale dawn sky, soft clouds, lavender hills, falling petals."""
    column = Image.new("RGB", (1, h))
    stops = [(0, (250, 236, 246)), (0.55, (255, 226, 228)), (1, (255, 214, 200))]
    for y in range(h):
        t = y / (h - 1)
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if p0 <= t <= p1:
                k = (t - p0) / (p1 - p0)
                column.putpixel((0, y), tuple(round(a + (b - a) * k) for a, b in zip(c0, c1)))
                break
    img = column.resize((w, h), Image.BILINEAR)
    rng = random.Random(seed)
    # Transparent white, not black: the blur would pull dark halos in from transparent black.
    clouds = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    draw = ImageDraw.Draw(clouds)
    for _ in range(max(6, w // 50)):
        x, y = rng.uniform(-40, w), rng.uniform(h * 0.05, h * 0.55)
        cw, ch = rng.uniform(60, 160), rng.uniform(14, 30)
        draw.ellipse((x, y, x + cw, y + ch), fill=(255, 255, 255, rng.randint(90, 160)))
    img = Image.alpha_composite(img.convert("RGBA"), clouds.filter(ImageFilter.GaussianBlur(8))).convert("RGB")
    for color, height, bumps, hseed in (((226, 206, 232), h * 0.2, 3, 22), ((212, 188, 222), h * 0.1, 5, 23)):
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        hr = random.Random(hseed)
        phase = hr.uniform(0, 6.28)
        points = [(0, h)] + [(x, h - height - 10 * math.sin(x / w * math.pi * bumps + phase)) for x in range(0, w + 8, 8)] + [(w, h)]
        ImageDraw.Draw(layer).polygon(points, fill=color + (255,))
        img.paste(layer.convert("RGB"), (0, 0), layer)
    petals = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(petals)
    for _ in range(max(40, w // 6)):
        x, y = rng.uniform(0, w), rng.uniform(0, h)
        r = rng.uniform(1.5, 3.2)
        draw.ellipse((x - r, y - r * 0.6, x + r, y + r * 0.6),
                     fill=rng.choice([(246, 160, 190), (250, 186, 206), (240, 140, 176)]) + (rng.randint(150, 230),))
    img.paste(petals.convert("RGB"), (0, 0), petals)
    return img


def blossom_card():
    """The same scene for the Friends cards: 512x128 (4:1, power of two; the cards are ~3.4:1)."""
    return blossom(512, 128, seed=31)


def blossom_home():
    """The same scene for the Home card: 1024x128 (8:1, power of two; the card is ~11:1)."""
    return blossom(1024, 128, seed=41)


SCENES = {
    "twilight": twilight,
    "parchment": parchment,
    "aurora": aurora,
    "ember": ember,
    "forest": forest,
    "ocean": ocean,
    "starfall": starfall,
}

# name -> image maker. Every scene: the picture (512x256, 2:1), a Friends card strip (512x128,
# 4:1) and a Home card strip (1024x128, 8:1) — all powers of two.
BACKGROUNDS = {}
for _name, _scene in SCENES.items():
    BACKGROUNDS[_name] = _scene
    BACKGROUNDS[_name + "_card"] = (lambda scene: lambda: draw(scene, 512, 128))(_scene)
    BACKGROUNDS[_name + "_home"] = (lambda scene: lambda: draw(scene, 1024, 128))(_scene)
BACKGROUNDS["blossom"] = blossom
BACKGROUNDS["blossom_card"] = blossom_card
BACKGROUNDS["blossom_home"] = blossom_home

if __name__ == "__main__":
    # <name> -> <name>/background.png, <name>_card -> <name>/card.png, <name>_home -> <name>/home.png
    for key, make in BACKGROUNDS.items():
        name, _, part = key.partition("_")
        file = OUT / name / {"": "background.png", "card": "card.png", "home": "home.png"}[part]
        file.parent.mkdir(parents=True, exist_ok=True)
        make().save(file, optimize=True)
        print(f"{name}/{file.name}  {file.stat().st_size // 1024} KB")
