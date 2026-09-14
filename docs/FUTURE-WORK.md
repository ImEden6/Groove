# Future work / not yet implemented

Single tracker for what the mod does not do yet, cross-checked against the active
codebase. The companion docs ([BACKEND-PLAN.md](BACKEND-PLAN.md),
[BACKEND-USAGE.md](BACKEND-USAGE.md),
[EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md),
[SEQUENCER-UI-ARCHITECTURE.md](SEQUENCER-UI-ARCHITECTURE.md),
[SAMPLES.md](SAMPLES.md),
[ENGINE-EVOLUTION.md](ENGINE-EVOLUTION.md),
[PHASE-2-SCHEDULER.md](PHASE-2-SCHEDULER.md))
describe what currently exists and how to use it; this document tracks remaining features,
known limitations, and deferred items. [EDITOR-BLOCK-DESIGN.md](EDITOR-BLOCK-DESIGN.md) is
a proposed (not yet implemented) design for the two items below marked with it.

## Priority Ranking (Open Items)

Ranked by impact on gameplay, creative expressiveness, and multiplayer usability:

| Rank | Item | Value Points | Effort / Complexity | Impact Area | Key Bottleneck Solved | Status |
|---|---|---|---|---|---|---|
| **1** | **Headphone Item** | **95** | Medium | Gameplay & Audio | Item, Trinkets equip slot, priority routing over speaker/positional audio, and per-editor live draft monitoring while linked and in range have landed. Remaining: a true `AL_SOURCE_RELATIVE` head-locked sink and underwater muffling. | Partial |
| **2** | **Server Resourcepack Distribution** | **80** | High | Multiplayer UX | Full catalog browsing on join plus a permission-gated `/groove-samples install <id>` with disk persistence and eviction have landed. Remaining: no automatic bulk push of everything to every player (a deliberate scope choice), and no vanilla-resource-pack-style compressed bundle transfer. | Partial |
| **3** | **Sample Drawer Hierarchy & Favorites** | **75** | Low–Medium | Editor UX | Speeds up browsing large custom sample packs beyond a flat list. | Open |
| **4** | **Direct Entry & Keyboard Controls for Knobs** | **70** | Low | Accessibility & UX | Precision value entry and arrow-key stepping without pixel-hunting rotary drags. | Implemented |
| **5** | **Workstation "In-Use" / Merge UI** | **60** | Medium | Multiplayer UX | Clear player feedback when multiple users attempt simultaneous sequencer edits (exclusive edit lock vs. optimistic conflict resolution). | Open |
| **6** | **Master Peak Limiter Test Suite** | **50** | Low | Audio Safety | Automated verification that adversarial DSP graphs cannot distort or clip past safety ceilings beyond `tanh`. | Open |
| **7** | **Cable Pulse Animation Toggle** | **45** | Low | Accessibility | Option to reduce visual motion / flashing during fast BPM sessions. | Open |
| **8** | **Phase 2 routing extensions** | **35** | Very High | Architecture | Independent audio-render sources and arbitrary pattern triggers/polyphonic envelopes have landed (each up to eight, shared event budget). Late joins/resyncs now approximate effect history with bounded local replay and a fade-in; exact historical state remains a refinement. | Implemented (3 of 3; bounded history) |

---

## Recently Landed Features

The following items from earlier roadmaps are fully implemented and verified in the codebase:

- **Independent Audio Sources ([PHASE-2-SIGNALS.md](PHASE-2-SIGNALS.md))**:
  - Up to eight independent pattern-to-audio pipelines with private voice pools and scheduler windows.
  - Separate filter/delay routes, shared upstream patterns, and a combined 128-event cost budget.
  - Multi-source sample playback, worker refill, seek/recovery, packet/persistence and allocation checks.
- **Arbitrary Pattern Triggers & Polyphonic Envelopes ([PHASE-2-SIGNALS.md](PHASE-2-SIGNALS.md))**:
  - `trigger_render` lets any pattern subgraph drive an `envelope`, not just `step_sequence`; up to eight per graph.
  - Overlapping triggers combine with MAX, keeping an envelope's output within its documented 0-1 range.
  - Stateless per-call window scan preserves late-join/backward-seek determinism; zero-allocation rendering.

