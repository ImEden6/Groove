# Engine upgrade stages

## Status

| Stage | Scope | Status |
| --- | --- | --- |
| 1 | Note names, scale-degree sequences, transpose, chords, filter modes | Implemented; automated checks pass |
| 2 | Shared voice DSP, offline samples, sample regions and slicing | Implemented; automated checks pass |
| 3 | Pattern reverse, swing, pulse/PWM, tempo-synced delay | Implemented; automated checks pass |
| 4 | Reverb, sustained sample loops, replay leasing, measured performance improvements | Implemented; automated checks pass; B8 8-renderer timing gate fails |

## Stage 1 usage

The editor's Shift+A / Tab palette includes `scale_sequence`, `transpose`,
and `chord`. Each accepts one `PATTERN` input and emits a `PATTERN` output.
Use a tone as the voice template, followed by a scale sequence and then a
chord or transpose node. Connect the result to `output.in`, or to
`audio_render` for effects routing.

### Note names

Tone `frequency` accepts note names in imported JSON and the editor's direct
value entry, for example `C3`, `F#4`, or `Bb2`. Scale-sequence `root` accepts
the same names. Scientific pitch notation is used: C4 is MIDI 60 and A4 is
440 Hz. Upper/lowercase note letters, one optional `#` or `b`, and octaves
-1 through 9 are supported where the resulting MIDI number is 0..127.
Playable graph frequencies must still be 20..16000 Hz.

Names are converted on the control side: frequency becomes Hz and root becomes
an integer MIDI note. Saved graphs and network snapshots remain numeric;
the original spelling is not retained. Numeric editor entry retains its
existing clamping behavior. The dependency-free engine exposes `Pitch.hz`,
`Pitch.midi`, and `Pitch.degreeHz` for Java callers.

### Scale sequence

- `root`: MIDI 0..127, default 60 (C4).
- `scale`: 0 major, 1 natural minor, 2 Dorian, 3 Phrygian, 4 Lydian,
  5 Mixolydian, 6 minor pentatonic, 7 blues, 8 whole tone.
- `steps`: 1..8, default 4.
- `stepsPerCycle`: 1..64, default 4.
- `value0`..`value7`: integer scale degrees -64..64, default 0. Only the
  first `steps` values play. Degree 0 is the root; negative degrees descend
  through the scale. All active pitches must fit the playable frequency range.

Each step sets the input's tone pitches to an absolute scale pitch and
compresses one child cycle into a pulse slot, using the same time semantics
as `polymeter`. The sequence continues across bar boundaries; child patterns
advance a local cycle per sequence rotation. Start with a single tone for
a melody. Put `chord` after this node: an absolute scale pitch replaces
every incoming tone pitch and does not preserve an upstream chord's intervals.

### Transpose and chords

`transpose.semitones` accepts -48..48, including fractional semitones.
It preserves rhythm, gain, pan, waveform, and voice-filter settings.

`chord.chord` selects 0 major, 1 minor, 2 dominant seventh, 3 major seventh,
4 minor seventh, 5 sus4, 6 diminished, or 7 dominant ninth. `inversion`
defaults to 0 and ranges up to voice count minus one. Inversion raises the
lowest indexed chord tones by one octave, then sorts the voicing by pitch.
Each generated voice receives input gain divided by chord voice count.
These are fixed semitone chord qualities, not automatic diatonic harmonization.

All three pitch nodes require tone-only inputs. Samples are rejected during
graph compilation, even when hidden in a later or probabilistic branch.
Pitch bounds likewise cover every branch, not just the first-cycle preview.
Chords multiply the event cost by voice count; scale sequences multiply it
by pulse rate. The shared 128-event budget and existing voice-stealing behavior
remain in force. No new voice allocator or continuous pitch-modulation input
is introduced in this stage.

### Filters

The `filter` audio node now has `mode`: 0 low-pass (default), 1 high-pass,
2 band-pass, or 3 notch. `cutoffHz` and `resonanceQ` retain their existing
ranges. Band-pass uses unity peak gain; increasing Q narrows its bandwidth.
Coefficients follow the [RBJ Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/).
Only low-pass retains the legacy bypass at the Nyquist ceiling. Other modes
clamp to just below Nyquist. The per-voice filters on tones and samples remain
low-pass; route through an audio `filter` node for the additional modes.

## Example patch

This plays a four-step C-major melody, expands each note into a major triad,
and applies a high-pass filter. Import through the existing patch JSON workflow.

```json
{
  "version": 3,
  "nodes": [
    {"id":"voice","type":"tone","params":{"frequency":"C4","gain":0.25,"wave":1}},
    {"id":"melody","type":"scale_sequence","params":{"root":"C4","scale":0,"steps":4,"stepsPerCycle":4,"value0":0,"value1":2,"value2":4,"value3":6}},
    {"id":"harmony","type":"chord","params":{"chord":0,"inversion":0}},
    {"id":"render","type":"audio_render","params":{}},
    {"id":"filter","type":"filter","params":{"mode":1,"cutoffHz":120}},
    {"id":"out","type":"output","params":{}}
  ],
  "edges": [
    {"fromNode":"voice","fromPort":"out","toNode":"melody","toPort":"in"},
    {"fromNode":"melody","fromPort":"out","toNode":"harmony","toPort":"in"},
    {"fromNode":"harmony","fromPort":"out","toNode":"render","toPort":"in"},
    {"fromNode":"render","fromPort":"out","toNode":"filter","toPort":"in"},
    {"fromNode":"filter","fromPort":"out","toNode":"out","toPort":"audio"}
  ]
}
```

