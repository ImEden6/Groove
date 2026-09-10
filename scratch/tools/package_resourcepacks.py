"""Packages the non-vanilla sequencer UI themes as standalone, distributable
resourcepacks.

Vanilla ships bundled inside the mod jar (src/main/resources/assets/modid/textures/gui/
sprites/sequencer/vanilla/); clockwork, crt, and tactical are optional -- a player drops
one into their resourcepacks folder to reskin the sequencer editor. ThemeAssets (client
Java) falls back to a bundled hazard texture for any theme whose pack isn't installed,
so these are safe to ship separately rather than baked into the jar.

Reads already-generated textures from generate_atlas.py's output (default: build/<theme>/),
adds nine-slice .mcmeta sidecars for the 9-patch files per the manifest in
docs/SEQUENCER-UI-ARCHITECTURE.md section 4, writes a pack.mcmeta, and zips the result.

Usage:
    python generate_atlas.py all --out build   # if not already generated
    python package_resourcepacks.py clockwork crt tactical
    python package_resourcepacks.py all --out build/resourcepacks
"""

import argparse
import json
import os
import shutil
import zipfile

NINE_SLICE_FILES = [
    "panel_main", "panel_drawer", "panel_transport",
    "node_header", "node_header_selected", "node_body",
    "button_default", "button_hover", "button_disabled",
]

NINE_SLICE_MCMETA = {
    "gui": {"scaling": {"type": "nine_slice", "width": 20, "height": 20, "border": 6}}
}

# Broad range so this doesn't nag on every 1.21.x patch bump; 34 is 1.21-1.21.1's own format.
PACK_FORMAT = 34
SUPPORTED_FORMATS = {"min_inclusive": 15, "max_inclusive": 100}

PACKAGEABLE_THEMES = ["clockwork", "crt", "tactical"]


def package_theme(theme, src_dir, out_dir):
    stage = os.path.join(out_dir, f"_stage_{theme}")
    if os.path.isdir(stage):
        shutil.rmtree(stage)
    sprites_dir = os.path.join(stage, "assets", "modid", "textures", "gui", "sprites", "sequencer", theme)
    os.makedirs(sprites_dir, exist_ok=True)

    theme_src = os.path.join(src_dir, theme)
    if not os.path.isdir(theme_src):
        raise SystemExit(f"No generated textures for {theme!r} at {theme_src} -- run generate_atlas.py first")

    for name in sorted(os.listdir(theme_src)):
        if not name.endswith(".png"):
            continue
        shutil.copy2(os.path.join(theme_src, name), os.path.join(sprites_dir, name))
        stem = name[:-4]
        if stem in NINE_SLICE_FILES:
            with open(os.path.join(sprites_dir, name + ".mcmeta"), "w") as f:
                json.dump(NINE_SLICE_MCMETA, f, indent=2)

    pack_mcmeta = {
        "pack": {
            "pack_format": PACK_FORMAT,
            "supported_formats": SUPPORTED_FORMATS,
            "description": f"Groove sequencer UI - {theme.capitalize()} theme",
        }
    }
    with open(os.path.join(stage, "pack.mcmeta"), "w") as f:
        json.dump(pack_mcmeta, f, indent=2)

    zip_path = os.path.join(out_dir, f"groove-{theme}.zip")
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(stage):
            for fname in files:
                full = os.path.join(root, fname)
                rel = os.path.relpath(full, stage)
                zf.write(full, rel)

    shutil.rmtree(stage)
    return zip_path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("theme", nargs="+", choices=[*PACKAGEABLE_THEMES, "all"],
                         help="Theme(s) to package (vanilla ships in the mod jar, not packaged here)")
    parser.add_argument("--src", default="build", help="Directory generate_atlas.py wrote into (default: build)")
    parser.add_argument("--out", default="build/resourcepacks", help="Output directory for the zips")
    args = parser.parse_args()

    targets = PACKAGEABLE_THEMES if "all" in args.theme else args.theme
    os.makedirs(args.out, exist_ok=True)
    for theme in targets:
        zip_path = package_theme(theme, args.src, args.out)
        print(f"{theme}: wrote {zip_path}")


if __name__ == "__main__":
    main()
