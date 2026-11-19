#!/usr/bin/env python3
"""Generate the SONYBAE Recorder / GTA San Andreas theme artwork.

Outputs (app/src/main/res/drawable-nodpi):
  bg_gta_art.jpg        1080x2418 comic-panel collage master
  bg_gta_art_screen.jpg 720x1612 darkened, device-sized background
  logo_gta.png          "grand theft auto / San Andreas" stacked wordmark
  logo_pricedown.png    oversized PRICEDOWN title wordmark
  logo_sonybae.png      "SONYBAE RECORDER" glossy white outlined wordmark
  tex_panel.png     400x400 seamless grunge tile

Pure Pillow drawing so the repo can rebuild the art without external tools.
Run: python3 tools/theme/make_assets.py
"""
from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/res/drawable-nodpi"
FONTS = ROOT / "app/src/main/res/font"
PREVIEW = Path("/tmp")

PRICEDOWN = str(FONTS / "pricedown.ttf")
DIPLOMA = str(FONTS / "diploma.ttf")

W, H = 1080, 2418
GUTTER = (10, 10, 9, 255)

random.seed(20041220)


# ------------------------------------------------------------------ helpers
def vgrad(size, top, bottom):
    img = Image.new("RGB", size)
    d = ImageDraw.Draw(img)
    w, h = size
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)],
               fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def bezier(p0, p1, p2, steps=24):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        pts.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                    u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return pts


def taper(draw, spine, w0, w1, color):
    """Fill a polygon that follows `spine` with a width tapering w0 -> w1."""
    n = len(spine)
    left, right = [], []
    for i in range(n):
        t = i / max(1, n - 1)
        x0, y0 = spine[max(0, i - 1)]
        x1, y1 = spine[min(n - 1, i + 1)]
        dx, dy = x1 - x0, y1 - y0
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln, dx / ln
        w = (w0 + (w1 - w0) * t) * 0.5
        px, py = spine[i]
        left.append((px + nx * w, py + ny * w))
        right.append((px - nx * w, py - ny * w))
    draw.polygon(left + right[::-1], fill=color)


def frond(draw, cx, cy, ang, length, droop, width, color, teeth=9, serr=0.34):
    """Filled fan-palm frond with a serrated edge."""
    spine = []
    for k in range(13):
        t = k / 12
        spine.append((cx + math.cos(ang) * length * t,
                      cy + math.sin(ang) * length * t + droop * length * t * t))

    upper, lower = [], []
    for i, (px, py) in enumerate(spine):
        t = i / 12
        x0, y0 = spine[max(0, i - 1)]
        x1, y1 = spine[min(12, i + 1)]
        dx, dy = x1 - x0, y1 - y0
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln, dx / ln
        hw = width * (1 - t * 0.90) * 0.5
        upper.append((px + nx * hw, py + ny * hw))
        lower.append((px - nx * hw, py - ny * hw))

    poly = [(cx, cy)]
    # serrated upper edge
    for i in range(2, 13):
        t = i / 12
        ux, uy = upper[i]
        poly.append((ux, uy))
        if i < 12:
            nx2, ny2 = upper[min(12, i + 1)]
            poly.append(((ux + nx2) / 2 - (ny2 - uy) * serr * (1 - t),
                         (uy + ny2) / 2 + (nx2 - ux) * serr * (1 - t)))
    # serrated lower edge back
    for i in range(12, 1, -1):
        t = i / 12
        lx, ly = lower[i]
        poly.append((lx, ly))
        if i > 2:
            px2, py2 = lower[i - 1]
            poly.append(((lx + px2) / 2 + (py2 - ly) * serr * (1 - t),
                         (ly + py2) / 2 - (px2 - lx) * serr * (1 - t)))
    draw.polygon(poly, fill=color)


