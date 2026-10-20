#!/usr/bin/env python3
"""Draws the IPTV app's Android TV banner (16:9) and launcher icons.

Run: .venv/bin/python apps/make_live_tv_icons.py
Writes into apps/live-tv/android/app/src/main/res/. Everything is drawn at 4x and downsampled
so edges are smooth.
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "live-tv/android/app/src/main/res")
FONT = os.path.join(HERE, "..", "fonts", "Outfit.ttf")
SS = 4  # supersampling

RED_TOP = (255, 82, 82)
RED_BOTTOM = (198, 40, 40)
DARK_A = (24, 24, 31)
DARK_B = (10, 10, 14)


def vertical_gradient(size, top, bottom):
    w, h = size
    g = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        g.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return g.resize((w, h))


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return m


def tv_glyph(size, color=(255, 255, 255, 255)):
    """Rounded TV screen with antenna and a play triangle, on transparency. size = square side."""
    s = size
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    stroke = round(s * 0.075)
    # screen
    l, t, r, b = s * 0.08, s * 0.30, s * 0.92, s * 0.90
    d.rounded_rectangle((l, t, r, b), radius=s * 0.13, outline=color, width=stroke)
    # antenna
    cx = s / 2
    d.line((cx, t + stroke * 0.2, s * 0.30, s * 0.08), fill=color, width=stroke)
    d.line((cx, t + stroke * 0.2, s * 0.70, s * 0.08), fill=color, width=stroke)
    for x in (s * 0.30, s * 0.70):
        d.ellipse((x - stroke * 0.5, s * 0.08 - stroke * 0.5, x + stroke * 0.5, s * 0.08 + stroke * 0.5), fill=color)
    # play triangle, optically centred
    pcx, pcy, ph = cx + s * 0.025, (t + b) / 2, s * 0.26
    d.polygon([(pcx - ph * 0.42, pcy - ph / 2), (pcx - ph * 0.42, pcy + ph / 2), (pcx + ph * 0.5, pcy)], fill=color)
    return img


def badge(size):
    """Red rounded-square app mark with the TV glyph."""
    s = size
    bg = vertical_gradient((s, s), RED_TOP, RED_BOTTOM).convert("RGBA")
    # soft top highlight
    hl = Image.new("RGBA", (s, s), (255, 255, 255, 0))
    ImageDraw.Draw(hl).ellipse((-s * 0.3, -s * 0.9, s * 1.3, s * 0.45), fill=(255, 255, 255, 26))
    bg = Image.alpha_composite(bg, hl.filter(ImageFilter.GaussianBlur(s * 0.16)))
    g = tv_glyph(round(s * 0.6))
    bg.alpha_composite(g, (round(s * 0.2), round(s * 0.19)))
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(bg, (0, 0), rounded_mask((s, s), round(s * 0.23)))
    return out


def font(px, weight):
    f = ImageFont.truetype(FONT, px)
    try:
        f.set_variation_by_axes([weight])
    except Exception:
        pass
    return f


def banner(w, h):
    W, H = w * SS, h * SS
    img = vertical_gradient((W, H), DARK_A, DARK_B).convert("RGBA")
    # red ambient glow behind the badge
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-W * 0.05, H * 0.05, W * 0.38, H * 0.95), fill=(229, 57, 53, 95))
    img = Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(W * 0.11)))

    bs = round(H * 0.46)
    bx, by = round(W * 0.085), (H - bs) // 2
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((bx, by + bs * 0.06, bx + bs, by + bs * 1.06), bs * 0.23, fill=(0, 0, 0, 120))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(bs * 0.08)))
    img.alpha_composite(badge(bs), (bx, by))

    d = ImageDraw.Draw(img)
    tx = bx + bs + round(W * 0.06)
    title = font(round(H * 0.25), 600)
    sub = font(round(H * 0.085), 500)
    tb = d.textbbox((0, 0), "Live TV", font=title)
    th = tb[3] - tb[1]
    sb = d.textbbox((0, 0), "INDIA  ·  1080p", font=sub)
    sh = sb[3] - sb[1]
    gap = round(H * 0.07)
    top = (H - (th + gap + sh)) // 2
    d.text((tx, top - tb[1]), "Live TV", font=title, fill=(255, 255, 255, 255))
    # small red LIVE dot before the subtitle
    r = round(sh * 0.36)
    sy = top + th + gap
    d.ellipse((tx, sy + sh / 2 - r, tx + 2 * r, sy + sh / 2 + r), fill=(255, 82, 82, 255))
    d.text((tx + 2 * r + round(H * 0.035), sy - sb[1]), "INDIA  ·  1080p", font=sub, fill=(170, 170, 182, 255))

    out = img.resize((w, h), Image.LANCZOS)
    return out.convert("RGB")


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print(path, img.size)


if __name__ == "__main__":
    # TV banner: 320x180 is the Android TV spec (xhdpi); larger copies stay sharp on denser screens
    for folder, (w, h) in {"drawable-xhdpi": (320, 180), "drawable-xxhdpi": (480, 270),
                           "drawable-xxxhdpi": (640, 360)}.items():
        save(banner(w, h), os.path.join(RES, folder, "banner.png"))

    # legacy square launcher icon
    for folder, px in {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
                       "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}.items():
        save(badge(px * SS).resize((px, px), Image.LANCZOS), os.path.join(RES, folder, "ic_launcher.png"))

    # adaptive icon layers (108dp canvas, glyph inside the 66dp safe zone)
    for folder, px in {"mipmap-mdpi": 108, "mipmap-hdpi": 162, "mipmap-xhdpi": 216,
                       "mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432}.items():
        S = px * SS
        save(vertical_gradient((S, S), RED_TOP, RED_BOTTOM).resize((px, px), Image.LANCZOS),
             os.path.join(RES, folder, "ic_launcher_adaptive_back.png"))
        fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        g = tv_glyph(round(S * 0.44))
        fg.alpha_composite(g, ((S - g.width) // 2, round(S * 0.27)))
        save(fg.resize((px, px), Image.LANCZOS), os.path.join(RES, folder, "ic_launcher_adaptive_fore.png"))
