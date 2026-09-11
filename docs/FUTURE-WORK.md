# Future work / not yet implemented

Single tracker for what the mod does not do yet, cross-checked against the active
codebase. The companion docs ([BACKEND-PLAN.md](BACKEND-PLAN.md),
[EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md),
[SEQUENCER-UI-ARCHITECTURE.md](SEQUENCER-UI-ARCHITECTURE.md), [SAMPLES.md](SAMPLES.md))
describe what currently exists and how to use it; this document tracks remaining features,
known limitations, and deferred items.

## Priority Ranking (by Value Points)

Ranked by impact on gameplay, creative expressiveness, and multiplayer usability:

| Rank | Item | Value Points | Effort / Risk | Impact Area | Key Bottleneck Solved |
|---|---|---|---|---|---|
| **1** | **Headphone Item** | **95** | Medium | Gameplay & Audio | Personal client-side playback (`AL_SOURCE_RELATIVE`) without disturbing multiplayer neighbors. |
| **2** | **Filter on `sample` Nodes** | **85** | Medium | DSP & Sound Design | Critical for drum sculpting; tone nodes have low-pass, but samples cannot be muffled or brightened. |
| **3** | **Server Resourcepack Distribution** | **80** | High | Multiplayer UX | Eliminates manual out-of-band sample installation for connecting players. |
| **4** | **Sample Drawer Hierarchy & Favorites** | **75** | Low–Medium | Editor UX | Speeds up browsing large custom sample packs beyond a flat list. |
| **5** | **Direct Entry & Keyboard Controls for Knobs** | **70** | Low | Accessibility & UX | Precision value entry without pixel-hunting rotary drags. |
| **6** | **Voice Stealing Micro-Crossfade** | **65** | Low–Medium | Audio Quality | Eliminates harsh clicks during dense polyphonic chords/patterns. |
| **7** | **Workstation "In-Use" / Merge UI** | **60** | Medium | Multiplayer UX | Clear player feedback when multiple users attempt simultaneous sequencer edits. |
| **8** | **Resonance / Q Parameter** | **55** | Low | DSP & Sound Design | Exposes acid/squelch resonance beyond fixed Butterworth $Q \approx 0.707$. |
| **9** | **Master Peak Limiter Test Suite** | **50** | Low | Audio Safety | Automated verification that adversarial DSP graphs cannot distort or clip past safety ceilings. |
| **10** | **Cable Pulse Animation Toggle** | **45** | Low | Accessibility | Option to reduce visual motion / flashing during fast BPM sessions. |
| **11** | **Anti-Aliasing Sinc Sample Pitching** | **40** | High | Audio Quality | High-end resampling replacing linear interpolation for extreme pitch shifts. |
| **12** | **Rich Pattern Types & Automation** | **35** | Very High | Architecture | Major engine rewrite for parameter curves, fractional speeds, and feedback loops. |

## Complexity Ranking

The same future work, reordered by how hard each item actually is to build rather than
how much it's worth (most complex first). Includes a few items that only appear in the
body sections below, not the value-points table above.

