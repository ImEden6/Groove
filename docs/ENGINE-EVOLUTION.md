# Phase 1 DSP implementation and Phase 2 handoff

## Still unimplemented (from this doc)

- Exact older/cross-revision effect-history reconstruction remains future work. Independent audio sources, arbitrary pattern-trigger envelopes and bounded local effect recovery have landed; see [signals](PHASE-2-SIGNALS.md).

Phase 1 retained graph versions 1 and 2, the existing packet format, saved-world format, and the one-cycle `LoopPlan`. Subsequent Phase 2 increments implement [scheduling](PHASE-2-SCHEDULER.md), [ports](PHASE-2-PORTS.md), and [modulation/audio routing](PHASE-2-SIGNALS.md).

## Implemented

- Tone `resonanceQ`: finite 0.1–20, default `1 / sqrt(2)`. The original five-argument `Tone` constructor and two-argument `Biquad.setLowPass` remain available.
- Sample `cutoffHz`: finite 20–20000 Hz, default 20000; sample `resonanceQ` uses the tone limits/default. The original four-argument `SampleVoice` constructor remains available.
- RBJ low-pass coefficients use variable Q. Filters clamp subnormal-scale output state and reset on non-finite output. The final render limiter remains `tanh`; high resonance can intentionally drive it.
- Live playback owns 32 persistent active voice slots and 32 fading tail slots per prepared program. Stereo samples use separate left/right filters; event index plus absolute onset distinguishes overlapping copies across cycles. Tones use the same voice ownership model, which also lets their complete filter state survive a steal.
- Newest onsets take priority at the live voice cap; equal onsets use stable plan order. A displaced voice retains oscillator/sample position and filter history during a 120-frame, 2.5 ms raised-cosine fade. Offline rendering uses a tail ring sized to its configured polyphony and a fade scaled to its output rate.
- Tail storage is bounded. If more tails are displaced than the ring can hold during one fade interval, the oldest tail is replaced. This overload case is not a universal click-free guarantee. Likewise, the discontinuity threshold in the regression fixtures is not a bound on arbitrary high-frequency audio.
- Resynchronization resets private state; signal graphs with effects then replay bounded prepared history before fading in. Rendering reuses preallocated voice, candidate, and fade storage; older/cross-revision effect history is not reconstructed.
- Bandlimited sample playback uses a 48-tap Kaiser-windowed sinc base kernel (beta 6.5), four prefiltered half-rate PCM levels, and precomputed phase/cutoff coefficient tables, replacing the original eight-point design.
- Dry audition passes its actual 48 kHz output rate into resampling. No editor controls or network fields were added.

## Bandlimited sample resampling

The fixed eight-point design has been replaced with a 48-tap Kaiser-windowed sinc base kernel (beta 6.5), four prefiltered half-rate PCM levels, and precomputed phase/cutoff coefficient tables.

### Quality contract

Let N be the smaller of source and effective output Nyquist frequencies, expressed in the source clock domain. The intended passband is 0–0.8N; 0.8N–N is the transition band. Downsampling tests require at least 50 dB rejection at and above N, excluding sample-edge transients. Upsampling tests compare the output with the continuous sine reference, including interpolation-image error. The passband RMS-error limit is 0.3%.

`ResamplerTests` measures 99 stopband cases: eleven conversion ratios from 1.01 to 16 (including values just below octave boundaries), nine frequencies each, and 512 output samples per case. Passband checks cover conversion ratios from 1/24 to 16 at 80% of the applicable Nyquist limit. This is a regression grid, not an exhaustive guarantee over every real-valued frequency/phase or sample-edge transient.

Measured on 2026-09-12:

- Minimum stopband rejection: **68.21 dB**, relative to a unit-amplitude input sine.
- Maximum passband RMS error in the tested cases: **0.05141%**.
- Original 48 kHz/10 kHz, 4x fixture: **78.23 dB less alias energy than linear**, versus 12.35 dB with eight taps. This comparison is not the absolute stopband measurement above.