def palm(draw, x, base_y, height, color, lean=0.16, frond_scale=1.0, seed=0):
    """California fan palm: slender curved trunk + filled fan fronds."""
    rnd = random.Random(seed or int(x * 7 + 1))
    top = (x + height * lean * rnd.uniform(0.75, 1.2), base_y - height)
    mid = (x + height * lean * 0.30, base_y - height * 0.55)
    spine = bezier((x, base_y), mid, top, 22)
    base_w = max(9.0, height * 0.030)
    taper(draw, spine, base_w, base_w * 0.62, color)

    # trunk rings
    for k in range(4, 22):
        px, py = spine[k]
        r = base_w * 0.30 * (1 - (k - 4) / 24.0)
        if r >= 1:
            draw.line([(px - r, py), (px + r, py)], fill=color, width=2)

    cx, cy = top
    fw = height * 0.052 * frond_scale
    count = 13
    for i in range(count):
        ang = -math.pi + (i + 0.5) * (math.pi / count) + rnd.uniform(-0.05, 0.05)
        length = height * rnd.uniform(0.30, 0.44) * frond_scale
        frond(draw, cx, cy, ang, length, rnd.uniform(0.38, 0.70), fw, color)
    # central spears pointing up
    for i in range(3):
        ang = -math.pi / 2 + (i - 1) * 0.50 + rnd.uniform(-0.05, 0.05)
        frond(draw, cx, cy, ang, height * rnd.uniform(0.16, 0.22) * frond_scale,
              0.62, fw * 0.80, color)


def skyline(draw, base_y, width, color, seed=3, top_scale=1.0, windows=None):
    rnd = random.Random(seed)
    x = -40
    while x < width + 40:
        bw = rnd.randint(46, 104)
        bh = rnd.randint(60, 230) * top_scale
        draw.rectangle([x, base_y - bh, x + bw, base_y], fill=color)
        if rnd.random() < 0.35:
            ax = x + bw * rnd.uniform(0.3, 0.7)
            draw.line([(ax, base_y - bh), (ax, base_y - bh - rnd.randint(24, 78))],
                      fill=color, width=5)
        if windows:
            wy = base_y - bh + 16
            while wy < base_y - 18:
                wx = x + 10
                while wx < x + bw - 12:
                    if rnd.random() < 0.55:
                        draw.rectangle([wx, wy, wx + 7, wy + 9], fill=windows)
                    wx += 18
                wy += 22
        x += bw + rnd.randint(4, 26)


def helicopter(draw, cx, cy, s, color):
    draw.ellipse([cx - 46 * s, cy - 26 * s, cx + 42 * s, cy + 24 * s], fill=color)
    draw.polygon([(cx + 28 * s, cy - 12 * s), (cx + 132 * s, cy - 5 * s),
                  (cx + 132 * s, cy + 8 * s), (cx + 32 * s, cy + 13 * s)], fill=color)
    draw.polygon([(cx + 108 * s, cy - 46 * s), (cx + 122 * s, cy - 46 * s),
                  (cx + 126 * s, cy + 2 * s), (cx + 110 * s, cy + 2 * s)], fill=color)
    draw.rectangle([cx - 124 * s, cy - 36 * s, cx + 124 * s, cy - 30 * s], fill=color)
    draw.rectangle([cx - 5 * s, cy - 36 * s, cx + 5 * s, cy - 21 * s], fill=color)
    draw.rectangle([cx - 42 * s, cy + 32 * s, cx + 38 * s, cy + 38 * s], fill=color)
    for dx in (-28, 24):
        draw.line([(cx + dx * s, cy + 24 * s), (cx + (dx - 4) * s, cy + 33 * s)],
                  fill=color, width=int(max(3, 5 * s)))
    draw.ellipse([cx - 54 * s, cy - 5 * s, cx - 40 * s, cy + 9 * s], fill=color)


# lowrider side profile in unit space (x -0.55..0.55, y 0..0.34 above ground)
CAR_BODY = [(-0.500, 0.062), (-0.516, 0.110), (-0.505, 0.148), (-0.452, 0.163),
            (-0.352, 0.170), (-0.286, 0.176), (-0.232, 0.222), (-0.150, 0.298),
            (0.010, 0.304), (0.108, 0.212), (0.216, 0.184), (0.380, 0.172),
            (0.468, 0.163), (0.505, 0.140), (0.512, 0.100), (0.498, 0.062)]
