# Sequencer UI: client rendering architecture

## Still unimplemented (from this doc)

- Animated/resizable drawer panel and vanilla sound-event indexing.
- No concurrent-draft merging (not planned). The "Also editing" indicator and Reload/Commit conflict prompt have landed.
- No hazard-stripe overlay rendered on the node card body for missing/mismatched samples (only the theme's fallback texture is substituted).

Originally design-only; the editor screen, all 4 themes, and graph submission
described here are now implemented (see the update note in §1 and the build
order in §6). What's still missing is tracked in [FUTURE-WORK.md](FUTURE-WORK.md),
not this doc. Scope is deliberately client-side: the node editor is a GUI screen
that edits a `Graph` and submits it via `MusicPackets.Submit` — everything on the
server/sync/sample-decode side of that boundary belongs to the backend milestones
in [BACKEND-PLAN.md](BACKEND-PLAN.md). This doc exists so the four themes in the
UI spec are real, distinct behavior (per-theme escapement ticks, particle pulses,
stepped cables, scanlines) instead of a single renderer with palette swaps,
keeping the client code and the backend code from stepping on each other.

## 1. Where this sits in the existing code

`Graph` (`core-engine/src/main/java/groove/engine/Graph.java`) has no coordinate
fields — its own doc comment says "editor layout deliberately lives outside this
model." That's the load-bearing fact for everything below: **node position, pan/zoom,
selection, and drawer state are pure client UI state, kept next to the graph, never
inside it.** The editor reads/writes a `Graph` and layers a parallel
`EditorLayout` on top, keyed by node id.

**Update:** the C2S seam described below as a future interface was superseded
during implementation — see [FUTURE-WORK.md](FUTURE-WORK.md) for what's still
actually missing. `MusicPackets.Submit`/`SubmitResult` is the real C2S packet
(`GrooveEditorScreen.submit`); neither `PatchSubmission` nor `SampleRegistry`
exists as a standalone interface, and sample audition is real (Milestone 4
sample decode/cache landed). The original reasoning is kept below for history.

Originally, the only way a `Graph` reached the server was `/groove load` reading
`groove-patch.json` from the world folder (validated through `GraphJson.decode`,
same as the demo patch), and there was no C2S packet for "here's an edited graph."
The editor was built against a single seam for this:

```java
// superseded — see note above; MusicPackets.Submit replaced this interface
public interface PatchSubmission {
    void submit(Graph graph);
}
```

Same reservation existed for two other backend-owned things the spec assumed,
both now resolved:
- **Sample audition / real sample playback** — Milestone 4 landed; the Sample
  Vault's `[►]` audition/preview path decodes and plays for real.
- **Missing-asset detection** (§4 of the UI spec) — `SampleCatalog.Status` plus
  the inspector's status line cover this; no separate `SampleRegistry` interface
  was needed. See [FUTURE-WORK.md](FUTURE-WORK.md) for the remaining gap (no
  hazard-stripe marker on the node card itself).

## 2. Package layout (proposed)

All new code is client-only (`src/client/java`, matches the existing
`splitEnvironmentSourceSets()` split in `build.gradle`):

```
com.mervyn.groove.client.ui/
  GrooveEditorScreen.java      — extends Screen; owns EditorState, drives one frame
  EditorState.java             — selection, pan/zoom, drawer open/closed, dragged-wire-in-progress
  EditorLayout.java            — Map<String nodeId, Vec2> positions; load/save alongside the patch
  NodeView.java                — Graph.Node + resolved layout position + per-frame visual state
  CableView.java                — resolved (fromPort screen pos, toPort screen pos)
  InputController.java         — keyboard/mouse -> EditorState mutations (theme-agnostic, see §3)
  PatchSubmission.java          — the seam described above
  SampleRegistry.java           — the seam described above

com.mervyn.groove.client.ui.theme/
  ThemeRenderer.java             — the interface all 4 themes implement (see §4)
  TacticalRenderer.java
  ClockworkRenderer.java
  CrtRenderer.java
  VanillaRenderer.java
  ThemeAssets.java               — resolves the fixed texture-id manifest (§5) per theme
```

## 3. Interaction is one codepath, rendering is four

The event-priority ladder, drag/pan/zoom rules, port-snapping, and drop-to-swap
logic in §2–3 of the UI spec don't vary by theme — a dragged cable snaps at 12px
in Clockwork exactly like it does in CRT. So `InputController` and `EditorState`
are theme-agnostic and own all *interaction* state. `ThemeRenderer` only *reads*
that state to decide how to draw it and how its decorative animations advance.

This keeps the "real per-theme behavior" scope (your call) bounded to rendering
and decoration, not four divergent input handlers:

```java
public interface ThemeRenderer {
    void drawBackground(GuiGraphics g, int width, int height);
    void drawPanel(GuiGraphics g, PanelKind kind, int x, int y, int w, int h);
    void drawNodeCard(GuiGraphics g, NodeView node, boolean selected);
    void drawCable(GuiGraphics g, CableView cable);
    default void drawCablePulse(GuiGraphics g, CableView cable, float tempoPhase) {} // skipped when the player turns pulses off
    void drawPort(GuiGraphics g, int x, int y, PortState state); // free / compatible / incompatible / magnet
    void drawEuclidRing(GuiGraphics g, int x, int y, boolean[] steps);
    void tickDecorative(float partialTick); // scanline scroll, escapement advance, bloom timing — no-op for Vanilla
}
```

Where the four themes actually diverge, concretely:

| Concern | Tactical | Clockwork | CRT | Vanilla |
| --- | --- | --- | --- | --- |
| Cable geometry | smooth cubic bezier | bezier, heavier sag constant | straight vector segments (no bezier) | orthogonal stepped/pixelated path |
| Cable render | glow (bloom blur pass) | woven-texture stroke | 1px unshaded line | 2px hard-edge line |
| Live pulse | particle traveling along path | none specified (mechanical tick instead) | traveling bright segment, no blur | traveling filled square, no interpolation |
| Node chrome | 4px radius, drop shadow | brass faceplate, rivets, gear teeth | wireframe box, phosphor-flare border | nine-patch, beveled vanilla button style |
| Idle decoration | none | escapement tick advances a visible gear on a fixed interval | scanline scroll + phosphor persistence fade | none |
| Font | proportional UI font | serif | monospace bitmap | Minecraft's default font renderer |

`tempoPhase` (0–1 across a beat, driven by `SessionState`/`ClockSync` once wired)
is the only thing that needs to reach the renderer from the transport/audio side;
everything else is pure client animation state (`tickDecorative` gets a
partial-tick float like any other `Screen`).

## 4. Asset manifest — the contract between this and `generate_atlas.py`

Each `ThemeRenderer` assumes a **fixed set of texture identifiers per theme**,
under `assets/modid/textures/gui/sprites/sequencer/<theme>/...` — the `sprites`
segment matters, not just `textures/gui`: that's the directory Minecraft's GUI
sprite atlas auto-discovers, which is what makes `GuiGraphics.blitSprite` and its
nine-slice scaling work at all (see §4a). This list is the contract
`scratch/tools/generate_atlas.py` must produce against — the Java side never
special-cases filenames per theme, only the theme namespace segment changes:

```
panel_main.png            9-patch, main canvas frame
panel_drawer.png          9-patch, left/right drawer background
panel_transport.png       9-patch, top bar
node_header.png           9-patch, node title bar (default / selected variants)
node_body.png             9-patch, node body
port_free.png / port_compatible.png / port_incompatible.png / port_magnet.png
knob_base.png + knob_indicator.png   (rotated in code, not baked per-angle)
ring_step_off.png / ring_step_on.png
button_default.png / button_hover.png / button_disabled.png   9-patch
icon_play.png / icon_stop.png
```

One exception to "the Java side never special-cases filenames per theme": `hazard_missing_asset.png` is a single file at `sequencer/hazard_missing_asset.png`, outside every theme's own folder, not part of this per-theme manifest. See §4a.

9-patch slicing convention: every `panel_*`/`node_*`/`button_*` texture is
20x20px with a fixed 6px border on all sides (Minecraft's `NineSlice` component,
already available in 1.21.x) — one convention for all four themes, so the
renderer's draw calls don't need per-theme layout math, only per-theme textures.
Non-9-patch icons (`port_*`, `ring_step_*`, `icon_*`) are flat 16x16 for Vanilla's
pixel-art requirement, and simply scaled for the other three themes.

This manifest is what turns `generate_atlas.py` from a stub into real work; it
emits exactly this file list into `scratch/tools/build/<theme>/...`.
`vanilla` is copied from there into
`src/main/resources/assets/modid/textures/gui/sprites/sequencer/vanilla/` and
ships inside the mod jar, since it's the always-available base. `clockwork`,
`crt`, and `tactical` are packaged by `scratch/tools/package_resourcepacks.py`
into standalone `.zip` resourcepacks instead (see §5) — the mod jar does not
bundle them.

## 4a. `ThemeAssets` and the hazard fallback

`ThemeAssets.sprite(theme, name)` (client) is what every `ThemeRenderer` calls
instead of hand-rolling a `ResourceLocation`. It checks, once per sprite id and
cached until the next resource reload, whether any loaded pack (mod jar
included) actually backs that file. If not — e.g. `clockwork` is selected but
its resourcepack isn't installed — it substitutes the single bundled
`sequencer/hazard_missing_asset.png` sprite instead of Minecraft's checkerboard
missing-texture placeholder, so a mismatched theme/pack pairing is visibly
informative rather than silently broken.

`panel_*`/`node_*`/`button_*` sprites get a same-named `.png.mcmeta` with
`gui.scaling.type: nine_slice` (`width`/`height: 20`, `border: 6`), which is
what makes `GuiGraphics.blitSprite(id, x, y, w, h)` stretch them correctly at
any panel/node size. `port_*`, `ring_step_*`, `knob_*`, and `icon_*` have no
mcmeta and just stretch.

`drawPanel`, `drawNodeCard`, `drawPort`, `drawEuclidRing` are wired to real
sprites, and `button_*`/`icon_play`/`icon_stop` are now wired too via
`ThemedButton` (Apply/Play-Stop/Reload). `RotaryKnob` draws `knob_base` and rotates
`knob_indicator` through a 270-degree sweep in the Inspector; gestures use
`EditorState`/`InputController`. Cable geometry, backgrounds,
and idle decoration (bezier sag, scanlines, the escapement gear) stay
hand-drawn per theme; the manifest never included textures for those.

## 5. Theme selection vs. resourcepack distribution

Two different things share the word "theme" in the spec, worth keeping distinct:

- **Which `ThemeRenderer` is active** is a mod-side choice — currently the
  `theme` argument to `/groove-editor` (see `EditorCommands`) — because it
  selects Java code, not just textures: Clockwork's escapement tick and CRT's
  stepped-vs-bezier cable geometry are behavior, not skin.
- **Which textures back that theme** is a resourcepack concern. `vanilla` is
  bundled in the mod jar and always available. `clockwork`/`crt`/`tactical`
  are optional resourcepacks a player installs separately (built by
  `package_resourcepacks.py`) — picking that `ThemeRenderer` without the
  matching pack installed still works, just renders the hazard sprite
  wherever a themed texture is missing (§4a). A resourcepack further
  overriding e.g. `clockwork/node_header.png` with a player's own art works
  exactly like any other Minecraft resourcepack override, for free.

## 6. Suggested build order from here

1. ~~Confirm or amend the texture manifest in §4.~~ Done — see §4/§4a.
2. ~~Implement `generate_atlas.py` against that manifest for all 4 themes.~~ Done.
3. ~~Build `EditorState` + `InputController` + a no-op `ThemeRenderer`.~~ Done —
   `NoOpThemeRenderer` proved the interaction model against the real
   `Graph`/`GraphJson` types.
4. ~~Implement the 4 `ThemeRenderer`s against the generated textures.~~ Done for
   panels/nodes/ports/rings/buttons/icons and Inspector knobs (§4a).
5. ~~Wire graph submission to the server.~~ Done via `MusicPackets.Submit`
   (see the note at the top of §1). Remaining gaps are tracked in
   [FUTURE-WORK.md](FUTURE-WORK.md), not this build order.
