"""Per-theme palette/style config, shared by generate_atlas.py.

Kept separate from the generator so a palette tweak doesn't require touching
drawing code, and so these values can be cross-checked against the CSS custom
properties in scratch/previews/sequencer-theme-preview.html (they're meant to
match — same source of truth, two renderers).
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Theme:
    name: str
    # panel / node fills
    bg: tuple
    panel_bg: tuple
    panel_bg_2: tuple
    border: tuple
    border_soft: tuple
    # signal colors
    accent_trigger: tuple   # euclid / trigger signals
    accent_audio: tuple     # audio-rate signals
    accent_mod: tuple       # modulation signals
    danger: tuple
    # corner/border treatment: "rounded" | "brass" | "wire" | "bevel"
    corner_style: str
    # icon foreground (ring dots, hazard glyph, play/stop glyph)
    ink: tuple
    # optional decorative overlay drawn onto panel textures only
    scanlines: bool = False


THEMES = {
    "tactical": Theme(
        name="tactical",
        bg=(26, 28, 32, 255),
        panel_bg=(32, 35, 41, 255),
        panel_bg_2=(35, 38, 45, 255),
        border=(51, 55, 63, 255),
        border_soft=(42, 45, 51, 255),
        accent_trigger=(255, 170, 0, 255),
        accent_audio=(0, 229, 255, 255),
        accent_mod=(166, 255, 0, 255),
        danger=(255, 90, 90, 255),
        corner_style="rounded",
        ink=(231, 233, 238, 255),
    ),
    "clockwork": Theme(
        name="clockwork",
        bg=(232, 223, 208, 255),
        panel_bg=(220, 208, 186, 255),
        panel_bg_2=(239, 231, 216, 255),
        border=(140, 106, 31, 255),
        border_soft=(196, 184, 165, 255),
        accent_trigger=(184, 134, 11, 255),
        accent_audio=(140, 106, 31, 255),
        accent_mod=(109, 122, 61, 255),
        danger=(150, 40, 30, 255),
        corner_style="brass",
        ink=(44, 38, 32, 255),
    ),
    "crt": Theme(
        name="crt",
        bg=(8, 12, 10, 255),
        panel_bg=(10, 15, 12, 255),
        panel_bg_2=(13, 20, 15, 255),
        border=(31, 74, 48, 255),
        border_soft=(22, 53, 36, 255),
        accent_trigger=(51, 255, 102, 255),
        accent_audio=(51, 255, 102, 255),
        accent_mod=(51, 255, 102, 255),
        danger=(255, 90, 90, 255),
        corner_style="wire",
        ink=(51, 255, 102, 255),
        scanlines=True,
    ),
    "vanilla": Theme(
        name="vanilla",
        bg=(55, 55, 55, 255),
        panel_bg=(198, 198, 198, 255),
        panel_bg_2=(139, 139, 139, 255),
        border=(55, 55, 55, 255),
        border_soft=(84, 84, 84, 255),
        accent_trigger=(85, 255, 85, 255),
        accent_audio=(85, 85, 255, 255),
        accent_mod=(255, 255, 85, 255),
        danger=(255, 85, 85, 255),
        corner_style="bevel",
        ink=(26, 26, 26, 255),
    ),
}