## Verification and limits

`PitchTests`, `FilterModeTests`, and `PitchEntryTests` cover note parsing,
enharmonic pitches, negative degrees, inversions, gain normalization,
partition-independent queries, future-branch bounds, event costs, filter
response/stability, stereo isolation, callback allocation, numeric persistence,
editor entry/undo, and draft round trips. The backend suite also exercises
the new nodes through network snapshots. Run `gradlew.bat build --offline`
with populated dependencies, or omit `--offline` on a fresh setup.

Existing numeric patches retain their behavior and require no migration.
All peers must use an updated build to understand new node names and modes.
Automated signal tests do not replace in-game listening or a two-machine
multiplayer check. This stage makes no new polyphony or real-time performance
claim; the existing worst-case resampling benchmark can still exceed its
audio time budget on this machine.

## Stage 2 usage

### Sample regions and slicing

`generator/sample` now accepts `startFrame`, `endFrame`, and `reverse`.
The interval is expressed in **source frames**, not output frames or individual
channel values. Start is inclusive; end is exclusive. Both default to zero,
with end=0 meaning the end of the asset. `reverse` is 0 for forward (default),
or 1 for reverse. Existing patches therefore retain whole-asset forward playback.

The editor palette includes `sample_slice`, with one sample-only `PATTERN`
input. Its controls are `slices` (1..64, default 8), `index` (0..slices-1,
default 0), and `reverse` (0/1, default 0). It selects a slice within each
input voice's source interval. Integer boundaries distribute remainder frames
without gaps or overlaps. Every slice must contain at least one source frame.
Actual asset bounds are checked when PCM is resolved on the compiler worker.

Slice selection and direction replace an earlier slice selection/direction;
stacking slice nodes does not subdivide recursively. Explicit start/end frame
bounds are retained. Slicing changes the audio content and its playback length,
not event arcs or trigger timing. Pitch still changes both duration and pitch;
there is no time stretching. A 1 ms attack and 5 ms release soften boundaries.

To rearrange a break, connect one sample to several `sample_slice` nodes,
choose their indices, and connect those to `polymeter.in` in playback order.
For example, four slices at four steps per cycle can play in order 0, 2, 1, 3,
with the last slice reversed. The existing event budget and 32-voice pool
still apply, including overlapping one-shot tails.

Each selected region is copied (and optionally reversed) on the control
thread, then gets its own prefiltered resampling levels. This prevents
neighboring slices from bleeding through interpolation or prefiltering.
Identical resolved regions share PCM even when gain, pan, pitch, or filter
settings differ. All sample variants from later/probabilistic graph branches
are discovered during compilation and prepared before publication.

Current and pending programs prepared by the app share one **32 MiB PCM
payload budget**, covering original assets and isolated regions with all
prefiltered levels. Up to 128 distinct sample voice settings are supported
per prepared bank. Memory is checked before copying a region; preparation
fails rather than allocating beyond the budget. Stereo payload is counted
for both channels. Metadata and array headers are additional but bounded by
the asset/variant caps. During a playback replacement, the outgoing timeline
can remain pinned through its crossfade, so two such banks can coexist. The
decoded asset cache, other playback sessions, and audio buffers have their
own existing limits; 32 MiB is not a process-wide heap limit.

### Offline samples and shared voice DSP

Live and offline renderers now use `VoiceDsp` for oscillator evaluation,
voice filtering, envelopes, and panning. Their schedulers remain separate:
offline timing uses integer output frames; live timing supports clock slew
and seeks. Both retain the existing limiter and bounded stealing fades.

Offline scores accept an immutable sample-bank snapshot:

```java
AssetRef ref = FactorySamples.ref("factory:basic/snare.wav");
SampleData pcm = WavDecoder.decode(FactorySamples.bytes(ref.assetId()));
Pattern sample = Pattern.sample(new SampleVoice(ref, 1, .6, 0))
        .slice(4, 0, true).fast(4);
Score score = Score.compile(sample, new Transport(48000, 120, 4), 4,
        Map.of(ref, pcm));
Renderer renderer = new Renderer(score, 32);
```

`Score.compile` resolves samples, validates regions, and rejects missing
assets before playback. Sample note end frames come from the region's pitched
duration, independently of musical event duration. `score.frames()` remains
the requested export length; render additional frames explicitly if an export
should include tails beyond it. Unsupported sample conversions above 16 source
frames per output frame fail during score compilation. Offline **pattern**
samples are supported; full offline signal-graph/effects export is not added.

For direct sample playback, call `SampleVoice.prepare(pcm)` once on the control
thread and then `SamplePlayback.value(...)`. The legacy `SampleVoice.value`
convenience remains for whole forward assets and rejects region voices to
prevent accidental unprepared playback. Engine renderers use a deduplicated,
bounded `PreparedSamples` bank instead of preparing each voice separately.

Run `gradlew.bat -p core-engine renderSampleDemo --offline` to write
`core-engine/build/sample-demo.wav`: an eight-second mix of sliced factory
drums, reversed snare, and a synthesized bass. No external assets are needed.

Stage 2 tests cover exact boundaries and remainder frames, stereo reversal,
absence of neighboring-slice bleed at all resampling levels, region memory
accounting/deduplication, future-branch validation, mixed live/offline playback,
block-size independence, natural and stolen voice tails, late joins and seeks,
independent audio sources, zero callback allocations, editor controls/undo,
and JSON/network round trips. In-game listening and multiplayer checks remain
manual validation.