CAR_GLASS = [(-0.244, 0.204), (-0.196, 0.238), (-0.128, 0.288), (0.002, 0.292),
             (0.086, 0.212), (0.190, 0.190), (0.150, 0.182), (-0.200, 0.186)]
CAR_WHEELS = (-0.296, 0.296)


def lowrider(draw, cx, ground_y, length, body, trim, glass, arch=None,
             lights=True):
    """Side-profile 1960s American lowrider. `length` = bumper-to-bumper px."""
    def P(x, y):
        return (cx + x * length, ground_y - y * length)

    def R(x0, y0, x1, y1):
        (ax, ay), (bx, by) = P(x0, y0), P(x1, y1)
        return [min(ax, bx), min(ay, by), max(ax, bx), max(ay, by)]

    arch = arch or body
    r = length * 0.098
    wheel_pts = [P(wx, 0.0) for wx in CAR_WHEELS]

    # wheel arches: cull the body around each wheel with an arch colour
    for px, py in wheel_pts:
        draw.ellipse([px - r * 1.30, py - r * 1.30, px + r * 1.30, py + r * 1.30],
                     fill=arch)

    draw.polygon([P(x, y) for x, y in CAR_BODY], fill=body)
    draw.polygon([P(x, y) for x, y in CAR_GLASS], fill=glass)

    # belt line + chrome spear
    draw.line([P(-0.49, 0.152), P(0.49, 0.146)], fill=trim,
              width=max(3, int(length * 0.010)))
    draw.line([P(0.11, 0.196), P(0.46, 0.176)], fill=trim, width=3)
    draw.line([P(-0.47, 0.192), P(-0.34, 0.180)], fill=trim, width=3)

    # bumpers
    draw.rounded_rectangle(R(-0.572, 0.058, -0.496, 0.114), radius=4, fill=trim)
    draw.rounded_rectangle(R(0.496, 0.058, 0.572, 0.114), radius=4, fill=trim)

    # wheels on top
    for px, py in wheel_pts:
        draw.ellipse([px - r, py - r, px + r, py + r], fill=body)
        draw.ellipse([px - r * 0.74, py - r * 0.74, px + r * 0.74, py + r * 0.74],
                     fill=trim)
        draw.ellipse([px - r * 0.46, py - r * 0.46, px + r * 0.46, py + r * 0.46],
                     fill=body)
        draw.ellipse([px - r * 0.12, py - r * 0.12, px + r * 0.12, py + r * 0.12],
                     fill=trim)
        # arch rim
        draw.arc([px - r * 1.22, py - r * 1.22, px + r * 1.22, py + r * 1.22],
                 start=185, end=355, fill=trim, width=max(2, int(length * 0.008)))

    if lights:
        hl = length * 0.016
        hx, hy = P(0.500, 0.132)
        draw.ellipse([hx - hl, hy - hl, hx + hl, hy + hl], fill=(244, 230, 190))
        draw.rectangle(R(-0.518, 0.118, -0.494, 0.140), fill=trim)

    # door seam (dark, thin)
    draw.line([P(-0.030, 0.070), P(-0.030, 0.196)], fill=(0, 0, 0, 70), width=2)


def bolts_car(draw, cx, ground_y, length, body, glass, trim):
    """Squat buggy silhouette used in the bottom panel."""
    def P(x, y):
        return (cx + x * length, ground_y - y * length)
    for wheel_x in (-0.32, 0.32):
        r = length * 0.115
        px, py = P(wheel_x, 0.0)
        draw.ellipse([px - r, py - r, px + r, py + r], fill=body)
        draw.ellipse([px - r * 0.58, py - r * 0.58, px + r * 0.58, py + r * 0.58],
                     fill=trim)
        draw.ellipse([px - r * 0.24, py - r * 0.24, px + r * 0.24, py + r * 0.24],
                     fill=glass)
    pts = [(-0.50, 0.030), (-0.52, 0.150), (-0.44, 0.230), (-0.20, 0.260),
           (-0.06, 0.380), (0.20, 0.392), (0.34, 0.262), (0.50, 0.230),
           (0.52, 0.150), (0.50, 0.030)]
    draw.polygon([P(x, y) for x, y in pts], fill=body)
    draw.polygon([P(x, y) for x, y in [(-0.18, 0.266), (-0.05, 0.362),
                                       (0.19, 0.372), (0.30, 0.268)]], fill=glass)


