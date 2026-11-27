"""Generate Aegis VPN Desktop icons matching the Android dark-glass mark.

Usage:  python tools/generate-icons.py
Writes src/main/resources/app-icon.png (1024), tray.png (128),
AegisVPN.ico (multi-resolution) and a small HTML preview in mockups/.
"""

from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src" / "main" / "resources"
MOCKUPS = ROOT.parent / "mockups"

MINT = (84, 230, 193)
BACKGROUND_STOPS = (
    (0.0, (29, 41, 66)),
    (0.55, (16, 24, 39)),
    (1.0, (7, 11, 20)),
)


def lerp_rgb(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def gradient(size, center, radius, stops, alpha=None):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    cx, cy = center[0] * size, center[1] * size
    r = radius * size
    t = np.clip(np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r, 0.0, 1.0)
    out = np.zeros((size, size, 3), dtype=np.float32)
    for i in range(len(stops) - 1):
        t0, c0 = stops[i]
        t1, c1 = stops[i + 1]
        seg = np.clip((t - t0) / (t1 - t0), 0.0, 1.0)
        contrib = np.stack([np.full_like(seg, c) for c in c0], axis=-1)
        target = np.stack([np.full_like(seg, c) for c in c1], axis=-1)
        out = np.where((t >= t0)[..., None], contrib + (target - contrib) * seg[..., None], out)
    out = np.where((t <= stops[0][0])[..., None], np.array(stops[0][1], dtype=np.float32), out)
    out = np.where((t >= stops[-1][0])[..., None], np.array(stops[-1][1], dtype=np.float32), out)
    if alpha is not None:
        a0, a1 = alpha
        a = (a0 + (a1 - a0) * t).astype(np.float32)
        return out, a
    return out


def rounded_mask(size, radius_ratio=0.222, ss=1):
    mask = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * ss - 1, size * ss - 1),
        radius=round(size * ss * radius_ratio),
        fill=255,
    )
    return mask.resize((size, size), Image.LANCZOS)


def cubic(p0, p1, p2, p3, steps=40):
    pts = []
    for i in range(1, steps + 1):
        t = i / steps
        u = 1 - t
        pts.append(
            (
                u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
            )
        )
    return pts


# Coordinates in the original 48x48 vector viewport.
SHIELD = [(24.0, 4.5), (38.5, 10.3), (38.5, 22.5)]
SHIELD += cubic((38.5, 22.5), (38.5, 31.7), (32.5, 39.2), (24.0, 42.4))
SHIELD += cubic((24.0, 42.4), (15.5, 39.2), (9.5, 31.7), (9.5, 22.5))
SHIELD += [(9.5, 10.3), (24.0, 4.5)]
CHECK = [(17.4, 23.6), (22.3, 28.5), (31.7, 18.5)]


def draw_mark(base: Image.Image, ss: int = 1) -> Image.Image:
    """Composite the shield over an existing RGBA canvas."""
    w = base.width
    draw = ImageDraw.Draw(base, "RGBA")

    def to_px(points):
        # 48-vp -> 108-vp (group scale 1.9 / translate 8.4,11.345) -> canvas.
        k = w / 108.0
        return [(round((8.4 + 1.9 * x) * k), round((11.345 + 1.9 * y) * k)) for x, y in points]

    def stroke(points, color, width_vp):
        width = max(1, round(width_vp * 1.9 * w / 108.0))
        p = to_px(points)
        draw.line(p + [p[0]], fill=color, width=width, joint="curve")
        return width

    # Soft mint halo behind the outline.
    stroke(SHIELD, (*MINT, 46), 9.0)
    # Crisp shield outline.
    stroke(SHIELD, (*MINT, 255), 3.4)
    # Check with round caps.
    width = stroke(CHECK, (*MINT, 255), 3.4)
    for x, y in to_px([CHECK[0], CHECK[-1]]):
        r = width / 2
        draw.ellipse((x - r, y - r, x + r, y + r), fill=(*MINT, 255))
    return base


