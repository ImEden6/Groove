"""Static PNG mockup of GrooveEditorScreen for each ThemeRenderer, since there is no
running Minecraft client in this environment. Mirrors the exact draw calls in
src/client/java/com/mervyn/groove/client/ui/theme/*.java against Graph.demo()'s 6 nodes
so what gets eyeballed here matches what the real screen would draw, only in Pillow
instead of GuiGraphics. Not shipped, not imported by the mod, throwaway verification only.
"""

import math
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).parent / "build" / "preview"
OUT.mkdir(parents=True, exist_ok=True)

WIDTH, HEIGHT = 920, 620
NODE_W, NODE_HEADER_H, NODE_BODY_H = 148, 22, 40
NODE_H = NODE_HEADER_H + NODE_BODY_H

# Graph.demo(): id -> (type, column, row)
NODES = [
    ("bass", "tone", 0, 0),
    ("bassRhythm", "euclid", 1, 0),
    ("lead", "tone", 2, 0),
    ("leadRhythm", "euclid", 3, 0),
    ("mix", "stack", 0, 1),
    ("out", "output", 1, 1),
]
EDGES = [("bass", "bassRhythm"), ("lead", "leadRhythm"), ("bassRhythm", "mix"), ("leadRhythm", "mix"), ("mix", "out")]

TEMPO_PHASE = 0.35


def origin(col, row):
    return 60 + col * (NODE_W + 40), 60 + row * (NODE_H + 40)


POS = {nid: origin(c, r) for nid, _type, c, r in NODES}
TYPE = {nid: t for nid, t, _c, _r in NODES}


def has_output(nid):
    return TYPE[nid] != "output"


def has_input(nid):
    return TYPE[nid] not in ("tone", "generator/sample")


def output_port(pos):
    return pos[0] + NODE_W, pos[1] + NODE_H // 2


def input_port(pos):
    return pos[0], pos[1] + NODE_H // 2


def cubic(p0, p1, p2, p3, t):
    u = 1 - t
    return u ** 3 * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t ** 3 * p3


def rgb(hexstr):
    return tuple(int(hexstr[i:i + 2], 16) for i in (0, 2, 4))


def render_tactical():
    bg, panel, panel_alt, border = rgb("1A1C20"), rgb("202329"), rgb("23262D"), rgb("33373F")
    header_sel, cable_core, pulse, ink = rgb("FFAA00"), rgb("00E5FF"), rgb("FFAA00"), rgb("E7E9EE")
    img = Image.new("RGB", (WIDTH, HEIGHT), bg)
    draw = ImageDraw.Draw(img, "RGBA")
    draw.rectangle([0, 0, WIDTH, 24], fill=border)
    draw.rectangle([1, 1, WIDTH - 1, 23], fill=panel_alt)

    for from_id, to_id in EDGES:
        x0, y0 = output_port(POS[from_id])
        x3, y3 = input_port(POS[to_id])
        sag = min(140, max(24, abs(x3 - x0) * 0.4))
        x1, y1, x2, y2 = x0 + sag, y0, x3 - sag, y3
        points = [(cubic(x0, x1, x2, x3, t / 24), cubic(y0, y1, y2, y3, t / 24)) for t in range(25)]
        for i in range(len(points) - 1):
            draw.line([points[i], points[i + 1]], fill=cable_core + (80,), width=5)
        draw.line(points, fill=cable_core, width=2)
        px, py = cubic(x0, x1, x2, x3, TEMPO_PHASE), cubic(y0, y1, y2, y3, TEMPO_PHASE)
        draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=pulse)

    for nid, (x, y) in POS.items():
        selected = nid == "mix"
        draw.rectangle([x + 2, y + 3, x + NODE_W + 2, y + NODE_H + 3], fill=(0, 0, 0, 128))
        draw.rectangle([x, y, x + NODE_W, y + NODE_HEADER_H], fill=header_sel if selected else panel_alt)
        draw.rectangle([x, y + NODE_HEADER_H, x + NODE_W, y + NODE_H], fill=panel)
        draw.rectangle([x, y, x + NODE_W, y + NODE_H], outline=border, width=1)
        draw.text((x + 6, y + 6), f"{nid} ({TYPE[nid]})", fill=ink)
        if has_output(nid):
            px, py = output_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=cable_core)
        if has_input(nid):
            px, py = input_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=border)

    img.save(OUT / "tactical.png")


