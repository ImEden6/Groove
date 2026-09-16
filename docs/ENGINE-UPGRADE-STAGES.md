# Engine upgrade stages

## Status

| Stage | Scope | Status |
| --- | --- | --- |
| 1 | Note names, scale-degree sequences, transpose, chords, filter modes | Implemented; automated checks pass |
| 2 | Shared voice DSP, offline samples, sample regions and slicing | Implemented; automated checks pass |
| 3 | Pattern reverse, swing, pulse/PWM, tempo-synced delay | Next |
| 4 | Reverb, sustained sample loops, measured performance improvements | Planned |

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