def make_icon(size: int = 1024, ss: int = 2) -> Image.Image:
    n = size * ss
    bg = gradient(n, (0.34, 0.26), 120 / 108, BACKGROUND_STOPS)
    glow, ga = gradient(n, (0.5, 0.537), 46 / 108, ((0.0, MINT), (1.0, MINT)), (0.18, 0.0))
    arr = np.clip(bg * (1 - ga[..., None]) + glow * ga[..., None], 0, 255).astype(np.uint8)
    alpha = np.asarray(rounded_mask(size, ss=ss).resize((n, n), Image.BILINEAR))
    rgba = np.dstack([arr, alpha])
    img = Image.fromarray(rgba, "RGBA")
    img = draw_mark(img, ss)
    # Thin mint rim on the glass tile.
    rim = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim, "RGBA")
    rim_w = max(1, round(1.4 * n / 108.0))
    rd.rounded_rectangle(
        (rim_w / 2, rim_w / 2, n - 1 - rim_w / 2, n - 1 - rim_w / 2),
        radius=round(n * 0.222),
        outline=(*MINT, 59),
        width=rim_w,
    )
    img = Image.alpha_composite(img, rim)
    return img.resize((size, size), Image.LANCZOS)


def make_tray(size: int = 256, ss: int = 2) -> Image.Image:
    n = size * ss
    bg = gradient(n, (0.34, 0.26), 120 / 108, BACKGROUND_STOPS)
    glow, ga = gradient(n, (0.5, 0.537), 46 / 108, ((0.0, MINT), (1.0, MINT)), (0.2, 0.0))
    arr = np.clip(bg * (1 - ga[..., None]) + glow * ga[..., None], 0, 255).astype(np.uint8)
    alpha = np.asarray(rounded_mask(size, radius_ratio=0.24, ss=ss).resize((n, n), Image.BILINEAR))
    img = Image.fromarray(np.dstack([arr, alpha]), "RGBA")
    img = draw_mark(img, ss)
    return img.resize((size, size), Image.LANCZOS)


def main():
    RES.mkdir(parents=True, exist_ok=True)
    app = make_icon(1024)
    app.save(RES / "app-icon.png")

    tray = make_tray(256)
    tray.save(RES / "tray.png")

    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    app.save(RES / "AegisVPN.ico", sizes=sizes)

    MOCKUPS.mkdir(parents=True, exist_ok=True)
    import base64

    def b64(path):
        return base64.b64encode(path.read_bytes()).decode("ascii")

    html = f"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><title>Aegis VPN — desktop icon</title>
<style>
body{{margin:0;padding:36px;background:#090914;color:#F3F0FF;font-family:'Segoe UI',system-ui,sans-serif}}
h1{{font-size:24px;margin:0 0 6px;font-weight:600}} p{{color:#9290AA;font-size:13px;margin:0 0 28px}}
.row{{display:flex;align-items:center;gap:34px;flex-wrap:wrap}}
.item{{text-align:center}} .cap{{font-size:10px;color:#9290AA;margin-top:10px}}
img{{display:block}} .big{{width:220px;height:220px;border-radius:8px}}
</style></head><body>
<h1>Новая иконка десктопа</h1>
<p>Тот же знак, что и на Android: тёмное стекло, мятный контур щита и галочка.</p>
<div class="row">
  <div class="item"><img class="big" src="data:image/png;base64,{b64(RES / 'app-icon.png')}"><div class="cap">app-icon.png · 1024</div></div>
  <div class="item"><img src="data:image/png;base64,{b64(RES / 'tray.png')}" width="96" height="96"><div class="cap">tray.png</div></div>
  <div class="item"><img src="data:image/png;base64,{b64(RES / 'tray.png')}" width="32" height="32"><div class="cap">в трее · 32</div></div>
  <div class="item" style="background:#E8EAF0;padding:12px;border-radius:10px"><img src="data:image/png;base64,{b64(RES / 'tray.png')}" width="32" height="32"><div class="cap" style="color:#555">на светлой панели</div></div>
</div>
</body></html>
"""
    (MOCKUPS / "desktop-icon-check.html").write_text(html, encoding="utf-8")
    print("icons:", RES)
    print("preview:", MOCKUPS / "desktop-icon-check.html")


if __name__ == "__main__":
    main()