def render_clockwork():
    bg, panel, panel_alt = rgb("E8DFD0"), rgb("DCD0BA"), rgb("EFE7D8")
    border, border_soft, rivet = rgb("8C6A1F"), rgb("C4B8A5"), rgb("6D7A3D")
    header_sel, ink = rgb("B8860B"), rgb("2C2620")
    img = Image.new("RGB", (WIDTH, HEIGHT), bg)
    draw = ImageDraw.Draw(img, "RGBA")
    draw.rectangle([0, 0, WIDTH, 24], fill=border)
    draw.rectangle([2, 2, WIDTH - 2, 22], fill=panel)

    gx, gy, radius, spokes = WIDTH - 18, HEIGHT - 18, 8, 6
    for i in range(spokes):
        angle = 2 * math.pi * i / spokes
        tip = (gx + radius * math.cos(angle), gy + radius * math.sin(angle))
        draw.line([(gx, gy), tip], fill=border, width=2)
    draw.ellipse([gx - 3, gy - 3, gx + 3, gy + 3], fill=rivet)

    for from_id, to_id in EDGES:
        x0, y0 = output_port(POS[from_id])
        x3, y3 = input_port(POS[to_id])
        sag = min(160, max(40, abs(x3 - x0) * 0.7))
        x1, y1, x2, y2 = x0 + sag, y0 + sag * 0.3, x3 - sag, y3 + sag * 0.3
        points = [(cubic(x0, x1, x2, x3, t / 24), cubic(y0, y1, y2, y3, t / 24)) for t in range(25)]
        draw.line(points, fill=border, width=2)

    for nid, (x, y) in POS.items():
        selected = nid == "mix"
        draw.rectangle([x, y, x + NODE_W, y + NODE_HEADER_H], fill=header_sel if selected else border)
        draw.rectangle([x, y + NODE_HEADER_H, x + NODE_W, y + NODE_H], fill=panel)
        draw.ellipse([x + 3, y + 3, x + 5, y + 5], fill=rivet)
        draw.ellipse([x + NODE_W - 5, y + 3, x + NODE_W - 3, y + 5], fill=rivet)
        draw.rectangle([x, y, x + NODE_W, y + NODE_H], outline=border, width=1)
        draw.text((x + 6, y + 6), f"{nid} ({TYPE[nid]})", fill=(255, 255, 255) if selected else ink)
        if has_output(nid):
            px, py = output_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=rivet)
        if has_input(nid):
            px, py = input_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=border_soft)

    img.save(OUT / "clockwork.png")


def render_crt():
    bg, panel = rgb("080C0A"), rgb("0A0F0C")
    border, border_soft, phosphor = rgb("1F4A30"), rgb("163524"), rgb("33FF66")
    img = Image.new("RGB", (WIDTH, HEIGHT), bg)
    draw = ImageDraw.Draw(img, "RGBA")
    for y in range(0, HEIGHT, 4):
        draw.line([(0, y), (WIDTH, y)], fill=phosphor + (24,), width=1)
    draw.rectangle([0, 0, WIDTH, 24], outline=border, width=1)
    draw.rectangle([1, 1, WIDTH - 1, 23], fill=panel)

    for from_id, to_id in EDGES:
        x0, y0 = output_port(POS[from_id])
        x1, y1 = input_port(POS[to_id])
        draw.line([(x0, y0), (x1, y1)], fill=border_soft, width=1)
        px, py = x0 + (x1 - x0) * TEMPO_PHASE, y0 + (y1 - y0) * TEMPO_PHASE
        length = max(1, math.hypot(x1 - x0, y1 - y0))
        ux, uy = (x1 - x0) / length, (y1 - y0) / length
        segment = max(6, length * 0.08)
        draw.line([(px - ux * segment / 2, py - uy * segment / 2), (px + ux * segment / 2, py + uy * segment / 2)], fill=phosphor, width=1)

    for nid, (x, y) in POS.items():
        selected = nid == "mix"
        draw.rectangle([x, y, x + NODE_W, y + NODE_H], fill=panel)
        frame = phosphor if selected else border
        draw.rectangle([x, y, x + NODE_W, y + NODE_H], outline=frame, width=1)
        draw.line([(x, y + NODE_HEADER_H), (x + NODE_W, y + NODE_HEADER_H)], fill=border_soft, width=1)
        draw.text((x + 6, y + 6), f"{nid} ({TYPE[nid]})", fill=phosphor)
        if has_output(nid):
            px, py = output_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=phosphor)
        if has_input(nid):
            px, py = input_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=border_soft)

    img.save(OUT / "crt.png")