| Tier | Item | Why |
|---|---|---|
| **Very High** | Rich Pattern Types & Automation | Major engine rewrite — new node types, parameter-over-time automation, fractional speed, feedback DSP, scheduler support for all of it. Everything else here is additive; this one touches the core graph model. |
| **High** | Anti-Aliasing Sinc Sample Pitching | Swapping linear interpolation for a real bandlimited windowed-sinc resampler is DSP work with actual correctness/performance tradeoffs, not a parameter tweak. |
| **High** | Server Resourcepack Distribution | Dynamic pack generation/pushing to connecting clients is real multiplayer infrastructure (versioning, transfer, client-side apply) layered on top of the existing manual-file-placement model. |
| **High** | Speaker tower audio routing/occlusion/distance validation | The base positional-audio system is already built (see "Physical presence in the world" below), but what's left — in-world sound occlusion through blocks, per-player bus config, and server-validated distance checks — is a second real systems-design pass, not cleanup. |
| **Medium-High** | Adaptive resync tuning (PID/slew under jitter) | Currently fixed thresholds; doing this adaptively under real asymmetric network conditions is control-theory tuning plus a much harder test matrix (simulated jitter/loss), not a bigger constant. |
| **Medium** | Filter on `sample` Nodes | Needs per-onset filter instances instead of the single persistent per-event filter tone nodes use, because samples can have overlapping onsets — a real structural change to `LiveRenderer.sample()`, even though the filter math itself already exists. |
| **Medium** | Headphone Item | Bounded but real: Curios/Trinkets integration, `AL_SOURCE_RELATIVE` head-locked routing, underwater muffling. Highest *value* below, not highest difficulty. |
| **Medium** | Workstation "In-Use" / Merge UI | Needs both a live indicator and actual merge-conflict semantics beyond today's flat reject-second-edit rule. |
| **Medium** | GC pressure in pattern queries / audio adapter | The mixer itself is already allocation-free; getting the same guarantee out of pattern queries and the Minecraft audio stream adapter means profiling and restructuring real hot paths. |
| **Medium** | Tempo automation, seeking, non-integer-cycle start | Touches timeline/scheduler math directly, not just a new parameter. |
| **Low-Medium** | Voice Stealing Micro-Crossfade | Scoped: crossfade logic at voice-steal boundaries, no architecture change. |
| **Low-Medium** | Sample Drawer Hierarchy & Favorites | UI/data-organization work over an already-working flat list. |
| **Low-Medium** | Sample CRC hazard-stripe overlay on node cards | Rendering work wired to a status system (`SampleCatalog.Status`) that already exists and already surfaces the same info elsewhere. |
| **Low-Medium** | Server-side chunk-unload / distance culling for the session | Hooks into existing lifecycle events; the speaker-tower version of this exact problem is already solved as prior art. |
| **Low** | Direct Entry & Keyboard Controls for Knobs | Input-handling addition onto an already-built widget. |
| **Low** | Resonance/Q Parameter | One new node parameter; the filter math (`Biquad`) already supports it, just hardcoded. |
| **Low** | Master Peak Limiter Test Suite | The limiting behavior (tanh saturation) already exists and is non-bypassable — this is writing tests, not building a limiter. |
| **Low** | Cable Pulse Animation Toggle | Single settings flag gating an existing animation. |

Biggest gap between the two rankings: the headphone item is #1 by value but only
mid-pack by difficulty, making it the highest-leverage thing to build next. Rich
pattern types is the opposite: lowest value-ranked *and* hardest, correctly last in
line on both axes.

## DSP / engine

