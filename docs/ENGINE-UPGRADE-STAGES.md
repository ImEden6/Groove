# Engine upgrade stages

## Status

| Stage | Scope | Status |
| --- | --- | --- |
| 1 | Note names, scale-degree sequences, transpose, chords, filter modes | Implemented; automated checks pass |
| 2 | Shared voice DSP, offline samples, sample regions and slicing | Next |
| 3 | Reverse, swing, pulse/PWM, tempo-synced delay | Planned |
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