## Stage 3 usage

### Pattern reverse

The editor's Shift+A / Tab palette includes `reverse`, accepting one `PATTERN`
input and emitting one `PATTERN` output.

`reverse` mirrors event onset and duration across cycle boundaries: an event
spanning musical cycle interval $[t_{\text{start}}, t_{\text{end}}]$ transforms to
$[1 - t_{\text{end}}, 1 - t_{\text{start}}]$. Child events retain their internal
parameters (gain, pitch, sample slicing, voice filtering).

`reverse` works with any pattern input: synthesized tones, sample slices,
polymetric grooves, alternations, or nested transformations. It preserves event
count and cost budget exactly. Stacking two `reverse` nodes restores original
timing. When combined with `alternate`, `reverse` enables classic call-and-response
structures, such as four-bar sequences where the final bar reverses the rhythm:

```java
Pattern drumLoop = Pattern.polymeter(8, kick, hat, snare, hat).swing(16, 0.6);
Pattern drums = Pattern.alternate(drumLoop, drumLoop, drumLoop, drumLoop.reverse());
```

### Continuous swing

The `swing` pattern node provides continuous micro-timing groove quantization.
It accepts one `PATTERN` input and emits one `PATTERN` output.

- `subdivision`: Even integer in 2..64, default 16 (16th-note swing).
- `amount`: Float in 0..1, default 0.333.
  - `0.0`: Straight / unquantized (pass-through).
  - `0.333`: Standard triplet swing (even subdivision stretched to 66.7% of the pair).
  - `0.5`–`0.6`: Heavy funk / MPC groove.

Swing operates by piecewise-linear warping of the continuous time axis within each
pair of sub-beats ($2 / \text{subdivision}$ cycle window). The first sub-beat expands
by $1 + \text{amount} / 3$, delaying the off-beat, while the second sub-beat compresses
to preserve the overall bar length. Both onset and duration are warped, keeping
note ends aligned with grid boundaries. Because the transformation is continuous
and cycle-invariant, late joins, windowed queries, and lookahead schedulers remain
deterministic without event loss or phase drift across cycle boundaries.

### Bandlimited pulse wave oscillator and PWM

The `tone` voice generator now supports `Tone.Wave.PULSE` (value 2 in editor knob
and JSON param `wave`).

- `dutyCycle`: Float in 0.05..0.95, default 0.5 (square wave).
  - Values below 0.5 produce narrow, bright, reedy pulses.
  - Value 0.5 produces a classic hollow square wave (odd harmonics only).
  - Values above 0.5 produce wide, punchy pulses.

To avoid harsh aliasing at high pitches while keeping zero-allocation evaluation
under 10 ns per sample, `VoiceDsp` synthesizes pulse waves using differentiated
bandlimited integrated tables (integrated parabolic waveforms / BLIT):
two integrated ramps offset by the duty cycle are subtracted and differentiated:

$$x(t) = \frac{\text{parabola}(t) - \text{parabola}((t - d) \bmod 1)}{d(1 - d)}$$

High frequencies remain alias-free across the full 20..16,000 Hz range. The waveform
is supported across offline rendering (`Renderer`), live streams (`LiveRenderer`),
and editor rotary dials.

### Tempo-synced delay and memory budgeting

The `delay` audio node supports tempo synchronization to the session BPM alongside
legacy free-time delay:

- `sync`: 0 for free millisecond/frame mode (default), 1 for tempo-synced mode.
- `frames`: Free-mode buffer length in frames (64..48,000, default 64). Used when `sync = 0`.
- `freeRun`: 0 (default, not stored) or 1. At 1, any feedback loop through this delay is exempt from
  the [loop-gain limit](#loop-gain-rule), so it can self-oscillate; players who join late may then
  hear a different tail. The audio is identical either way.
- `division`: Synced-mode beat subdivision index (0..7, default 2 for 1/8 note):
  - `0`: 1/16 (0.25 beat)
  - `1`: 1/8T (1/3 beat, triplet)
  - `2`: 1/8 (0.50 beat, eighth note)
  - `3`: 1/4T (2/3 beat, quarter triplet)
  - `4`: 1/8D (0.75 beat, dotted eighth)
  - `5`: 1/4 (1.00 beat, quarter note)
  - `6`: 1/4D (1.50 beat, dotted quarter)
  - `7`: 1/2 (2.00 beats, half note)

Delay length in output frames is computed as:

$$\text{frames} = \text{round}\left(\text{sampleRate} \times \frac{60}{\text{BPM}} \times \text{beatRatio}\right)$$

#### Worst-case memory budget (192,000 frames)
To guarantee bounded memory without risking audio dropout or unbounded allocations,
the graph compiler charges every synced delay line its worst-case frames at 30 BPM
($96,000 \times \text{beatRatio}$ frames). The total delay budget across all lines
in a single `SignalGraph` is capped at **192,000 stereo frames** (~3.07 MB).
Graphs exceeding this cap are rejected at compilation with an explicit error:
`"Delay memory budget exceeded: <frames> frames (max 192000; synced delays count at 30 BPM)"`.
Free delays remain individually capped at 48,000 frames (up to four lines).
A single 1/2-note synced delay uses the whole budget on its own.

#### Runtime allocation and tempo transitions
Delay ring buffers are sized from the program's BPM when a timeline is published to
`LiveRenderer` (the control thread builds `SignalRuntime`), and synced lengths are checked
against $64 \le \text{frames} \le 192,000$. Buffer traversal in `SignalRuntime.process`
is allocation-free.

A tempo change within one session carries the delay's input history into the new
program ([carrying effect state](PHASE-2-SIGNALS.md#carrying-effect-state)): a delay line
holds exactly its last inputs, so the resized line is refilled from them and the echoes
continue at the new timing. Without a session to carry from (a fresh join, or a renderer
published without a session key), the new delay lines start empty and replay only covers
time since the new program's anchor cycle, so the synced delay stays silent until one full
new delay length of input has passed through it (24,000 frames for a 1/8 note at 60 BPM).
`SignalTests.delaySync` asserts that uncarried gap.

### Audio demonstrations

Stage 3 adds automated build targets and offline demonstration scripts:

- `gradlew.bat -p core-engine renderSampleDemo`: renders `core-engine/build/sample-demo.wav`
  (16.0s, 48 kHz stereo PCM), demonstrating sliced factory drums, reversed snare,
  continuous 16th swing, pulse bass, and a swung pulse lead echoing through a synced
  1/8-note delay loop.
- `gradlew.bat -p core-engine renderSignalDemo`: renders `core-engine/build/signal-demo.wav`
  (8.0s, 48 kHz stereo PCM), demonstrating dual live sources, LFO-modulated biquad filtering,
  and tempo-synced feedback delay via `LiveRenderer`.

Stage 4 reworks both demos around reverb:

- `renderSampleDemo` now plays four scores for 7 cycles at 120 BPM (14 s) plus a 2 s tail: the
  sliced swung kit (cycle 3 reversed) through a short room mixed low, dry pulse bass, the swung
  lead through the synced 1/8 echo and a long hall, and a sustained pad (the kick's first half,
  looped and voiced 2:3:4) into the hall. The hall sits after the echo loop.
- `renderSignalDemo` renders `SignalDemo.reverbSources()`: the bass filter and echo mix gains a
  reverb outside the delay loop, and the lead stays dry.
- `/groove sample-demo` loads `FactorySamples.reverbDemo()` and `/groove signal-demo` loads
  `reverbSources()`. In game the patterns loop, so there is no tail.
- `DemoTests` checks both in `check`: finite samples, peak at most 1.0, every sample-demo cycle and
  every signal-demo second above -40 dBFS, and the sample-demo tail above -50 dBFS at 14.0-14.2 s
  and below it by 15.8-16.0 s.

## Stage 4 usage

Stage 4 (Phase 4) adds the `reverb` node, sustained loops on `sample` nodes, shared replay leasing
for speakers, and measured performance work. Graphs stay at version 3; every new param has a
default, so Phase 3 saves load unchanged. Downgrading to Phase 3 is unsupported; see
[BACKEND-USAGE.md](BACKEND-USAGE.md#compatibility-and-version-checks).

### Reverb

`reverb` takes `in`: AUDIO and outputs `out`: AUDIO, wet signal only (like `delay`). Mix it back
with a `mix_bus`. The node is a Dattorro plate: mono in (`(L + R) / 2`), stereo out, fixed memory
and a modulated tank that avoids metallic ringing.

| Param | Range | Default | Meaning |
| --- | --- | --- | --- |
| `decaySeconds` | 0.1..20 | 1.8 | Nominal T60 |
| `dampingHz` | 200..20000 | 6000 | One-pole low-pass inside the tank |
| `bandwidthHz` | 200..20000 | 12000 | One-pole low-pass on the input |
| `preDelayMs` | 0..500 | 0 | Delay before the plate |

- **Limit.** At most 2 reverbs per graph (`"At most 2 reverbs per graph"`), separate from the
  192,000-frame delay budget. The editor enforces the same limit.
- **Loudness contract.** Peak magnitude response is −1 dB at every `decaySeconds`. Saved patches
  rely on this, so the node is never retuned in place; other algorithms would be new node types.
  The contract fixes peak response, not tail energy: broadband tail energy still falls by about
  9 dB from 1 s to 20 s, and `mix_bus` gain is capped at 1, so set the dry level lower instead.
- **Decay.** The tank applies a decay multiplier every `τ = 0.181 s`, with
  `g = 10^(−3 · τ / decaySeconds)`. Measured T60 is within ±20% from 1 s up; below 0.5 s it is
  uncalibrated.
- **Filters.** `damping: y = (1 − d)·x + d·y₋₁` with `d = exp(−2π·dampingHz / fs)`;
  `bandwidth: y = b·x + (1 − b)·y₋₁` with `b = 1 − exp(−2π·bandwidthHz / fs)`. Cutoffs clamp to
  0.45 · fs.
- **Memory.** About 36,330 frames of tank at 48 kHz (about 290 KB) plus up to 24,000 frames of
  predelay (about 190 KB) per reverb.
- **Denormals.** Feedback writes below 1e-30 snap to zero so a decaying tail never turns into slow
  subnormal numbers. The same applies to delay writes and filter state.

#### Loop-gain rule

Every audio feedback loop, with or without a reverb, must provably lose energy on each trip, or the
graph is rejected with `"Feedback loop can exceed unity gain (bound <x>); ..."`. The compiler cuts
edges into each `delay`, then bounds the amplitude arriving back at every delay from every other:
`mix_bus` multiplies the summed inputs by its gain (1 when modulated), a low- or high-pass `filter`
by its resonance peak `Q / sqrt(1 − 1/(4Q²))` for `Q > 0.707` (times 1.05), and a reverb by 0.95.
Any delay whose incoming bounds sum above **0.95** (−0.45 dB) rejects the graph, unless a delay in
that loop has `freeRun = 1`. For example, `delay → mix_bus(gain 0.9) → delay` is accepted and gain
1 is not; `delay → reverb → mix_bus(gain 1) → delay` sits exactly at the limit and is accepted,
while `mix_bus(delay, reverb(delay)) → delay` at gain 1 (bound 1.95) is not.

Below that bound, two listeners who joined at different times converge; at the limit the difference
shrinks by about 5% per trip. Patches saved before the limit keep loading and sounding the same:
delays in over-limit loops are marked free-running on load. The derivation, convergence times and
what is not guaranteed are in [feedback stability](FEEDBACK-STABILITY.md).

#### Known limitations

- Late joins, resyncs and seeks replay at most 1 s of history, and stopping the transport cuts
  tails. Republishes and commits within one session carry effect state instead
  ([carrying effect state](PHASE-2-SIGNALS.md#carrying-effect-state)), but through the edited graph,
  while a late joiner replays the new graph only. Two speakers that joined at different times differ
  until the unreplayed part of the tail has decayed: at most T60 for a reverb outside a loop, plus
  the loop convergence time for one inside a loop.
- Effect memory is per program per renderer, not per graph: up to 3.07 MB of delay plus about 1 MB
  for two reverbs per program, times current, pending and previous programs, times the client's
  renderers. A program waiting for a replay lease keeps the previous program alive too.
- Free-running loops (`freeRun = 1`, including loops migrated from older saves) can saturate at ±8
  and keep late joiners on a different tail. That is the price of opting out of the limit.

### Sustained sample loops

`sample` nodes take four more params. With `loop = 0` the other three are ignored and the node
behaves, saves and hashes exactly as in Phase 3.

| Param | Range | Default when looping | Meaning |
| --- | --- | --- | --- |
| `loop` | 0 or 1 | 0 | 1 sustains the voice for its whole event |
| `loopStart` | 0..1 | 0.25 | Fraction of the prepared region, in playback order |
| `loopEnd` | 0..1 | 0.9 | Fraction of the prepared region, in playback order |
| `loopFadeMs` | 0..500 | 20 | Crossfade length at the seam |

The compiler requires `loopStart < loopEnd`. The region is the one after `startFrame`/`endFrame`,
slicing and reverse, so loop points follow the audio as it actually plays. A looped voice lasts as
long as its event, with a 1 ms attack and a 20 ms release ending at the event's end; one-shots
keep their natural length.

**Geometry.** Loop points are resolved once per voice on the control thread, deterministically from
the asset, so every client agrees. The loop must keep the resampler's full reach `R` inside real
audio:

```
step0 = assetRate · pitchRatio / outputRate
level = halvings while step ≥ 1.96        (level ≤ 4)
band  = ceil(max(0, step0 / 2^level − 1) · 64)
R     = (ceil(24 · (1 + band/64)) + 1) · 2^level + 48 · (2^level − 1)
```

Steps 1, 2, 15.996 and 16 give `R` = 25, 98, 1,120 and 1,120. With `N` prepared frames, the fade is
first limited to half the loop, then the loop is moved inward to `[max(Ls, R + fade), min(Le,
N − R))`. If fewer than 128 frames remain, the voice plays one-shot instead.

**Seam.** Across the last `X` frames before the loop end, the voice crossfades toward the matching
audio one period back. The fade gains adapt to how alike the two windows are (their correlation ρ,
measured over at most 8,192 frame pairs and clamped to −0.5): identical material keeps its
amplitude, uncorrelated material keeps its power, and opposite-phase material dips by at most 6 dB.

**Warnings.** The editor checks each looped node against its locally loaded asset and shows
`"Loop too short for this sample; playing one-shot"` or `"Loop points moved inward to fit"`.
Listeners never see UI; their renderers count `loopFallbacks` and `loopClamps` and log them once per
program. The server validates ranges only, since it has no asset lengths.

**Limitations.**

- A looped voice's position comes from cycles at the current tempo, so a sustain that crosses a
  tempo change jumps position. Every listener hears the same jump, inside the 240-frame program
  crossfade.
- Sustained voices hold the 32 voice slots longer, so dense patterns steal earlier.
- Loop variants count toward the 128 prepared voice variants; they share PCM with the unlooped
  region.

### Replay leasing and join time

Graphs with a `filter`, `delay` or `reverb` rebuild up to 1 s of history when a listener joins,
resyncs, seeks, or receives a publish it cannot carry effect state into. Replay runs two history frames per output frame, so a full
second takes about one second of silent catch-up, then a 5 ms fade-in. A publish during a replay
keeps the previous ready audio playing; a fresh join is silent until its replay completes.

All speaker emitters on a client share one `ReplayBudget`: at most 2 programs replay at once and the
rest wait silently in order. With 8 speakers joining together, the last starts after at most 3 full
recoveries (about 3 s) and finishes about 1 s later. Once a scheduled commit has taken effect, only
the incoming program replays. The headphone preview uses its own budget. A renderer that stops
rendering for 250 ms (a closed or paused stream) loses its lease, and a fading speaker cancels its
replay. `MusicClient` logs grants and reclaims at INFO, and at WARN when a wait exceeds one full
recovery or a live renderer is evicted. Details: [PHASE-2-SIGNALS.md](PHASE-2-SIGNALS.md).

### Performance changes

- **Resampler level near powers of two.** A pitch step within 2% below a power of two uses the next
  octave level, where its kernel is narrowest. This is Stage 4's one intentional output change; see
  [ENGINE-EVOLUTION.md](ENGINE-EVOLUTION.md).
- **Voice selection.** Each program reuses its chosen voices until a note could start or stop,
  instead of rescanning every frame. Output is bit-identical to the per-frame scan.
- **Denormal snap** in delay, filter and reverb state, as above.

### Verification

`./gradlew check` covers Stage 4 with `ReverbTests` (T60, peak gain, stability, stereo width,
loop-gain rule, feedback convergence, parity, allocation), `LoopTests` (geometry, seam matrix,
late join, tempo, stealing, parity), `ReplayLeaseTests` and `SpeakerLinkTests` (leases, storms,
lifetime, fade cancel), `DenormalTests`, `ResamplerTests` (including the 15.7x-16x spectral gate),
`SelectionCacheTests` (cached versus per-frame differential), `DemoTests` and the float64 goldens.
`./gradlew :core-engine:longTest` runs the 20 s reverb decays. `perfBench` scenarios B1-B11 are
below.

## Stage 4 performance on reference machine

### Stage 4 before and after

Pinned, throttling off, 2026-09-17. "Before" is the baseline table below, recorded before replay
leasing, denormals, the resampler level change and cached voice selection; B9 and B8 use their first
runs (B8 with the original replay rate of 4). "After" is the final run. B4 was last run after the
resampler change, before cached voice selection.

| Id | Median before (ms) | Median after (ms) | p99 before (ms) | p99 after (ms) |
| --- | --- | --- | --- | --- |
| B1 | 0.7424 | 0.2207 | 1.1199 | 0.3464 |
| B2 | 0.1073 | 0.0167 | 2.0310 | 1.6346 |
| B3 | 0.1075 | 0.0164 | 4.0361 | 2.2992 |
| B4 | 0.1076 | 0.1061 | 2.8192 | 2.4730 |
| B5 | 0.5833 | 0.3221 | 0.9877 | 0.5821 |
| B6 | 0.6435 | 0.4000 | 1.1351 | 0.8317 |
| B7 | 2.9563 | 2.3483 | 4.0684 | 3.5745 |
| B7b | 0.6196 | 0.3542 | 1.1930 | 0.6273 |
| B9 | 4.5970 | 2.5910 | 8.2684 | 5.0507 |
| B8x8 | 34.1187 | 17.6738 | 47.5869 | 26.4099 |

Gates: B9 passes (p99 ≤ 10.67 ms, publish ≤ 1,323,132 bytes). B8 at 8 renderers fails its
5.33 ms p99 gate: the graph costs about 2 ms per steady block, so 8 renderers exceed it before any
replay. Replay work per round stays within its `2k` bound.


### Reference machine specification
- **CPU:** Intel64 Family 6 Model 186 Stepping 2, GenuineIntel (13th Gen Intel(R) Core(TM) i7-13620H)
- **Cores:** 16 logical cores
- **OS:** Windows 11 Build 26100 (amd64)
- **Power Plan:** Balanced (Power Scheme GUID: `381b4222-f694-41f0-9685-ff5bb260df2e`)
- **JDK:** OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, Eclipse Adoptium)

### Baseline benchmarks (`perfBench`)

Measured with `./gradlew :core-engine:perfBench` (forked JVM `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`, GC logging enabled, block budget 10.67 ms for 512 frames @ 48 kHz). Each trial renders 2,000 blocks; warmed until 3 consecutive trial medians agree within 5%. Statistics pooled from the warmed trials.

The first recorded baseline table never called `Timeline.prepare`, so the renderer missed nearly
every schedule window and most timed blocks were silent. `perfBench` now prepares each block
outside the timed render and fails on any schedule miss.

`perfBench` also pins itself to the performance cores (`0xFFF` here) and turns off Windows power
throttling (EcoQoS) for its own process. Unpinned, Windows moves the render thread to an efficiency
core after about 3 s. Throttling applies because Gradle launches the bench as a windowless child of
its daemon: with pinning alone every scenario still ran about 2x slower than with throttling off.
Override the mask with `-PperfAffinity=<hex mask>` or `-PperfAffinity=none`; run a subset with
`-PperfOnly=B1,B7`.

Voice matching now rejects on a hash of the matched event fields before comparing records. Stacked
events share an ordinal and onset, so every voice used to walk every candidate's `Tone` or
`SampleVoice` fields each frame; that was about 2/3 of B1 and 70% of B7. Output is byte-identical
to the previous engine for B1, B7, B7b and the signal demo.

Trial 0 runs while the JIT is still compiling and is now warm-up only: settling needs 3 agreeing
trials after it, and pooling never includes it. Before this, a bench that settled on its first 3
trials reported trial 0's compile stalls as the maximum (B7 20.0 ms, B2 13.4 ms).

Results from 2026-09-17, pinned, throttling off, after the matching change, warm-up excluded:

| Id | Scenario | Median (ms) | p99 (ms) | Max (ms) | RT Ratio | Publish Alloc (B) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | 32 tone voices (saw, pulse) | 0.7424 | 1.1199 | 15.9787 | 0.0696 | 814,696 | settled |
| B2 | 32 mono sample voices at 1x | 0.1073 | 2.0310 | 4.0899 | 0.0101 | 33,088 | settled |
| B3 | 32 stereo sample voices at 15.996x | 0.1075 | 4.0361 | 6.3800 | 0.0101 | 33,088 | settled |
| B4 | 32 stereo sample voices at 16.000x | 0.1076 | 2.8192 | 7.3048 | 0.0101 | 33,088 | settled |
| B5 | 8 audio sources, filters, feedback delay | 0.5833 | 0.9877 | 2.8723 | 0.0547 | 470,808 | settled |
| B6 | B5 plus 2 reverbs | 0.6435 | 1.1351 | 6.6016 | 0.0603 | 1,380,928 | settled |
| B7 | 32 sustained looped stereo voices at 4x | 2.9563 | 4.0684 | 10.0193 | 0.2771 | 33,872 | settled |
| B7b | 112 hat one-shots per cycle beside one loop | 0.6196 | 1.1930 | 6.1224 | 0.0581 | 33,248 | settled |

B2 to B4 medians are low because the one-shots finish early in each 2 s cycle; their p99 is the
playing cost. Every p99 is within the 10.67 ms budget. Maxima are single blocks and vary between
runs: six B1-only runs gave 4.4 to 5.1 ms except one at 11.6 ms, with Gradle and direct launches
alike. Recorded stalls after warm-up had no GC, deoptimization or safepoint nearby, so they are
treated as OS preemption. Use p99, not max, for pass criteria.

### After the resampler level change and cached voice selection

Pinned, throttling off, 2026-09-17. "Before" is the full run after the resampler level change near
powers of two, "after" adds cached voice selection. B4 was not re-run for "after".

| Id | Median before (ms) | Median after (ms) | p99 before (ms) | p99 after (ms) |
| --- | --- | --- | --- | --- |
| B1 | 0.7438 | 0.2207 | 1.3558 | 0.3464 |
| B2 | 0.1091 | 0.0167 | 2.1616 | 1.6346 |
| B3 | 0.1078 | 0.0164 | 2.8277 | 2.2992 |
| B5 | 0.5725 | 0.3221 | 0.8997 | 0.5821 |
| B6 | 0.6613 | 0.4000 | 1.3306 | 0.8317 |
| B7 | 2.9039 | 2.3483 | 4.2342 | 3.5745 |
| B7b | 0.6129 | 0.3542 | 0.9770 | 0.6273 |
| B9 | 3.3613 | 2.5910 | 5.8140 | 5.0507 |
| B8x1 | 5.9509 | 4.7006 | 9.5572 | 7.1972 |
| B8x4 | 16.9727 | 12.8068 | 23.4388 | 18.2451 |
| B8x8 | 23.2004 | 17.6738 | 38.9681 | 26.4099 |
| B8q | 0.1535 | 0.1541 | 0.2690 | 0.2764 |

Compared with the run after replay leasing, the resampler change alone took B9 from a 4.60 ms median
and 8.27 ms p99 to 3.36 and 5.81 ms. B9's publish bytes after cached voice selection are 1,211,344, within its 1,323,132 gate. B8 at 8 renderers
still fails its 5.33 ms gate.

### B9 adversarial (`perfBench -PperfOnly=B9`)

One renderer on a graph at the compiler's limits: 128 events per cycle (32 loops, 7 audio sources
at 10 events and 2 trigger sources at 13), 8 audio sources, 2 trigger sources driving envelopes on
the two mix gains, 32 looped stereo voices at 15.996x and 2 reverbs at 20 s. The sources share one
tone and the triggers one pattern so it fits in 64 nodes; `perfBench` fails if a compiler change
leaves B9 short of these limits.

Results from 2026-09-17, pinned, throttling off (two runs agreed within 0.1 ms on median and p99):

| Id | Scenario | Median (ms) | p99 (ms) | Max (ms) | RT Ratio | Status |
| --- | --- | --- | --- | --- | --- | --- |
| B9 | Adversarial, one renderer | 4.5970 | 8.2684 | 17.3549 | 0.4308 | settled |

The p99 gate of 10.67 ms passes. Bytes per publish are measured as the smallest of 10 publishes
after 2 warm-up publishes: 1,202,848 bytes, identical in both runs. B9 did not exist before replay leasing, so
this figure is the baseline and the gate is 1,323,132 bytes (+10%).

### B8 replay leasing (`perfBench -PperfOnly=B8`)

Renderers share one `ReplayBudget` (k = 2) and join the same graph together, one second after a
scheduled commit took effect, so every pending program replays one second of history. A block round
is one 512-frame render by every renderer in turn, as the sound thread pays it, timed only while a
replay is in progress. Each storm uses a fresh budget, timeline and renderers, built outside the
timed rounds. The graph is B6 with its last source replaced by 25 sustained loops, the most that fit
in 64 nodes.

Results from 2026-09-17, pinned, throttling off, `REPLAY_PER_FRAME = 2`:

| Id | Scenario | Median (ms) | p99 (ms) | Max (ms) | RT Ratio | Publish Alloc (B) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B8x1 | 1 renderer replaying, per block round | 5.7741 | 8.0119 | 12.0347 | 0.5412 | 3,537,200 | settled |
| B8x4 | 4 renderers replaying, per block round | 16.4415 | 21.8155 | 27.9090 | 1.5409 | 2,794,056 | settled |
| B8x8 | 8 renderers replaying, per block round | 22.3636 | 34.2357 | 47.1620 | 2.0959 | 2,795,352 | settled |
| B8q | 8 renderers waiting for a lease, per block round | 0.1568 | 0.3390 | 0.8793 | 0.0147 | 0 | settled |

Each trial times whole storms, so every grant in a storm is measured. Max replay work per round was
3,584 frames at 8 renderers, within the bound of `2k · REPLAY_PER_FRAME · 512 = 4,096`: a lease freed
mid-block can serve a later renderer's whole block in the same round, so the bound allows each lease
one handoff per round. A lease handoff (release, grant, requeue with 7 waiters) costs about 62 ns.

One replaying renderer fits the 10.67 ms block budget. The 8-renderer p99 gate of 5.33 ms fails, and
not because of replay: this graph costs about 2.3 ms per steady block, so 8 renderers need about
18 ms per round with no replay at all.

With `REPLAY_PER_FRAME = 4` (the first run) one replaying renderer took p99 15.75 ms, and rounds
reached 5,120 frames of replay work against the original bound of `k · 4 · 512 = 4,096`.

### B10 loop analysis (`perfBench -PperfOnly=B10`)

Times the feedback-loop analysis (`SignalGraph.feedbackLoops`) that every compile runs and the
editor runs on each wire drop and knob step, plus a full `GraphCompiler.compile`, on the worst case:
63 nodes and 123 edges, with 30 delays and 30 buses cross-linked into one loop. Report only, with no
gate, since sub-millisecond compile-time numbers are too noisy to gate on shared machines.

Results from 2026-09-19: analysis median 0.1891 ms, p99 0.3294 ms; full compile median 0.5447 ms,
p99 0.7752 ms. Before precomputing each loop's inputs and gain bounds once per loop, the analysis
took about 1.6 ms. A full `perfBench` run before and after the loop-gain limit showed no rendering
change: B2 to B9 and B8 medians within 4%, and B1 within 2% once rerun alternately with the old build.

### B11 effect carry (`perfBench -PperfOnly=B11`) — known limitation

Two measurements, both against the 10.67 ms block of 512 frames:

- **Copy:** `SignalRuntime.continueFrom`, the sound-thread copy that carries effect state across a
  republish or commit, for 1 renderer and for 8 switching in the same block (a commit reaching 8
  linked speakers). Between trials each outgoing runtime renders a block and 64 MB is streamed, so
  copies start cold. Reverbs copy their whole predelay line, so history survives a later predelay
  increase after an unchanged republish.
- **Switch round:** the whole block in which 8 `LiveRenderer`s on a B6-class patch switch, including
  rendering, the copy and the outgoing program's crossfade, next to a steady round without a switch.
  The same round without a session key (the old reset-and-replay path) is reported for comparison;
  its renderers have unlimited replay budgets, so it overstates what speakers sharing a budget of 2
  would pay.

The targets were a p99 of 0.5 ms for one copy and 1.33 ms for eight (a quarter of B8's half-block
headroom), and a switch round within the deadline. **They are not met reliably**, and they are not
raised to make B11 pass. Results from 2026-09-19 on the reference machine, B6-class patch (B5 plus
2 reverbs):

| Measurement | Runs | Median | p99 | Target |
| --- | --- | --- | --- | --- |
| Copy, 1 switch | 5 | — | 0.24–0.48 ms | ≤ 0.5 ms, met |
| Copy, 8 switches | 16 | 0.83–1.13 ms | 1.46–2.82 ms | ≤ 1.33 ms, missed in every run; ≥ 2.0 ms in 7 |
| Switch round, 8 renderers | 8 | 5.9–6.6 ms | 8.8–12.2 ms | ≤ 10.67 ms deadline, missed in 4 |
| Steady round, 8 renderers | 8 | 3.1–3.4 ms | 3.5–5.7 ms | — |
| Old path switch round | 1 | 10.8 ms | 16.0 ms | — |

The copy is not the main cost of a switch: a switch round costs about 3 ms more than a steady one at
the median. B11 splits the switch block into three `render` calls, so nothing is added to
`LiveRenderer`; rendering a steady block as three calls costs the same as one (3.21 against
3.20 ms). Medians over 100 trials, 8 renderers:

| Part of the block | Switching | Steady | Extra |
| --- | --- | --- | --- |
| Frame 0: switch, reset, copy, voice selection | 1.21 ms | 0.04 ms | 1.17 ms |
| Frames 1–239: crossfade, outgoing program also renders | 3.14 ms | 1.48 ms | 1.66 ms |
| Frames 240–511: incoming program only | 1.73 ms | 1.68 ms | 0.05 ms |

The outgoing program rendering through the crossfade is the largest part, then the copy and reset.
Once the crossfade ends the incoming program costs the same as in steady state.

A shorter crossfade for carried switches was trialled (`CarryFadeTrial`, report only) and not
adopted: at 64 frames a tone-gain or loop-gain jump scores about 6 times the click measure of the
normal 240-frame fade. The trial also showed that a carried reverb whose decay changes steps in
level about 9 ms after the switch, after any crossfade has ended, because its input gain changes at
once; fade length cannot fix that. Smoothing changed parameters inside the incoming program would. In game the sound thread renders 2048-frame chunks with about 171 ms of
buffering, so a single long block at a commit is unlikely to be audible, but it breaks this
benchmark's convention.

The worst-case patch (the full 192,000-frame delay budget and two reverbs at 500 ms predelay, about
4 MB per program) is report only and separate from the B6-class figures: copy p99 0.67–1.04 ms for
one switch and 3.97–5.42 ms for eight. The same patch already misses B8 on rendering alone.
Ownership transfer (handing buffers over instead of copying, with a crossfade reworked so each
carried effect is processed once) would remove the copy but not the rest of the switch cost, so it
waits for the breakdown to show its benefit.

A full `perfBench` run with carrying in place kept B1 to B10 within run-to-run noise, and B9's
publish allocation rose by about 540 bytes (one transfer-plan map on its signal program).