- **Phase 1 DSP Primitives ([ENGINE-EVOLUTION.md](ENGINE-EVOLUTION.md))**:
  - Filter on `sample` nodes (`cutoffHz` 20–20000 Hz, `resonanceQ` 0.1–20).
  - Configurable filter resonance $Q \in [0.1, 20.0]$ across `Tone` and `SampleVoice` using RBJ biquad formulas.
  - Voice-stealing micro-crossfade (2.5 ms raised-cosine ramp-down on displaced voices).
  - Bandlimited 48-tap Kaiser-windowed sinc resampler with 4 prefiltered octave levels (68.21 dB stopband rejection).
- **Physical Speaker Presence ([BACKEND-USAGE.md](BACKEND-USAGE.md))**:
  - Placeable speaker blocks (`GrooveBlocks.SPEAKER`) stacking up to 32 blocks tall into towers.
  - Height-scaled positional mono audio and distance culling (8 nearest towers within 64 blocks).
- **Phase 2 Stage 1 Scheduler ([PHASE-2-SCHEDULER.md](PHASE-2-SCHEDULER.md))**:
  - Fractional `FAST.factor` in $[0.25, 16.0]$ preserved in compiler and editor with 2-decimal precision.
  - 64-slot rolling lookahead scheduler ring preparing 4 cycles ahead with anchor-gated sample tails.
  - Live speaker tower graph synchronization.

---

## DSP / Engine (Remaining)

- **Exact effect-history reconstruction.** The three Phase 2 extensions have landed, including up to one second of local delay/filter replay for late joins and resyncs; see [signals](PHASE-2-SIGNALS.md). Recovering older feedback or state across graph/tempo revisions would require a richer history or authoritative snapshots. The current bounded approximation does not provide exact historical equivalence.
- **No tempo automation, seeking, or non-integer-cycle start.** Dynamic tempo changes at runtime exist via `/groove tempo`, but score/pattern-side tempo curves and timeline seeking do not.

## Physical Presence in the World (Remaining)

- **Speaker tower advanced routing & occlusion.** The core speaker tower block and positional audio system is functional. Future work includes in-world sound occlusion through intervening solid blocks, per-player bus configuration, and server-side distance validation.
- **Graceful stream fade-out and dimension-change handling have landed.** Chunk unload, range changes, and invalidated speaker links fade out over 300ms of newly generated PCM (`GrooveAudioStream.fadeOut`), after any already-queued audio. Retired streams remain tracked until the channel drains, with a bounded cleanup deadline for stalled channels. Disconnect, dimension change, pause, audition, and headphone switching stop both active and retiring streams immediately.
- **Headphone item lacks a true head-locked sink and underwater muffling.** A wearable headphone item with a Trinkets equip slot has landed; wearing it silences nearby speaker positional audio and, once linked to an editor block, plays that editor's live draft as a private non-positional feed (`MusicClient`). It still plays through a plain non-positional sound rather than a true `AL_SOURCE_RELATIVE` head-locked sink, and there is no underwater muffling filter.
- **Live draft-graph monitoring through headphones has landed.** Holding the headphones and right-clicking an editor block records a persistent, exclusive link on that item (survives trading/dropping/relogin). While worn and within 16 blocks of the bound editor, headphones now poll and play that editor's live draft privately (`HeadphonePackets.Preview`/`Draft`), using the shared server transport phase and continuous lookahead. Worn links auto-unlink server-side outside that range or across dimensions; unlinked or unavailable previews are silent. See [EDITOR-BLOCK-DESIGN.md](EDITOR-BLOCK-DESIGN.md).

## Editor UI (Remaining)