- **No filtering on `generator/sample` nodes.** Tone nodes have low-pass filtering
  (`Biquad`, Butterworth with `cutoffHz`), but one-shot samples remain unfiltered.
  Samples can have multiple simultaneous overlapping onsets per event slot
  (`LiveRenderer.sample()`'s `while (onset >= anchorCycle)` loop), which requires dynamic
  per-onset filter instances rather than the single persistent per-event-index filter
  used by tone events.
- **No resonance / Q parameter.** Filter Q is hardcoded to $Q \approx 0.707$
  (Butterworth, no resonance peak) across both `Renderer` and `LiveRenderer`; exposing
  resonance as an adjustable node parameter remains future work.
- **No richer pattern types beyond the v1/v2 node set** (`tone`, `euclid`, `fast`,
  `stack`, `output`, `generator/sample`). Fractional speed transforms, parameter automation
  over time, multiple mix buses, and feedback DSP are all unimplemented and will need new
  graph node types plus scheduler support.
- **No tempo automation, seeking, or non-integer-cycle start.** Dynamic tempo changes at
  runtime exist via `/groove tempo`, but score/pattern-side tempo curves and timeline seeking do not.
- **Voice stealing has no crossfade.** Voices are stolen cleanly by index, but rapid voice
  stealing under dense polyphony can produce subtle audible clicks without a micro-crossfade.
- **Sample pitching is linear-interpolation only.** Pitch adjustments use basic linear
  interpolation rather than a bandlimited, anti-aliased sinc/windowed resampler.

## Physical presence in the world

- **Speaker towers are implemented with positional audio & distance culling.**
  A placed tower has a `SpeakerBlockEntity` per segment; the base segment owns a client-side
  mono `GrooveAudioStream` source with linear falloff and height-scaled positioning/volume.
  The client budgets at most eight nearest towers within a 64-block Euclidean audible range
  ($4096\text{ blocks}^2$), throttles world scans, and falls back to the non-positional
  monitor when no towers are nearby. Player-facing audio routing/bus configuration, in-world
  sound occlusion, explicit UI source budgeting, and multiplayer distance validation remain future work.
- **No headphone item.** No Curios/Trinkets integration, no `AL_SOURCE_RELATIVE` head-locked
  audio sink, and no underwater muffling filter. Headphones remain later work in
  [BACKEND-PLAN.md](BACKEND-PLAN.md) Milestone 3/4 and [maybe.md](maybe.md) §4.

## Editor UI

- **Rotary knob keyboard & direct entry.** Sprite-backed rotary controls support vertical
  dragging, fine adjustment via Ctrl, and undo/redo. Keyboard focus navigation, arrow-key
  stepping, and direct numeric text entry remain future work.
- **Sample drawer polish.** The sample drawer is currently a flat scrollable/filterable list.
  Favorites, an animated/resizable drawer panel, vanilla sound-event indexing, and hierarchical
  pack-tree navigation are deferred (see [EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md)).
- **No concurrent-draft merging.** The server maintains one shared session and accepts one
  pending edit at a time; concurrent edits from another player are rejected rather than
  merged. An explicit "workstation in use by player" indicator or optimistic merge UI is not built yet.
- **Sample CRC mismatch warning on node cards.** Missing-asset detection functions correctly
  via `SampleCatalog.Status` and the inspector status text, but there is no visual hazard-stripe
  overlay rendered directly across the node card body for missing samples (only the theme's
  fallback texture is substituted).

## Server sync / multiplayer

- **No automatic resync tuning beyond fixed thresholds.** Transport clock synchronization
  operates via documented slew and step thresholds ([BACKEND-USAGE.md](BACKEND-USAGE.md)),
  but adaptive PID/slew tuning under asymmetric or high-jitter network conditions has not been implemented.
- **No server-side chunk-unload or distance culling for audio state.** The session is server-wide
  and persistent, decoupled from individual chunk lifecycles or entity unload events.
- **GC pressure reduction.** The DSP mixer avoids per-render allocations, but pattern queries
  and the Minecraft audio stream adapter still allocate temporary buffers per tick/block.

## Distribution

- **No automated server resourcepack distribution for custom samples.** Both server and
  clients currently require matching audio files installed locally at identical relative
  paths ([SAMPLES.md](SAMPLES.md)); dynamic server-to-client resourcepack generation or pushing
  is not yet built.

## Accessibility

- **No formal master limiter guarantee.** Output is bounded by non-bypassable `tanh` soft
  saturation ([SPECS.md §5](SPECS.md)), but there is no lookahead brickwall limiter or automated test
  asserting peak ceilings under extreme adversarial sum inputs.
- **No toggle for cable pulses.** Cable beat-pulse animations run at tempo phase across animated
  themes; an accessibility setting to disable high-frequency cable pulses is not yet implemented.

## Superseded documentation claims

- [SEQUENCER-UI-ARCHITECTURE.md](SEQUENCER-UI-ARCHITECTURE.md) previously sketched hypothetical
  `PatchSubmission` and `SampleRegistry` interfaces; these were superseded by the real network
  packets `MusicPackets.Submit` / `MusicPackets.SubmitResult` and `SampleCatalog`.
- Themed button and knob draw calls now use real sprite resources (`ThemedButton` wires
  Apply/Play-Stop/Reload and `RotaryKnob` renders inspector dials).