def fence(draw, x0, x1, y0, y1, color):
    step = max(28, int((x1 - x0) / 26))
    draw.line([(x0, y0), (x1, y0)], fill=color, width=7)
    for x in range(int(x0), int(x1) + 1, step):
        draw.line([(x, y0), (x, y1)], fill=color, width=4)
    draw.line([(x0, y0 + (y1 - y0) * 0.48), (x1, y0 + (y1 - y0) * 0.48)],
              fill=color, width=4)


def wire(draw, pts, color, width=3):
    draw.line(pts, fill=color, width=width, joint="curve")


def grain(size, strength=13, block=5):
    small = Image.new("L", (max(1, size[0] // block), max(1, size[1] // block)))
    px = small.load()
    rnd = random.Random(77)
    for y in range(small.height):
        for x in range(small.width):
            px[x, y] = rnd.randint(128 - strength, 128 + strength)
    return small.resize(size, Image.BILINEAR)


def scratches(img, count=90, seed=5):
    rnd = random.Random(seed)
    d = ImageDraw.Draw(img, "RGBA")
    w, h = img.size
    for _ in range(count):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h)
        ln = rnd.uniform(60, 340)
        ang = rnd.uniform(-0.22, 0.22)
        d.line([(x, y), (x + math.cos(ang) * ln, y + math.sin(ang) * ln)],
               fill=(18, 16, 12, rnd.randint(12, 30)),
               width=rnd.choice([1, 1, 2]))
    # dust specks
    for _ in range(900):
        x, y = rnd.uniform(0, w), rnd.uniform(0, h)
        r = rnd.uniform(0.7, 2.6)
        d.ellipse([x, y, x + r, y + r],
                  fill=(24, 20, 15, rnd.randint(10, 34)))


def halftone(img, spacing=11, r=1.7, alpha=15):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    w, h = img.size
    for y in range(0, h, spacing):
        off = ((y // spacing) % 2) * (spacing // 2)
        for x in range(0, w, spacing):
            d.ellipse([x + off - r, y - r, x + off + r, y + r],
                      fill=(0, 0, 0, alpha))
    img.alpha_composite(layer)


# --------------------------------------------------------------- background
def panel_top(h):
    img = vgrad((W, h), (176, 166, 138), (214, 192, 156)).convert("RGBA")
    d = ImageDraw.Draw(img)
    # haze bands
    for i, yy in enumerate(range(0, h, 16)):
        a = max(0, 34 - i * 2)
        if a:
            d.rectangle([0, yy, W, yy + 16], fill=(228, 204, 160, a))
    skyline(d, int(h * 0.94), W, (146, 138, 112), seed=5, top_scale=0.55,
            windows=(200, 188, 152, 80))
    for x, hh, ln in ((58, 0.72, 0.13), (312, 0.60, 0.16),
                      (760, 0.62, -0.12), (1010, 0.68, -0.10)):
        palm(d, x, int(h * 1.0), int(h * hh), (78, 76, 60), lean=ln, seed=int(x))
    helicopter(d, int(W * 0.74), int(h * 0.19), 1.35, (66, 66, 54))
    # power lines
    for yy in (int(h * 0.36), int(h * 0.44)):
        wire(d, [(0, yy + 26), (int(W * 0.33), yy + 58), (int(W * 0.60), yy + 30),
                 (W, yy + 70)], (92, 90, 70, 170), 3)
    return img


def panel_mid(h):
    img = vgrad((W, h), (146, 128, 104), (170, 148, 118)).convert("RGBA")
    d = ImageDraw.Draw(img)
    skyline(d, int(h * 0.50), W, (134, 118, 96), seed=9, top_scale=0.62)
    for x, hh in ((110, 0.52), (300, 0.42), (782, 0.40), (966, 0.50)):
        palm(d, x, int(h * 0.62), int(h * hh), (92, 78, 60), lean=-0.10, seed=x + 7)
    # road strip + distant car
    d.rectangle([0, int(h * 0.62), W, h], fill=(126, 110, 88))
    d.line([(0, int(h * 0.66)), (W, int(h * 0.655))], fill=(104, 90, 72), width=5)
    return img


def panel_bottom(h):
    img = vgrad((W, h), (152, 150, 130), (96, 98, 86)).convert("RGBA")
    d = ImageDraw.Draw(img)
    skyline(d, int(h * 0.42), W, (114, 114, 98), seed=13, top_scale=0.55)
    for x, hh in ((96, 0.36), (286, 0.28), (900, 0.32), (1046, 0.26)):
        palm(d, x, int(h * 0.44), int(h * hh), (74, 76, 62), lean=-0.12, seed=x + 31)
    helicopter(d, int(W * 0.58), int(h * 0.10), 0.85, (76, 78, 64))
    # tarmac
    d.rectangle([0, int(h * 0.52), W, h], fill=(104, 104, 90))
    fence(d, 0, W, int(h * 0.50), int(h * 0.66), (80, 82, 70))
    d.line([(0, int(h * 0.665)), (W, int(h * 0.665))], fill=(74, 76, 66), width=6)
    return img


def make_background():
    canvas = Image.new("RGBA", (W, H), (16, 15, 13, 255))

    top_h = int(H * 0.330)
    mid_h = int(H * 0.290)
    bot_h = H - top_h - mid_h

    canvas.paste(panel_top(top_h), (0, 0))
    mid_y = top_h
    canvas.paste(panel_mid(mid_h), (0, mid_y))
    bot_y = top_h + mid_h
    canvas.paste(panel_bottom(bot_h), (0, bot_y))

    pd = ImageDraw.Draw(canvas)
    # mid panel: small lowrider riding the road, driving right
    lowrider(pd, int(W * 0.46), mid_y + int(mid_h * 0.80), 470,
             body=(60, 48, 36), trim=(148, 124, 92), glass=(120, 104, 84),
             arch=(126, 110, 88), lights=False)
    # bottom panel: hero lowrider in the foreground
    lowrider(pd, int(W * 0.50), bot_y + int(bot_h * 0.99), 720,
             body=(36, 36, 31), trim=(178, 172, 142), glass=(112, 112, 96),
             arch=(104, 104, 90))
    # bottom panel: second car parked right, behind the fence
    lowrider(pd, int(W * 0.80), bot_y + int(bot_h * 0.655), 340,
             body=(62, 62, 54), trim=(150, 148, 124), glass=(112, 112, 96),
             arch=(114, 114, 98), lights=False)

    # ink gutters between panels (torn comic separators)
    sep = ImageDraw.Draw(canvas)
    sep.polygon([(0, mid_y - 16), (W, mid_y - 44), (W, mid_y + 4), (0, mid_y + 32)],
                fill=GUTTER)
    sep.polygon([(0, bot_y - 26), (W, bot_y - 4), (W, bot_y + 36), (0, bot_y + 14)],
                fill=GUTTER)

    # aged print: halftone + fine grain + scratches
    halftone(canvas, spacing=11, r=1.7, alpha=14)
    g = grain((W, H), 14, 5)
    canvas = Image.composite(canvas, Image.new("RGBA", (W, H), (26, 24, 20, 255)),
                             g.point(lambda v: 186 + v // 7))
    scratches(canvas, 90)

    # warm grade + vignette so the UI text layer stays legible
    canvas.alpha_composite(Image.new("RGBA", (W, H), (255, 196, 120, 14)))

    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).ellipse(
        [-int(W * 0.46), -int(H * 0.18), int(W * 1.46), int(H * 1.18)], fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(210))
    dark = Image.new("RGBA", (W, H), (8, 7, 5, 255))
    dark.putalpha(Image.eval(mask, lambda v: int((255 - v) * 0.78)))
    canvas.alpha_composite(dark)
    return canvas.convert("RGB")


# ----------------------------------------------------------------- wordmarks
def fit_font(path, text, max_w, start=520):
    size = start
    while size > 12:
        f = ImageFont.truetype(path, size)
        b = f.getbbox(text)
        if b[2] - b[0] <= max_w:
            return f
        size -= 4
    return ImageFont.truetype(path, 12)


def tight(img, pad=8):
    box = img.getbbox()
    if not box:
        return img
    x0, y0, x1, y1 = box
    x0, y0 = max(0, x0 - pad), max(0, y0 - pad)
    x1, y1 = min(img.width, x1 + pad), min(img.height, y1 + pad)
    return img.crop((x0, y0, x1, y1))


def make_gta_logo():
    CW, CH = 1100, 900
    img = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    lines = ("grand", "theft", "auto")
    size = 300
    while size > 60:
        f = ImageFont.truetype(PRICEDOWN, size)
        widest = max(f.getbbox(t)[2] - f.getbbox(t)[0] for t in lines)
        if widest <= CW - 120:
            break
        size -= 6
    f = ImageFont.truetype(PRICEDOWN, size)

    stroke = max(6, int(size * 0.075))
    depth = max(8, int(size * 0.085))
    step = depth / 14.0
    lead = int(size * 0.80)

    y = int(size * 0.52)
    for line in lines:
        cx = CW // 2
        for i in range(14, 0, -1):
            shade = int(20 + 34 * (i / 14.0))
            d.text((cx + i * step * 0.85, y + i * step), line, font=f,
                   fill=(shade, shade, shade, 255), anchor="mm",
                   stroke_width=stroke, stroke_fill=(shade, shade, shade, 255))
        d.text((cx, y), line, font=f, fill=(252, 250, 244, 255), anchor="mm",
               stroke_width=stroke, stroke_fill=(14, 12, 10, 255))
        y += lead

    # "San Andreas" script, overlapping the bottom of "auto"
    sa = fit_font(DIPLOMA, "San Andreas", 640, 260)
    sa_cy = y - int(lead * 0.52)
    for i in range(8, 0, -1):
        shade = int(22 + 30 * (i / 8.0))
        d.text((CW // 2 + i * 0.7, sa_cy + i * 1.1), "San Andreas", font=sa,
               fill=(shade, shade, shade, 255), anchor="mm",
               stroke_width=12, stroke_fill=(shade, shade, shade, 255))
    d.text((CW // 2, sa_cy), "San Andreas", font=sa, fill=(255, 238, 198, 255),
           anchor="mm", stroke_width=12, stroke_fill=(14, 12, 10, 255))
    return tight(img, 10)


def make_wordmark(text, max_w, glow=13, top=(255, 255, 255), bottom=(206, 206, 210),
                  stroke=(10, 9, 8)):
    f = fit_font(PRICEDOWN, text, max_w, 520)
    b = f.getbbox(text)
    tw, th = b[2] - b[0], b[3] - b[1]
    sw = max(5, int(th * 0.115))
    pad = int(th * 0.30) + 24 + glow * 3
    img = Image.new("RGBA", (tw + pad * 2, th + pad * 2), (0, 0, 0, 0))
    c = (img.width // 2, img.height // 2)

    # neon halo
    if glow:
        halo = Image.new("RGBA", img.size, (0, 0, 0, 0))
        hd = ImageDraw.Draw(halo)
        hd.text(c, text, font=f, fill=(255, 255, 255, 255), anchor="mm",
                stroke_width=sw + 7, stroke_fill=(255, 255, 255, 200))
        halo = halo.filter(ImageFilter.GaussianBlur(glow))
        img.alpha_composite(halo)
        img.alpha_composite(halo)

    d = ImageDraw.Draw(img)
    # 3D slab + hard outline
    for i in range(7, 0, -1):
        shade = int(24 + 22 * (i / 7.0))
        d.text((c[0] + i * 1.4, c[1] + i * 2.6), text, font=f, anchor="mm",
               fill=(shade, shade, shade, 255),
               stroke_width=sw, stroke_fill=(shade, shade, shade, 255))
    d.text(c, text, font=f, anchor="mm", fill=(0, 0, 0, 255),
           stroke_width=sw, stroke_fill=stroke)

    # glossy white face: vertical gradient clipped to the glyph mask
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).text(c, text, font=f, anchor="mm", fill=255)
    grad = vgrad(img.size, top, bottom).convert("RGBA")
    img.paste(grad, (0, 0), mask)

    # specular sheen across the top third of the letters
    sheen = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(sheen).text((c[0], c[1] - th * 0.20), text, font=f,
                               anchor="mm", fill=(255, 255, 255, 150))
    sheen = sheen.filter(ImageFilter.GaussianBlur(max(2, glow // 2)))
    sheen.putalpha(Image.composite(sheen.getchannel("A"), Image.new("L", img.size, 0),
                                   mask))
    img.alpha_composite(sheen)
    return tight(img, 6)


def mod(v, n):
    return v % n


def make_pricedown_logo():
    return make_wordmark("PRICEDOWN", 1020, glow=0,
                        top=(255, 255, 250), bottom=(255, 255, 250),
                        stroke=(8, 7, 6))


def make_panel_texture(size=400):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rnd = random.Random(9)
    for _ in range(1500):
        x, y = rnd.uniform(0, size), rnd.uniform(0, size)
        r = rnd.uniform(0.6, 2.6)
        col = ((0, 0, 0, rnd.randint(10, 34)) if rnd.random() < 0.72
               else (255, 240, 210, rnd.randint(8, 20)))
        for dx in (-size, 0, size):
            for dy in (-size, 0, size):
                px, py = x + dx, y + dy
                if -r < px < size + r and -r < py < size + r:
                    d.ellipse([px, py, px + r, py + r], fill=col)
    for _ in range(90):
        x, y = rnd.uniform(0, size), rnd.uniform(0, size)
        ln = rnd.uniform(20, 120)
        ang = rnd.uniform(-0.5, 0.5)
        col = (0, 0, 0, rnd.randint(8, 24))
        for dx in (-size, 0, size):
            for dy in (-size, 0, size):
                d.line([(x + dx, y + dy),
                        (x + dx + math.cos(ang) * ln, y + dy + math.sin(ang) * ln)],
                       fill=col, width=1)
    return img


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    bg = make_background()
    bg.save(OUT / "bg_gta_art.jpg", quality=86, optimize=True, progressive=True)

    # Device-sized background avoids decoding the full 1080x2418 master into
    # a large ARGB bitmap on lower-memory phones. The recording canvas is 720x1612.
    screen_bg = bg.resize((720, 1612), Image.LANCZOS)
    screen_bg = screen_bg.point(lambda value: int(value * 0.58))
    screen_bg.save(OUT / "bg_gta_art_screen.jpg", quality=84, optimize=True,
                   progressive=True)
    screen_bg.resize((432, 967), Image.LANCZOS).save(
        PREVIEW / "preview_bg.jpg", quality=84)

    gta = make_gta_logo()
    gta.save(OUT / "logo_gta.png")
    gta.resize((gta.width // 2, gta.height // 2), Image.LANCZOS).save(
        PREVIEW / "preview_logo_gta.png")

    pricedown = make_pricedown_logo()
    pricedown.save(OUT / "logo_pricedown.png")

    mark = make_wordmark("SONYBAE RECORDER", 980, glow=13)
    mark.save(OUT / "logo_sonybae.png")
    mark.resize((mark.width // 2, mark.height // 2), Image.LANCZOS).save(
        PREVIEW / "preview_logo_sonybae.png")

    make_panel_texture().save(OUT / "tex_panel.png")

    for p in sorted(OUT.iterdir()):
        print(f"{p.name:20s} {p.stat().st_size / 1024:8.1f} KiB  "
              f"{Image.open(p).size}")


if __name__ == "__main__":
    main()
