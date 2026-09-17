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
| **1** | **Headphone Item** | **95** | Medium | Gameplay & Audio | Item, Trinkets equip slot, priority routing over speaker/positional audio, per-editor live draft monitoring while linked and in range, a true `AL_SOURCE_RELATIVE` head-locked sink, and underwater muffling have all landed. | Implemented |
| **2** | **Server Resourcepack Distribution** | **80** | High | Multiplayer UX | Full catalog browsing on join plus a permission-gated `/groove-samples install <id>` with disk persistence and eviction have landed. Remaining: no automatic bulk push of everything to every player (a deliberate scope choice), and no vanilla-resource-pack-style compressed bundle transfer. | Partial |
| **3** | **Sample Drawer Hierarchy & Favorites** | **75** | Low–Medium | Editor UX | Speeds up browsing large custom sample packs beyond a flat list. | Implemented |
| **4** | **Direct Entry & Keyboard Controls for Knobs** | **70** | Low | Accessibility & UX | Precision value entry and arrow-key stepping without pixel-hunting rotary drags. | Implemented |
| **5** | **Workstation "In-Use" / Merge UI** | **60** | Medium | Multiplayer UX | Clear player feedback when multiple users attempt simultaneous sequencer edits (exclusive edit lock vs. optimistic conflict resolution). | Implemented |
| **6** | **Master Peak Limiter Test Suite** | **50** | Low | Audio Safety | Regression coverage for finite output and tanh peak bounds under high-load DSP graphs. | Implemented |
| **7** | **Cable Pulse Animation Toggle** | **45** | Low | Accessibility | Option to reduce visual motion / flashing during fast BPM sessions. | Open |
| **8** | **Phase 2 routing extensions** | **35** | Very High | Architecture | Independent audio-render sources and arbitrary pattern triggers/polyphonic envelopes have landed (each up to eight, shared event budget). Late joins/resyncs now approximate effect history with bounded local replay and a fade-in; exact historical state remains a refinement. | Implemented (3 of 3; bounded history) |

---

## Recently Landed Features

The following items from earlier roadmaps are fully implemented and verified in the codebase:

- **Engine Upgrade Stage 4 ([ENGINE-UPGRADE-STAGES.md](ENGINE-UPGRADE-STAGES.md#stage-4-usage))**:
  - `reverb` node (Dattorro plate, −1 dB peak loudness contract, at most 2 per graph) with a compile-time loop-gain rule for feedback through reverb.
  - Sustained sample loops (`loop`, `loopStart`, `loopEnd`, `loopFadeMs`) with correlation-aware seam crossfades and editor warnings.
  - Shared replay leasing for speakers, a Groove protocol check on join, unreadable-project preservation and session `.bak` backups.
  - Measured performance: `perfBench` B1-B9, denormal snaps, the resampler level change near powers of two, and cached voice selection (B1 median 0.74 to 0.22 ms).
- **Engine Upgrade Stages 1, 2, and 3 ([ENGINE-UPGRADE-STAGES.md](ENGINE-UPGRADE-STAGES.md))**:
  - **Stage 1**: Note names (`C4`), scale-degree sequences (`scale_sequence`), transposition (`transpose`), chord voicings (`chord`), and multi-mode biquad filtering (HP, BP, notch).
  - **Stage 2**: Shared `VoiceDsp` foundation, sample region bounds and slicing (`sample_slice`), reversed sample playback, offline pattern sample compilation, and 32 MiB bank memory budget.
  - **Stage 3**: Continuous groove swing (`swing`), pattern cycle mirroring (`reverse`), bandlimited pulse wave oscillator with PWM (`Tone.Wave.PULSE`), and tempo-synced delay with musical divisions and 192,000-frame worst-case memory budgeting.
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

- **Stage 4 follow-ups.**
  - Carry effect state across unchanged republishes, so knob drags and relinks do not rebuild tails from 1 s of history.
  - Compute looped-voice age from onset nanoseconds, so a sustain crossing a tempo change does not jump position.
  - Pool runtime delay and reverb buffers if GC pauses show up in B8.
  - Extend the loop-gain check to feedback loops without a reverb, which can still saturate at ±8 and make late joiners diverge.
  - Decide the B8 gate: 8 renderers of the B8 graph miss the 5.33 ms p99 gate even without replay (26.4 ms), so it needs a lighter graph or a different criterion.

- **Exact effect-history reconstruction.** The three Phase 2 extensions have landed, including up to one second of local delay/filter replay for late joins and resyncs; see [signals](PHASE-2-SIGNALS.md). Recovering older feedback or state across graph/tempo revisions would require a richer history or authoritative snapshots. The current bounded approximation does not provide exact historical equivalence.
- **No tempo automation, seeking, or non-integer-cycle start.** Dynamic tempo changes at runtime exist via `/groove tempo`, but score/pattern-side tempo curves and timeline seeking do not.

## Physical Presence in the World (Remaining)

- **Speaker tower advanced routing & occlusion.** The core speaker tower block and positional audio system is functional. Future work includes in-world sound occlusion through intervening solid blocks, per-player bus configuration, and server-side distance validation.
- **Graceful stream fade-out and dimension-change handling have landed.** Chunk unload, range changes, and invalidated speaker links fade out over 300ms of newly generated PCM (`GrooveAudioStream.fadeOut`), after any already-queued audio. Retired streams remain tracked until the channel drains, with a bounded cleanup deadline for stalled channels. Disconnect, dimension change, pause, audition, and headphone switching stop both active and retiring streams immediately.
- **Headphone item has a true head-locked sink and underwater muffling.** A wearable headphone item with a Trinkets equip slot has landed; wearing it silences nearby speaker positional audio and, once linked to an editor block, plays that editor's live draft as a private feed (`MusicClient`). The feed was already a true `AL_SOURCE_RELATIVE` head-locked sink — `GrooveSound`'s headphone constructor sets `relative = true` at position (0,0,0), which `SoundEngine`/`Channel` (decompiled and traced to confirm) turns into a literal `AL10.alSourcei(source, AL_SOURCE_RELATIVE, 1)`, the standard OpenAL technique for a source that tracks listener position and orientation every frame with zero directional cue; this doc previously undersold that as merely "non-positional." Underwater muffling was genuinely missing and now works: `GrooveAudioStream` runs the feed through a 700 Hz low-pass (`Biquad`, reused from the DSP engine) whenever the player's eyes are in water (`Entity.isEyeInFluid`, polled each client tick), clearing instantly on surfacing.
- **Live draft-graph monitoring through headphones has landed.** Holding the headphones and right-clicking an editor block records a persistent, exclusive link on that item (survives trading/dropping/relogin). While worn and within 16 blocks of the bound editor, headphones now poll and play that editor's live draft privately (`HeadphonePackets.Preview`/`Draft`), using the shared server transport phase and continuous lookahead. Worn links auto-unlink server-side outside that range or across dimensions; unlinked or unavailable previews are silent. See [EDITOR-BLOCK-DESIGN.md](EDITOR-BLOCK-DESIGN.md).

## Editor UI (Remaining)

- **Rotary knob keyboard & direct entry have landed.** Sprite-backed rotary controls support vertical dragging, fine adjustment via Ctrl, and undo/redo. Clicking a knob (or Up/Down once a node is selected) gives it keyboard focus, shown as an outline; Left/Right steps its value by a percentage of the param's actual range (Ctrl for a finer step), and Enter opens a numeric entry field that types an exact value straight through the same clamp a drag would apply. Integer controls move by at least one unit, including fine stepping. Direct entry rejects non-finite values and preserves full numeric precision. Each value change is one undo entry; unchanged or rejected entries do not consume undo history.
- **Sample drawer hierarchy and favorites have landed.** Asset ids (`namespace:path/to/file`) drive a collapsible folder tree (`SampleTree`) instead of a flat list, with click/Enter/Space to expand a folder and click/Enter/Space on a sample to audition or drag it. Right-click toggles a favorite, pinned into a synthetic "Favorites" group at the top (the entry still also appears in its normal folder position) and persisted client-side across sessions (`SampleFavorites`, `groove/favorite-samples.txt`). Typing a search query bypasses collapsed folders entirely so a match is never hidden. Remaining: an animated/resizable drawer panel and vanilla sound-event indexing.
- **Workstation in-use/conflict feedback has landed; still no concurrent-draft merging.** Block editors keep their last-write-wins shared draft, with stale-revision rejection at Commit — that architecture is unchanged. What was missing was visibility: the editor screen's bottom status line now shows every other player who currently has the same editor open ("Also editing: ..."), tracked server-side as transient (unpersisted) presence keyed by dimension, block position and session (`EditorServer`, `EditorPackets.CLOSE`), refreshed by the existing ~1s idle poll and cleared on screen close or disconnect. If a player has unsaved local edits and someone else's edit lands on the shared draft in the meantime, autosave pauses and the status line offers Reload or an explicit Commit to overwrite, distinguishing an actual cross-player conflict from the player's own edit still in flight (comparing the response's revision against the revision the player's local draft was last synced from). The legacy global session still accepts one pending edit at a time. Automatic graph merging is not planned by the block design.
- **Sample CRC mismatch warning on node cards.** Missing-asset detection functions correctly via `SampleCatalog.Status` and inspector status text, but there is no visual hazard-stripe overlay rendered directly across the node card body for missing samples (only the theme's fallback texture is substituted).

## Server Sync & Multiplayer (Remaining)

- **No automatic resync tuning beyond fixed thresholds.** Transport clock synchronization operates via documented slew and step thresholds ([BACKEND-USAGE.md](BACKEND-USAGE.md)), but adaptive PID/slew tuning under asymmetric or high-jitter network conditions has not been implemented.
- **No server-side chunk-unload or distance culling for audio state.** The session is server-wide and persistent, decoupled from individual chunk lifecycles or entity unload events.
- **GC pressure reduction.** The DSP mixer avoids per-render allocations, but pattern queries and the Minecraft audio stream adapter still allocate temporary buffers per tick/block.
- **Editor block design is functionally complete.** Placed editors own independent persistent draft/committed sessions, ownership, an owner-managed Access UI, a Speakers panel to link/unlink nearby speakers, and headphone binding — both connection types now carry real audio (headphones play the linked draft privately, linked speakers play the committed patch positionally), and the legacy global monitor stream is retired. Stale links on stored/unworn headphones are now also cleaned up (`HeadphonesItem.inventoryTick`, throttled, alongside the existing worn self-heal in `HeadphoneServer`), scoped to a player's own inventory — a pair left in a chest, dropped on the ground, or in an item frame is not swept. See [EDITOR-BLOCK-DESIGN.md](EDITOR-BLOCK-DESIGN.md).

## Distribution & Custom Samples (Remaining)

- **Catalog browsing and explicit install have landed; bulk push has not.** On join, the server advertises its full custom catalog listing (id+hash only) so `/groove-samples list` can browse everything, and `/groove-samples install <id>` (operator level 2) explicitly fetches a specific asset into a separate persistent download cache, with eviction limited to managed downloads ([SAMPLES.md](SAMPLES.md)). Assets already referenced by the shared graph still auto-fetch as before. Deliberately not built: an automatic bulk push of every custom asset's bytes to every joining player, and a vanilla-resource-pack-style compressed bundle transfer.

## Accessibility & Safety (Remaining)

- **Master limiter test suite has landed; still no lookahead brickwall limiter.** Output is bounded by non-bypassable `tanh` soft saturation ([SPECS.md §5](SPECS.md)). `LimiterTests` (core-engine) now sweeps `Biquad` at representative Q/cutoff values including the legal endpoints under a full-scale step train, and renders a legally-compiled high-load graph (eight independent max-resonance sources, each four simultaneous full-gain tones, summed into a unity-gain delay feedback loop) for two seconds, asserting every sample stays finite and within tanh's own bound. The suite also injects non-finite filter inputs to verify recovery, and checks known stereo sums against the tanh curve through pattern/signal playback and publication crossfades. These are regression fixtures, not an exhaustive proof for every graph; soft saturation intentionally adds distortion. There is still no separate lookahead brickwall limiter stage beyond `tanh` itself.
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

Stage 1 now provides note-name authoring, `scale_sequence`, `transpose`, and
`chord` on the event side. See [engine upgrade stages](ENGINE-UPGRADE-STAGES.md)
for parameters, bounds, and an example patch. Continuous signal-driven pitch
quantization remains future work:

* **Scale Quantizer Node:**
* Takes continuous pitch ratios or integer scale degrees ($0, 1, 2, 3\dots$) and forces them into musical scales (Minor Pentatonic, Dorian, Phrygian, Major, Blues).
* Inputs: Root Note (e.g., $C3$), Scale Selector.
* Formula:

$$\text{frequency} = 440 \times 2^{\frac{\text{scaleInterval} - 69}{12}}$$




* **Chord Expander Node — implemented:**
* `chord` expands tone events into fixed triads, sevenths, sus4, diminished,
  or dominant ninth voicings, with inversions and a shared event budget.



---

### 3. Granular Sample Chopping & Slicing (`chop` / `slice`)

**Stage 2 update:** source-frame regions, the `sample_slice` node, reversed
sample playback, and offline pattern sample rendering are implemented.
See [engine upgrade stages](ENGINE-UPGRADE-STAGES.md#stage-2-usage). Sustained sample loops landed in
[Stage 4](ENGINE-UPGRADE-STAGES.md#sustained-sample-loops). Live slice-index modulation and granular
time stretching remain future work.

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
| **Sampling** | One-shots, source regions, equal slicing, reverse, sustained loops, offline pattern rendering | Live slice modulation, time stretching, sustained looping | Live slice modulation and time stretching |
| **Environment** | Static in-game blocks | N/A (Browser-based) | `SunClock`, `WeatherMod`, and `Proximity` sensory nodes |
| **Progression** | Operator commands (`/groove`) | Text files / URL sharing | Physical craftable Discs / Cartridges for survival trading |