- **Rotary knob keyboard & direct entry have landed.** Sprite-backed rotary controls support vertical dragging, fine adjustment via Ctrl, and undo/redo. Clicking a knob (or Up/Down once a node is selected) gives it keyboard focus, shown as an outline; Left/Right steps its value by a percentage of the param's actual range (Ctrl for a finer step), and Enter opens a numeric entry field that types an exact value straight through the same clamp a drag would apply. Each step or entry is its own undo entry.
- **Sample drawer polish.** The sample drawer is currently a flat scrollable/filterable list. Favorites, an animated/resizable drawer panel, vanilla sound-event indexing, and hierarchical pack-tree navigation are deferred.
- **No concurrent-draft merging.** Block editors use last-write-wins shared drafts, with stale-revision rejection at Commit. The legacy global session still accepts one pending edit at a time. Automatic graph merging is not planned by the block design.
- **Sample CRC mismatch warning on node cards.** Missing-asset detection functions correctly via `SampleCatalog.Status` and inspector status text, but there is no visual hazard-stripe overlay rendered directly across the node card body for missing samples (only the theme's fallback texture is substituted).

## Server Sync & Multiplayer (Remaining)

- **No automatic resync tuning beyond fixed thresholds.** Transport clock synchronization operates via documented slew and step thresholds ([BACKEND-USAGE.md](BACKEND-USAGE.md)), but adaptive PID/slew tuning under asymmetric or high-jitter network conditions has not been implemented.
- **No server-side chunk-unload or distance culling for audio state.** The session is server-wide and persistent, decoupled from individual chunk lifecycles or entity unload events.
- **GC pressure reduction.** The DSP mixer avoids per-render allocations, but pattern queries and the Minecraft audio stream adapter still allocate temporary buffers per tick/block.
- **Editor block design is functionally complete.** Placed editors own independent persistent draft/committed sessions, ownership, an owner-managed Access UI, a Speakers panel to link/unlink nearby speakers, and headphone binding — both connection types now carry real audio (headphones play the linked draft privately, linked speakers play the committed patch positionally), and the legacy global monitor stream is retired. Stale links on stored/unworn headphones are now also cleaned up (`HeadphonesItem.inventoryTick`, throttled, alongside the existing worn self-heal in `HeadphoneServer`), scoped to a player's own inventory — a pair left in a chest, dropped on the ground, or in an item frame is not swept. See [EDITOR-BLOCK-DESIGN.md](EDITOR-BLOCK-DESIGN.md).

## Distribution & Custom Samples (Remaining)

- **Catalog browsing and explicit install have landed; bulk push has not.** On join, the server advertises its full custom catalog listing (id+hash only) so `/groove-samples list` can browse everything, and `/groove-samples install <id>` (operator level 2) explicitly fetches a specific asset into a separate persistent download cache, with eviction limited to managed downloads ([SAMPLES.md](SAMPLES.md)). Assets already referenced by the shared graph still auto-fetch as before. Deliberately not built: an automatic bulk push of every custom asset's bytes to every joining player, and a vanilla-resource-pack-style compressed bundle transfer.

## Accessibility & Safety (Remaining)

- **No formal master limiter guarantee.** Output is bounded by non-bypassable `tanh` soft saturation ([SPECS.md §5](SPECS.md)), but there is no lookahead brickwall limiter or automated test asserting peak ceilings under extreme adversarial sum inputs.
- **No toggle for cable pulses.** Cable beat-pulse animations run at tempo phase across animated themes; an accessibility setting to disable high-frequency cable pulses is not yet implemented.

## Superseded Documentation Claims

- [SEQUENCER-UI-ARCHITECTURE.md](SEQUENCER-UI-ARCHITECTURE.md) previously sketched hypothetical `PatchSubmission` and `SampleRegistry` interfaces; these were superseded by the real network packets `MusicPackets.Submit` / `MusicPackets.SubmitResult` and `SampleCatalog`.
- Themed button and knob draw calls now use real sprite resources (`ThemedButton` wires Apply/Play-Stop/Reload and `RotaryKnob` renders inspector dials).

Comparing your design directly against what makes **Strudel and TidalCycles** so expressive identified the following capability areas; musical pattern primitives have now landed.

While your execution pipeline (OpenAL streaming, rolling lookahead scheduler, sample caching, and UI themes) is solid, your composition model remains closer to an **analog modular Eurorack sequencer** than a true **Strudel algorithmic pattern engine**.

Here are the functional gaps:

---

### 1. Musical pattern primitives - implemented

`alternate`, seeded `probability`, and `polymeter` now provide multi-cycle
progressions, event dropping, and sequences that phase across bar lines.
See [musical pattern nodes](MUSICAL-PATTERNS.md) for semantics, connection
ordering, limits, and examples. A dedicated input-order editor is still deferred.

---

### 2. Musical Pitch: Note Quantization & Scale Systems

Right now, your pitch model is purely physical: raw frequency in Hz ($20\text{--}16000\text{ Hz}$) or pitch ratios ($0.25\text{--}4.0$). Strudel's musical power comes from scales and pitch theory:

* **Scale Quantizer Node:**
* Takes continuous pitch ratios or integer scale degrees ($0, 1, 2, 3\dots$) and forces them into musical scales (Minor Pentatonic, Dorian, Phrygian, Major, Blues).
* Inputs: Root Note (e.g., $C3$), Scale Selector.
* Formula:

$$\text{frequency} = 440 \times 2^{\frac{\text{scaleInterval} - 69}{12}}$$




* **Chord Expander Node:**
* Takes a root note and outputs polyphonic chord triads/sevenths (e.g., `min7`, `sus4`, `dom7`) to eliminate manually wiring 3 separate Tone nodes just to build a chord.



---

### 3. Granular Sample Chopping & Slicing (`chop` / `slice`)

One of Strudel’s most famous live-coding tricks is breakbeat slicing (jungle/drum & bass chops on the Amen break):

* *Strudel Concept:* `s("amen").slice(8, "0 3 2 5 6 1 4 7")` chops an audio sample into 8 equal slices and rearranges their trigger order.
* *Current Limitation:* Your `generator/sample` node only plays samples from offset $0$ to the end of the file.
* *Missing Node:* A **`Slice` / `Chop` Node**:
* Parameters: `slices` (integer, e.g., 8 or 16), `index` (which slice to play, or modulated by an LFO/Euclid).
* DSP Implementation: Offsets the source PCM playback pointer to:

$$\text{startFrame} = \left(\frac{\text{index}}{\text{slices}}\right) \times \text{totalFrames}$$





---

### 4. World-to-Music Mechanics (Environmental Envelopes)

Because redstone was removed, the mod needs native world-interaction hooks to avoid feeling like an isolated app running inside a Minecraft window:

* **Environmental Modulator Nodes:**
* **Day/Night Cycle Node:** Outputs a continuous float $[0.0\text{--}1.0]$ following the sun/moon, perfect for opening filter cutoffs at high noon and dropping to sub-bass pads at midnight.
* **Weather / Rain Intensity Node:** Sweeps filter resonance or reverb wetness during thunderstorms.
* **Proximity Node:** Measures distance to the nearest listening player, swelling audio volume or distortion as players approach the DJ booth.
* **Biome / Altitude Node:** Injects subtle detune or pitch shifts when placed in the Nether, End, or below bedrock.



---

### 5. In-Game Pattern Archiving & Trading (The Physical Loop)

You have clipboard Base64 JSON and world transactional files, but no physical survival gameplay loop:

* **The Vinyl / Punch Card Item:**
* A craftable item (e.g., *Blank Audio Disc* or *Punched Paper Tape*).
* Right-clicking the Sequencer workstation burns the current node graph onto the disc.
* Inserting that disc into a Satellite Speaker or a Sequencer in another base instantly loads the patch.
* Allows players to build record shops, trade stems, and DJ in multiplayer survival worlds.



---

### Inception Gap Analysis

| Feature Area | Current Architecture | What Strudel Does | Missing Inception Component |
| --- | --- | --- | --- |
| **Rhythm** | Euclid, Fast/Slow, Alternate, Probability, Polymeter | Euclidean, alternation, degradation, polymeter | Implemented; dedicated input reordering UI deferred |
| **Pitch** | Raw Frequency (Hz) / Pitch Ratio | Notes (`c3`, `eb4`), Scales, Chords, Microtuning | `ScaleQuantizer` & `ChordGen` nodes |
| **Sampling** | Trigger full one-shot from start | Chopping (`chop`), Slicing (`slice`), Looping (`loopAt`) | `SampleSlicer` node & start-offset DSP parameter |
| **Environment** | Static in-game blocks | N/A (Browser-based) | `SunClock`, `WeatherMod`, and `Proximity` sensory nodes |
| **Progression** | Operator commands (`/groove`) | Text files / URL sharing | Physical craftable Discs / Cartridges for survival trading |
