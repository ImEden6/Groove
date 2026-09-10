# scratch/tools

Python generator for the sequencer UI's placeholder resourcepack textures — the
manifest from [docs/SEQUENCER-UI-ARCHITECTURE.md](../../docs/SEQUENCER-UI-ARCHITECTURE.md) §4,
for all 4 themes. Flat/simple placeholders, not final art — enough for the Java
`ThemeRenderer` implementations to load real files with the right names and
9-patch dimensions while the actual look gets iterated on.

## Setup

```bash
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

## Usage

```bash
python generate_atlas.py tactical          # one theme -> build/tactical/
python generate_atlas.py all --out build   # all 4 themes
```

Each theme directory gets the 20 textures listed in the architecture doc's
manifest: 3 panels, node header (+selected variant) and body, 4 port states,
knob base/indicator, 2 ring-step states, the missing-asset hazard icon, 3
button states, and play/stop icons. 9-patch textures are 20x20 with a 6px
border; flat icons are 16x16.

## Layout

- `generate_atlas.py` — the CLI and all drawing logic.
- `themes/__init__.py` — one `Theme` dataclass per theme (palette + corner
  style), meant to mirror the CSS custom properties in
  `scratch/previews/sequencer-theme-preview.html` so the HTML preview and the
  generated textures don't drift apart as either gets tuned.
- `requirements.txt` — currently just Pillow.
- `build/` — generated output (gitignored-worthy scratch, not committed art).

## Known placeholder gaps

- `node_header_selected.png` is `node_header.png` plus a code-drawn accent
  outline, not a distinct hand-designed state.
- Clockwork's "brass" and CRT's "wire" styles are the roughest approximations
  of the spec's visual treatment (real bevel/gear/scanline art needs actual
  design work, not procedural rectangles).
- No font atlas yet (CRT's bitmap font, Vanilla's Minecraft default) — text
  rendering is out of scope for this script.
