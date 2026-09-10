# Future work / not yet implemented

Single tracker for what the mod does not do yet, cross-checked against the code
(not just the design docs) on 2026-09-10. The per-area docs
([BACKEND-PLAN.md](BACKEND-PLAN.md), [EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md),
[SEQUENCER-UI-ARCHITECTURE.md](SEQUENCER-UI-ARCHITECTURE.md), [SAMPLES.md](SAMPLES.md))
describe what exists and how to use it; this doc is where "not built yet" lives so
it doesn't rot into stale claims scattered across five files.

## DSP / engine

- **Tone-node low-pass filtering is implemented** (`Biquad`, RBJ Butterworth
  low-pass, `cutoffHz` param on `tone` nodes, wired into both the offline
  `Renderer` and the live `LiveRenderer` path). **`generator/sample` nodes are
  not filtered** — one-shot samples can have multiple simultaneous overlapping
  onsets per event slot (`LiveRenderer.sample()`'s `while (onset >=
  anchorCycle)` loop), which doesn't fit a single persistent per-event-index
  filter the way tone events do; this needs per-onset-instance filter state,
  deferred as follow-up. **Resonance/Q is also deferred** — fixed at Q≈0.707
  (no resonance peak) for now; exposing it as a second node parameter is a
  natural small follow-up.
- **No richer pattern types beyond the v1/v2 node set** (`tone`, `euclid`, `fast`,
  `stack`, `output`, `generator/sample`). Fractional speed transforms, automation
  (parameter-over-time), multiple mix buses, and feedback DSP are all unimplemented
  and would need new graph node types plus scheduler work.
- **No tempo automation, seeking, or non-integer-cycle start.** Tempo changes at
  runtime exist (`/groove tempo`), but score/pattern-side tempo curves do not.
- **Voice stealing has no crossfade** — can still click under heavy polyphony.
- **Sample pitching is linear-interpolation only** — no higher-quality
  anti-aliasing resampler.

## Physical presence in the world

- **No speaker blocks.** Playback is a single server-wide virtual session with no
  in-world sound source or positional falloff beyond what the Jukebox/Note Block
  volume category gives for free.
- **No headphone item.** No Curios/Trinkets integration, no `AL_SOURCE_RELATIVE`
  redirect to the player's head, no underwater muffling filter.
- Both are called out as later work in [BACKEND-PLAN.md](BACKEND-PLAN.md)
  Milestone 3/4 and assumed by [maybe.md](maybe.md) §2 and §4 — neither has a class
  in the codebase yet (no `BlockEntity`, no block/item registration for either).

## Editor UI

- **Knob accessibility polish.** Sprite-backed rotary controls now support vertical
  dragging, Ctrl fine adjustment, and undo/redo. Keyboard focus, arrow-key adjustment,
  and direct numeric entry remain future work.
- **Sample drawer is functional but not polished**: no favorites, no
  animated/resizable drawer, no vanilla-sound indexing, no pack-tree navigation.
  Today it is a flat scrollable/filterable list. (See
  [EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md).)
- **No concurrent-draft merging.** One shared session, one pending edit at a time;
  a second player's edit is rejected outright rather than merged. [maybe.md](maybe.md)
  §5 lays out the exclusive-lock-vs-optimistic-merge tradeoff — the exclusive-lock
  side is effectively what exists today (via the single-pending-edit rule), but
  there's no explicit "workstation in use by X" UI for it.
- **Missing-asset detection is real** (`SampleCatalog.Status`, inspector status
  line), but there's no visual "hazard stripe" on the node card itself the way
  [SPECS.md §6](SPECS.md) describes for CRC mismatches — only the theme's hazard
  sprite substitution for missing *textures*, not a missing-*sample* node marker.

## Server sync / multiplayer

- **No automatic resync tuning beyond the documented slew/resync thresholds** —
  works as specified in [BACKEND-USAGE.md](BACKEND-USAGE.md), but has not been
  validated against real asymmetric network conditions, only local testing per
  that doc's own verification section.
- **No server-side chunk-unload / distance-culling behavior for audio threads**
  ([maybe.md](maybe.md) §2) — the session is server-wide and always-on, not tied to
  any in-world entity that could unload.
- **No GC-pressure hardening pass** ([maybe.md](maybe.md) §1) — the mixer avoids
  per-render allocation, but pattern queries and the Minecraft audio adapter still
  allocate per block (documented, accepted tradeoff, not yet revisited).

## Distribution

- **No server resourcepack distribution path for custom sample packs.** Today,
  each side needs the matching file placed at the same relative path manually
  ([SAMPLES.md](SAMPLES.md)); there's no packaging/pushing mechanism
  ([maybe.md](maybe.md) §3, "Option A: Server Resource Pack" is unimplemented).

## Accessibility

- **No hard master limiter guarantee beyond the existing tanh saturation** — it's
  already non-bypassable per [SPECS.md §5](SPECS.md), so this is closer to "verify"
  than "build," but there's no automated test asserting output never clips past a
  safe ceiling under adversarial graphs.
- **No "disable high-frequency cable pulses" accessibility setting**
  ([maybe.md](maybe.md) §6) — cable pulse animation always runs at tempo phase.

## Stale doc claims fixed alongside this file

[SEQUENCER-UI-ARCHITECTURE.md](SEQUENCER-UI-ARCHITECTURE.md) previously said no C2S
packet existed and that `PatchSubmission`/`SampleRegistry` were unbuilt seams —
both are now superseded: `MusicPackets.Submit`/`SubmitResult` is the real C2S
packet (see `GrooveEditorScreen.submit`), and neither `PatchSubmission` nor
`SampleRegistry` was built as originally sketched. It also said button/knob/icon
draw calls had no call sites; `ThemedButton` now wires Apply/Play-Stop/Reload to
real sprites, and `RotaryKnob` now draws the Inspector's knob sprites too.
