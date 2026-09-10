"""Generates placeholder resourcepack textures for the sequencer UI themes.

Produces exactly the manifest from docs/SEQUENCER-UI-ARCHITECTURE.md §4, for one
theme or all four, into an output directory. These are flat/simple placeholders
meant to unblock building the Java ThemeRenderer implementations against real
files with the right names and dimensions -- not final art.

Naming note vs. the manifest text: "node_header.png (default / selected variants)"
is implemented as two separate files, node_header.png and node_header_selected.png,
so ThemeRenderer never has to branch on anything but the theme directory.

Usage:
    python generate_atlas.py tactical
    python generate_atlas.py all --out build
"""

import argparse
import os

from PIL import Image, ImageDraw

from themes import THEMES

NINE_PATCH_SIZE = 20
NINE_PATCH_BORDER = 6
ICON_SIZE = 16


def _rounded_panel(theme, fill, border_w=2, radius=4):
    img = Image.new("RGBA", (NINE_PATCH_SIZE, NINE_PATCH_SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    box = (0, 0, NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1)
    d.rounded_rectangle(box, radius=radius, fill=fill)
    d.rounded_rectangle(box, radius=radius, outline=theme.border, width=border_w)
    return img


def _brass_panel(theme, fill):
    img = Image.new("RGBA", (NINE_PATCH_SIZE, NINE_PATCH_SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # vertical gradient fill to suggest a brushed/faceted plate
    top = tuple(min(255, c + 22) for c in fill[:3]) + (255,)
    bottom = tuple(max(0, c - 18) for c in fill[:3]) + (255,)
    for y in range(NINE_PATCH_SIZE):
        t = y / (NINE_PATCH_SIZE - 1)
        row = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (NINE_PATCH_SIZE - 1, y)], fill=row)
    box = (0, 0, NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1)
    d.rectangle(box, outline=theme.border, width=2)
    # rivets in the corners
    rivet_positions = [(3, 3), (NINE_PATCH_SIZE - 4, 3), (3, NINE_PATCH_SIZE - 4),
                        (NINE_PATCH_SIZE - 4, NINE_PATCH_SIZE - 4)]
    for (rx, ry) in rivet_positions:
        d.ellipse((rx - 1, ry - 1, rx + 1, ry + 1), fill=(60, 50, 30, 255))
    return img


def _wire_panel(theme, fill):
    img = Image.new("RGBA", (NINE_PATCH_SIZE, NINE_PATCH_SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    box = (0, 0, NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1)
    d.rectangle(box, fill=fill)
    d.rectangle(box, outline=theme.border, width=1)
    if theme.scanlines:
        for y in range(0, NINE_PATCH_SIZE, 2):
            d.line([(0, y), (NINE_PATCH_SIZE - 1, y)], fill=(0, 0, 0, 40))
    return img


def _bevel_panel(theme, fill, pressed=False):
    img = Image.new("RGBA", (NINE_PATCH_SIZE, NINE_PATCH_SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    box = (0, 0, NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1)
    d.rectangle(box, fill=fill)
    light, dark = (255, 255, 255, 255), (85, 85, 85, 255)
    if pressed:
        light, dark = dark, light
    d.line([(0, 0), (NINE_PATCH_SIZE - 1, 0)], fill=light, width=2)
    d.line([(0, 0), (0, NINE_PATCH_SIZE - 1)], fill=light, width=2)
    d.line([(0, NINE_PATCH_SIZE - 1), (NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1)], fill=dark, width=2)
    d.line([(NINE_PATCH_SIZE - 1, 0), (NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1)], fill=dark, width=2)
    return img


def panel(theme, fill, variant="default"):
    if theme.corner_style == "rounded":
        return _rounded_panel(theme, fill)
    if theme.corner_style == "brass":
        return _brass_panel(theme, fill)
    if theme.corner_style == "wire":
        return _wire_panel(theme, fill)
    if theme.corner_style == "bevel":
        return _bevel_panel(theme, fill, pressed=(variant == "pressed"))
    raise ValueError(f"Unknown corner style {theme.corner_style!r}")


def icon_canvas():
    return Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))


def port_icon(theme, color, ring_only=False):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    box = (3, 3, ICON_SIZE - 4, ICON_SIZE - 4)
    if ring_only:
        d.ellipse(box, outline=color, width=2)
    else:
        d.ellipse(box, fill=color, outline=theme.bg, width=1)
    return img


def knob_base(theme):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    d.ellipse((1, 1, ICON_SIZE - 2, ICON_SIZE - 2), fill=theme.panel_bg_2, outline=theme.border, width=1)
    return img


def knob_indicator(theme):
    # a single tick at 12 o'clock; ThemeRenderer rotates this quad around center per value
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    cx = ICON_SIZE // 2
    d.line([(cx, 1), (cx, 6)], fill=theme.accent_trigger, width=2)
    return img


def ring_step(theme, on):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    box = (2, 2, ICON_SIZE - 3, ICON_SIZE - 3)
    if on:
        d.ellipse(box, fill=theme.accent_trigger)
    else:
        d.ellipse(box, outline=theme.accent_trigger, width=2)
    return img


def hazard_missing_asset(theme):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    # diagonal yellow/black hazard stripes clipped to a triangle, plus "!" mark
    stripe = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stripe)
    for i in range(-ICON_SIZE, ICON_SIZE * 2, 4):
        color = (255, 200, 0, 255) if (i // 4) % 2 == 0 else (30, 30, 30, 255)
        sd.polygon([(i, 0), (i + 4, 0), (i + 4 - ICON_SIZE, ICON_SIZE), (i - ICON_SIZE, ICON_SIZE)], fill=color)
    mask = Image.new("L", (ICON_SIZE, ICON_SIZE), 0)
    md = ImageDraw.Draw(mask)
    md.polygon([(ICON_SIZE // 2, 1), (ICON_SIZE - 1, ICON_SIZE - 1), (0, ICON_SIZE - 1)], fill=255)
    img.paste(stripe, (0, 0), mask)
    d.line([(ICON_SIZE // 2, 6), (ICON_SIZE // 2, 10)], fill=(20, 20, 20, 255), width=2)
    d.point((ICON_SIZE // 2, 12), fill=(20, 20, 20, 255))
    return img


def icon_play(theme):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    d.polygon([(4, 3), (4, ICON_SIZE - 3), (ICON_SIZE - 3, ICON_SIZE // 2)], fill=theme.accent_mod)
    return img


def icon_stop(theme):
    img = icon_canvas()
    d = ImageDraw.Draw(img)
    d.rectangle((4, 4, ICON_SIZE - 5, ICON_SIZE - 5), fill=theme.ink)
    return img


def build_theme(theme, out_dir):
    os.makedirs(out_dir, exist_ok=True)

    files = {
        "panel_main.png": panel(theme, theme.bg),
        "panel_drawer.png": panel(theme, theme.panel_bg),
        "panel_transport.png": panel(theme, theme.panel_bg),
        "node_header.png": panel(theme, theme.panel_bg_2),
        "node_header_selected.png": panel(theme, theme.panel_bg_2),  # border tinted below
        "node_body.png": panel(theme, theme.panel_bg),
        "port_free.png": port_icon(theme, theme.border_soft, ring_only=True),
        "port_compatible.png": port_icon(theme, theme.accent_audio),
        "port_incompatible.png": port_icon(theme, theme.danger),
        "port_magnet.png": port_icon(theme, theme.accent_audio),
        "knob_base.png": knob_base(theme),
        "knob_indicator.png": knob_indicator(theme),
        "ring_step_off.png": ring_step(theme, on=False),
        "ring_step_on.png": ring_step(theme, on=True),
        "hazard_missing_asset.png": hazard_missing_asset(theme),
        "button_default.png": panel(theme, theme.panel_bg_2),
        "button_hover.png": panel(theme, theme.panel_bg_2),
        "button_disabled.png": panel(theme, theme.panel_bg),
        "icon_play.png": icon_play(theme),
        "icon_stop.png": icon_stop(theme),
    }

    # selected header gets an accent-colored outline overlay so it's visibly distinct
    outline = files["node_header_selected.png"].copy()
    d = ImageDraw.Draw(outline)
    d.rectangle((0, 0, NINE_PATCH_SIZE - 1, NINE_PATCH_SIZE - 1), outline=theme.accent_trigger, width=2)
    files["node_header_selected.png"] = outline

    for name, img in files.items():
        img.save(os.path.join(out_dir, name))

    return sorted(files.keys())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("theme", choices=[*THEMES.keys(), "all"], help="Theme to generate assets for")
    parser.add_argument("--out", default="build", help="Output directory (default: build)")
    args = parser.parse_args()

    targets = THEMES.keys() if args.theme == "all" else [args.theme]
    for name in targets:
        theme_out = os.path.join(args.out, name)
        written = build_theme(THEMES[name], theme_out)
        print(f"{name}: wrote {len(written)} textures to {theme_out}")


if __name__ == "__main__":
    main()
