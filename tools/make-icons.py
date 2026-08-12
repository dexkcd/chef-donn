#!/usr/bin/env python3
"""Generate the PWA icon set for the three apps.

Kept in the repo so the icons are reproducible rather than mystery binaries:
each is a rounded square in a brand color with the role letter set in Archivo
Black, the same display face the apps use. Maskable variants inset the letter
into the 80% safe zone so Android's circle/squircle crop never clips it.

    pip install pillow fonttools brotli
    python3 tools/make-icons.py

The shipped woff2 is the only copy of the font: it is decompressed to a temp
TTF here because Pillow cannot read woff2 directly.
"""

import tempfile
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
WOFF2 = ROOT / "web" / "fonts" / "archivo_variable.woff2"

# bg, fg — straight from tokens.css
ROLES = {
    "waiter": ("W", "#0F4D3A", "#F4F1EA"),
    "kitchen": ("K", "#14201B", "#F0BD5E"),
    "cashier": ("C", "#D9A441", "#1A1A1A"),
}


def draw_icon(size: int, letter: str, bg: str, fg: str, maskable: bool, font_path: str) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    if maskable:
        # Full bleed: the platform supplies the mask.
        draw.rectangle((0, 0, size, size), fill=bg)
        cap = size * 0.42
    else:
        radius = int(size * 0.22)
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=bg)
        cap = size * 0.56

    font = ImageFont.truetype(font_path, int(cap))
    try:
        font.set_variation_by_axes([900])  # Archivo Black
    except OSError:
        pass

    box = draw.textbbox((0, 0), letter, font=font)
    draw.text(
        ((size - (box[2] - box[0])) / 2 - box[0], (size - (box[3] - box[1])) / 2 - box[1]),
        letter,
        font=font,
        fill=fg,
    )
    return img


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        ttf = Path(tmp) / "archivo.ttf"
        font = TTFont(WOFF2)
        font.flavor = None
        font.save(ttf)

        for role, (letter, bg, fg) in ROLES.items():
            out = ROOT / "web" / role / "icons"
            out.mkdir(parents=True, exist_ok=True)
            for size in (192, 512):
                draw_icon(size, letter, bg, fg, False, str(ttf)).save(out / f"icon-{size}.png")
            draw_icon(512, letter, bg, fg, True, str(ttf)).save(out / "icon-maskable-512.png")
            print(f"{role}: wrote 3 icons to {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