def render_vanilla():
    bg, panel, panel_alt = rgb("373737"), rgb("C6C6C6"), rgb("8B8B8B")
    border, cable, bevel_light, bevel_dark = rgb("373737"), rgb("1A1A1A"), rgb("FFFFFF"), rgb("545454")
    header_sel = rgb("5555FF")
    img = Image.new("RGB", (WIDTH, HEIGHT), bg)
    draw = ImageDraw.Draw(img, "RGBA")
    draw.rectangle([0, 0, WIDTH, 24], fill=panel)
    draw.line([(0, 0), (WIDTH, 0)], fill=bevel_light, width=1)
    draw.line([(0, 23), (WIDTH, 23)], fill=bevel_dark, width=1)

    for from_id, to_id in EDGES:
        x0, y0 = output_port(POS[from_id])
        x1, y1 = input_port(POS[to_id])
        mid_x = x0 + (x1 - x0) // 2
        draw.line([(x0, y0), (mid_x, y0)], fill=cable, width=2)
        draw.line([(mid_x, y0), (mid_x, y1)], fill=cable, width=2)
        draw.line([(mid_x, y1), (x1, y1)], fill=cable, width=2)
        steps = 8
        step = min(steps - 1, int(TEMPO_PHASE * steps))
        t = step / (steps - 1)
        if t < 0.5:
            px, py = x0 + (mid_x - x0) * (t * 2), y0
        else:
            t2 = (t - 0.5) * 2
            px, py = mid_x + (x1 - mid_x) * t2, y0 + (y1 - y0) * t2
        draw.rectangle([px - 3, py - 3, px + 3, py + 3], fill=cable)

    for nid, (x, y) in POS.items():
        selected = nid == "mix"
        draw.rectangle([x, y, x + NODE_W, y + NODE_HEADER_H], fill=header_sel if selected else panel_alt)
        draw.rectangle([x, y + NODE_HEADER_H, x + NODE_W, y + NODE_H], fill=panel)
        draw.line([(x, y), (x + NODE_W, y)], fill=bevel_light, width=1)
        draw.line([(x, y), (x, y + NODE_H)], fill=bevel_light, width=1)
        draw.line([(x, y + NODE_H - 1), (x + NODE_W, y + NODE_H - 1)], fill=bevel_dark, width=1)
        draw.line([(x + NODE_W - 1, y), (x + NODE_W - 1, y + NODE_H)], fill=bevel_dark, width=1)
        draw.text((x + 6, y + 6), f"{nid} ({TYPE[nid]})", fill=(0, 0, 0) if not selected else (255, 255, 255))
        if has_output(nid):
            px, py = output_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=rgb("55FF55"))
        if has_input(nid):
            px, py = input_port((x, y))
            draw.ellipse([px - 3, py - 3, px + 3, py + 3], fill=bevel_dark)

    img.save(OUT / "vanilla.png")


if __name__ == "__main__":
    render_tactical()
    render_clockwork()
    render_crt()
    render_vanilla()
    print(f"wrote previews to {OUT}")
