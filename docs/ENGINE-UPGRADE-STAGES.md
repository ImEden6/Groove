# Engine upgrade stages

## Status

| Stage | Scope | Status |
| --- | --- | --- |
| 1 | Note names, scale-degree sequences, transpose, chords, filter modes | Implemented; automated checks pass |
| 2 | Shared voice DSP, offline samples, sample regions and slicing | Implemented; automated checks pass |
| 3 | Pattern reverse, swing, pulse/PWM, tempo-synced delay | Implemented; automated checks pass |
| 4 | Reverb, sustained sample loops, measured performance improvements | In progress |

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

A tempo change publishes a new program whose delay lines start empty. History replay
only covers time since the new program's anchor cycle (at most one second), so right
after a switch it replays roughly one block, not the old echo tail. The outgoing
program's audio fades out over the 240-frame program crossfade, then the synced delay
stays silent until one full new delay length of input has passed through it (24,000
frames for a 1/8 note at 60 BPM). `SignalTests.delaySync` asserts this gap.

### Audio demonstrations

Stage 3 adds automated build targets and offline demonstration scripts:

- `gradlew.bat -p core-engine renderSampleDemo`: renders `core-engine/build/sample-demo.wav`
  (16.0s, 48 kHz stereo PCM), demonstrating sliced factory drums, reversed snare,
  continuous 16th swing, pulse bass, and a swung pulse lead echoing through a synced
  1/8-note delay loop.
- `gradlew.bat -p core-engine renderSignalDemo`: renders `core-engine/build/signal-demo.wav`
  (8.0s, 48 kHz stereo PCM), demonstrating dual live sources, LFO-modulated biquad filtering,
  and tempo-synced feedback delay via `LiveRenderer`.

## Stage 4 baseline performance on reference machine

### Reference machine specification
- **CPU:** Intel64 Family 6 Model 186 Stepping 2, GenuineIntel (13th Gen Intel(R) Core(TM) i7-13620H)
- **Cores:** 16 logical cores
- **OS:** Windows 11 Build 26100 (amd64)
- **Power Plan:** Balanced (Power Scheme GUID: `381b4222-f694-41f0-9685-ff5bb260df2e`)
- **JDK:** OpenJDK 64-Bit Server VM Temurin-21.0.10+7 (build 21.0.10+7-LTS, Eclipse Adoptium)

### Baseline benchmarks (`perfBench`)

Measured with `./gradlew :core-engine:perfBench` (forked JVM `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`, GC logging enabled, block budget 10.67 ms for 512 frames @ 48 kHz). Each trial renders 2,000 blocks; warmed until 3 consecutive trial medians agree within 5%. Statistics pooled from the warmed trials.

The first recorded table (step 1) never called `Timeline.prepare`, so the renderer missed nearly
every schedule window and most timed blocks were silent. `perfBench` now prepares each block
outside the timed render and fails on any schedule miss.

`perfBench` also pins itself to the performance cores (`0xFFF` here). Unpinned, Windows moves the
render thread to an efficiency core after about 3 s, which slowed even a plain Java loop 2 to 2.5x
and kept B1 and B7 from settling. Override with `-PperfAffinity=<hex mask>` or `-PperfAffinity=none`.

Voice matching now rejects on a hash of the matched event fields before comparing records. Stacked
events share an ordinal and onset, so every voice used to walk every candidate's `Tone` or
`SampleVoice` fields each frame; that was about 2/3 of B1 and 70% of B7. Output is byte-identical
to the previous engine for B1, B7, B7b and the signal demo.

Results from 2026-09-17, pinned, after the matching change:

| Id | Scenario | Median (ms) | p99 (ms) | Max (ms) | RT Ratio | Publish Alloc (B) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | 32 tone voices (saw, pulse) | 2.3979 | 3.4646 | 7.3537 | 0.2247 | 722,760 | settled |
| B2 | 32 mono sample voices at 1x | 0.3635 | 7.1392 | 21.0489 | 0.0341 | 33,088 | settled |
| B3 | 32 stereo sample voices at 15.996x | 0.3557 | 13.5054 | 17.0637 | 0.0333 | 33,136 | settled |
| B4 | 32 stereo sample voices at 16.000x | 0.3557 | 8.7371 | 10.6887 | 0.0333 | 33,088 | settled |
| B5 | 8 audio sources, filters, feedback delay | 1.8231 | 3.1570 | 12.3718 | 0.1709 | 470,808 | settled |
| B6 | B5 plus 2 reverbs | 2.0611 | 2.9580 | 8.2959 | 0.1932 | 1,380,928 | settled |
| B7 | 32 sustained looped stereo voices at 4x | 9.4605 | 11.5776 | 29.0271 | 0.8866 | 33,872 | settled |
| B7b | 112 hat one-shots per cycle beside one loop | 1.9770 | 3.4191 | 8.0971 | 0.1853 | 33,248 | settled |

B2 to B4 medians are low because the one-shots finish early in each 2 s cycle; their p99 is the
playing cost. B7 p99 and B3 p99 still exceed the 10.67 ms budget. In game the sound thread is not
pinned, so it can still land on an efficiency core.