Integer-frame, unity-rate playback preserves original PCM exactly. Source-frame step must be finite, positive, and no greater than 16; this covers Groove's 8–192 kHz assets, 0.25–4 pitch range, and 48 kHz output. Out-of-range sample positions remain silent. Filter edges use zero padding. Filter output can overshoot source peaks; live mixing uses its existing limiter, and dry audition clamps before integer PCM conversion to prevent wraparound.

### Processing and memory

Octave selection reduces the runtime conversion ratio below two. The runtime filter uses 49–97 coefficient slots, with interpolated fractional phases. Shared tables use 64 cutoff subdivisions and 256 fractional phases. Convolution blends two independent dot products; the interior loop is unrolled to remove the serial accumulation bottleneck. There are no runtime transcendental calculations, sample allocations, or linear fallback.

The PCM pyramid is built at sample construction on the existing decoder/control path. The four additional levels increase decoded storage to approximately **1.94 times** the original PCM size. `SampleData.bytes()` includes every level, so cache eviction and active-bank limits remain honest. No graph, save, or wire-format change is required.

Local warmed measurements (best trial, diagnostic only):

| Work | Time |
| --- | --- |
| Linear sampler | 11.5 ns/output sample |
| Bandlimited sampler at 16x | 62.9 ns/output sample |
| 32 stereo voices at 16x, 85.33 ms of audio | 24.24 ms |
| 32 stereo voices at 15.996x, 85.33 ms of audio | 33.96 ms |

These measurements cover one renderer on this machine; they are not a hardware guarantee or a benchmark of eight simultaneous speaker emitters. The 48-tap design was selected because the measured sweep clears 50 dB comfortably with balanced memory and CPU overhead.

## Compatibility

Old graphs load using defaults. New graphs carrying these parameters cannot be opened in old builds that reject unknown parameters. Java convenience constructors are source-compatible; changed record shapes require downstream Java consumers to recompile. Graph/wire schema versions are unchanged.

## Verification

`DspTests`, invoked by `EngineTests`, covers Q sweeps, a measurable resonance peak, parameter validation/defaults, overlapping stereo sample filtering across cycle boundaries, alias reduction, interpolation boundaries/DC gain, and forced tone/sample steals. Steal fixtures place a loud victim away from a zero crossing; offline rendering also checks chunk-size independence. Existing live/sample suites cover aligned clients, late joins, underruns, and missing assets. Backend tests round-trip the new parameters through existing JSON and submission packets.

All three checks passed on 2026-09-12. The final combined `test runClient -PaudioSmoke` run passed the engine/live/sample/backend suites and reported 38 streaming reads, one induced-underrun recovery, and successful explicit stream closure.

Commands:

- `./gradlew.bat -p core-engine check`
- `./gradlew.bat test`
- `./gradlew.bat runClient -PaudioSmoke`

## Phase 2, staged separately

Preserve `Pattern`'s continuous algebra. Replace only the static one-cycle compilation/playback restriction with rolling lookahead queries prepared off the audio thread. Introduce typed `PATTERN`, `MOD_FLOAT`, `TRIGGER`, and `AUDIO` ports; reusable 64-frame control buffers with slewing; and the v3 graph/serialization/editor migration.

Add LFO, envelope, attenuverter, filter, delay, step sequence, and mix-bus nodes. Modulation derives deterministically from shared transport time. Hz LFOs use a server-assigned per-node birth timestamp, reset only on that node's frequency edits; unrelated graph edits preserve phase. Envelopes default to ONE_SHOT, with GATED release anchored to the event's whole-arc end.

Reject zero-delay loops by cutting edges into delay/buffer nodes and checking the remaining graph for cycles, including diamond-bypass regressions. Feedback requires at least one 64-frame block of delay. Verify transparent v1/v2 migration, late-join phase equivalence, unrelated-edit stability, tempo changes, and audio-thread allocation behavior before shipping v3.
