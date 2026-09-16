# Phase 4 plan: reverb, sustained sample loops, measured performance

Status: planned. Scope comes from the Stage 4 row in
[ENGINE-UPGRADE-STAGES.md](ENGINE-UPGRADE-STAGES.md).

## Decisions

| Question | Decision |
| --- | --- |
| Late join for looped sample voices | Join mid-note. Loop position is a pure function of voice age, so a late joiner hears the right place behind the existing 240-frame fade. One-shot samples keep skipping onsets before the anchor. |
| Reverb memory budget | Own limit: at most 2 `reverb` nodes per graph, separate from the 192,000-frame delay budget. |
| Benchmark harness | Dependency-free. No JMH. |

## Order of work

1. Performance baseline (harness only, no optimisations yet)
2. Sustained sample loops
3. Reverb
4. Performance fixes, each measured against the baseline
5. Demo audio updated with reverb and loops
6. Docs

The baseline comes first so every later change has a before number. Loops come
before the scheduler rework (P4) because loops make the per-frame window scan larger.

---

## 1. Performance baseline

### Harness

Add a `perfBench` `JavaExec` task in `core-engine/build.gradle`. It is not wired into
`check`, since timings are noisy and would make the build flaky.

- Render in 512-frame blocks after a JIT warmup, over at least 20 trials.
- Report median, p99 and max milliseconds per block against the 10.67 ms real-time
  budget, plus the real-time ratio.
- Print JDK version, CPU model and core count, and trial count with the results.
- Fixed seeds and fixed patterns so runs are comparable.

The existing microbenchmarks report the best warmed trial. Two runs on the same code
on 2026-09-16 disagreed by about 4x (32 voices at 15.996x: 275 ms, then 35 ms, per
85.33 ms of audio), so best-of numbers are not used for decisions.

Use JDK Flight Recorder (`-XX:StartFlightRecording`, built into the JDK) to confirm
hotspots before changing code.

### Scenarios

| Id | Scenario |
| --- | --- |
| B1 | 32 tone voices (saw, pulse) |
| B2 | 32 mono sample voices at step 1x |
| B3 | 32 stereo sample voices at 15.996x (widest kernel) |
| B4 | 32 stereo sample voices at 16.000x |
| B5 | 8 audio sources, filters, feedback delay |
| B6 | 2 reverbs on B5 (added in step 3) |
| B7 | 32 looped sample voices sustaining (added in step 2) |

Correctness gates stay in `check`: zero-allocation assertions and output comparisons.

---

## 2. Sustained sample loops

### Parameters on `sample` (`GENERATOR_SAMPLE`)

| Param | Range | Default | Meaning |
| --- | --- | --- | --- |
| `loop` | 0 or 1 | 0 | 0 keeps today's one-shot behaviour |
| `loopStart` | 0..1 | 0 | Fraction of the prepared region, in playback order |
| `loopEnd` | 0..1 | 1 | Fraction of the prepared region, in playback order |
| `loopFadeMs` | 0..500 | 10 | Crossfade length at the loop seam |

Fractions of the prepared region mean loop points follow slices, regions and reverse
without separate conversions. Compile-time rule: `loopStart < loopEnd`.

### Maths

Work in prepared source frames. `N` is the prepared region length.

```
Ls = floor(loopStart · N)
Le = floor(loopEnd · N)
P  = Le − Ls                          period
X  = min(fadeFrames, Ls, floor(P/2))  effective fade

s = age · rate · pitchRatio           position, stateless in age
u = s < Le ? s : Ls + (s − Ls) − P · floor((s − Ls) / P)

if Le − X ≤ u < Le:
    w   = (u − (Le − X)) / X
    out = a(w) · S(u) + b(w) · S(u − P)      u − P lies in [Ls − X, Ls)
else:
    out = S(u)
```

`S` is the existing bandlimited `SampleData.at`. Both reads sit on real contiguous
audio, so the interpolation kernel never crosses a hard edge.

Continuity: as `u → Le`, `w → 1` and the output tends to `S(Le − P) = S(Ls)`, which is
where the next period starts with `w = 0`.

Fade gains. An equal-gain fade suits time-aligned identical material; an equal-power
fade suits uncorrelated material. Correlation is measured once on the control thread
when samples are prepared:

```
ρ = Σ x·y / sqrt(Σ x² · Σ y²)    x = S[Le−X, Le),  y = S[Ls−X, Ls)
ρ = max(ρ, −0.5)

a(w) = cos(πw/2) / sqrt(1 + 2ρ·cos(πw/2)·sin(πw/2))
b(w) = sin(πw/2) / sqrt(1 + 2ρ·cos(πw/2)·sin(πw/2))
```

Check: the output power of `a·x + b·y` for unit-power signals is
`a² + b² + 2ρab`. With the normalisation this is exactly 1. At `ρ = 1` it reduces to
amplitude-preserving; at `ρ = 0` it is plain equal-power. The clamp keeps the
denominator at or above `sqrt(0.5)`.

Voice lifetime:

- One-shot: prepared duration / pitchRatio (unchanged).
- Looped: `event length in cycles · secondsPerCycle`, then a 20 ms linear release
  (same as tones), capped at 40 s of sustain.

### Edge cases

1. `loop = 0` must render bit-identical output to the current engine. Golden checksum
   captured before the change.
2. `Ls < fadeFrames` shrinks the fade to `Ls`. `Ls = 0` means no fade; document the click.
3. Minimum period `P ≥ 128` frames. At a 16x step the kernel spans up to 97 frames.
   Checked when samples are prepared, where asset length is known (same place
   `SampleRegion.start` validates), throwing on the control thread.
4. Scheduler history. `LookaheadScheduler` sizes history from sample duration (cap 40 s)
   and the ring holds 59 cycles. Nested `fast(0.25)` can make an event arbitrarily long,
   so sustain is capped at 40 s (51 cycles at 300 BPM, fits). Ended looped events are
   pruned like ended tones (`whole.end + release ≤ base − 1`) so the window stays small.
5. Late join (decided): looped voices with `onset < anchorCycle` start at their computed
   position behind the join fade.
6. Voice stealing: sustained voices hold the 32 slots longer. Dense-pattern test.
7. Tempo change: lifetime depends on BPM like tones; position depends only on age, so it
   stays continuous.
8. `SampleVoice` gains loop fields: 21 constructor call sites, and loop variants count
   toward `PreparedSamples.MAX_VOICES` (128). No extra PCM memory.
9. Offline parity: `Renderer` duplicates the duration logic
   (`core-engine/src/main/java/groove/engine/Renderer.java:80`) and must match live.
10. Editor: loop knobs, clamps, and `loopStart < loopEnd` enforcement modelled on the
    `START_FRAME`/`END_FRAME` rule in `EditorState`. Old saves lack the keys and default
    to `loop = 0`.

### Tests

- `loop = 0` golden checksum unchanged.
- Seam continuity: max sample-to-sample jump across the seam on a sine sample stays
  within the jump inside the loop body.
- Late join at an arbitrary age matches a voice that played from the start.
- Offline/live parity with loops.
- Period and fade validation errors.
- Zero allocation while sustaining.

---

## 3. Reverb (Dattorro plate)

Chosen over Freeverb: mono in, stereo out, few parameters, and a modulated tank that
avoids metallic ringing. Freeverb needs 8 combs and 4 allpasses per channel.

### Topology and constants

Lengths are published at 29,761 Hz. Scale by `48000 / 29761 = 1.61285` and round.

| Part | Lengths at 29,761 Hz | Coefficients |
| --- | --- | --- |
| Input diffusers (series allpass) | 142, 107, 379, 277 | 0.75, 0.75, 0.625, 0.625 |
| Tank half A | modulated allpass 672 (±16), delay 4453, allpass 1800, delay 3720 | decay diffusion 1 = 0.70, decay diffusion 2 = 0.50 |
| Tank half B | modulated allpass 908 (±16), delay 4217, allpass 2656, delay 3163 | same |
| Left output taps | +266, +2974, −1913, +1996, −1990, −187, −1066 | |
| Right output taps | +353, +3627, −1228, +2673, −2111, −335, −121 | |

Tap values were cross-checked against an independent implementation's millisecond table.

Memory: about 22,526 frames at 29,761 Hz, about 36,330 frames at 48 kHz, mono doubles,
about 290 KB. Predelay up to 500 ms adds 24,000 frames (about 190 KB). Two reverbs plus
the full delay budget is about 4 MB.

### Parameters

The node outputs wet signal only, like `delay`. Blend with `mix_bus`.

| Param | Range | Default |
| --- | --- | --- |
| `decaySeconds` | 0.1..20 | 1.8 |
| `dampingHz` | 200..20000 | 6000 |
| `bandwidthHz` | 200..20000 | 12000 |
| `preDelayMs` | 0..500 | 0 |

### Maths

Decay gain. The decay multiplier is applied 4 times per full figure-eight loop of about
0.715 s, so once every `τ ≈ 0.179 s`. For 60 dB of decay over `T60`:

```
g = 10^(−3 · τ / T60)
```

`T60 = 20 s` gives `g = 0.940`. The paper's default `g = 0.5` corresponds to about 1.78 s.
Allpasses and damping also shape decay, so this is nominal and verified by measurement.

One-pole filters in the tank and at the input:

```
damping:    y[n] = (1 − d)·x[n] + d·y[n−1],   d = exp(−2π·dampingHz / fs)
bandwidth:  y[n] = b·x[n] + (1 − b)·y[n−1],   b = 1 − exp(−2π·bandwidthHz / fs)
```

Cutoffs clamp to `0.45 · fs`. Input is `(L + R) / 2`.

Modulated allpass read position uses linear or Hermite interpolation. Allpass
interpolation is avoided because it produces transients when the delay moves.

### Edge cases and regressions

1. `LiveRenderer` marks only DELAY and FILTER as stateful
   (`LiveRenderer.java:120`). REVERB must be added, or late joins get no replay.
2. Reverb does not break feedback cycles. Allpasses pass the current input straight
   through, so `SignalGraph.java:168` and `EditorState.java:206` stay DELAY-only.
3. Modulation LFO phase derives from absolute `serverNanos`, never a running counter,
   so replay is deterministic.
4. Replay covers at most 1 s. Late joiners do not hear the full tail of a long reverb.
   Documented alongside the delay behaviour.
5. Denormals: tank tails decay toward subnormal doubles, which are very slow on x86 and
   Java cannot enable flush-to-zero. Snap `|x| < 1e-30` to 0 on tank writes. A 0.1 s
   decay reaches the subnormal range after about 10 s of silence.
6. `bounded(±8)` inside the tank; master `tanh` stays the final bound.
7. Graph limit: more than 2 reverbs fails compile with `"At most 2 reverbs per graph"`.
8. Touchpoints: `NodeType` (ports, `isSignalNode`), `NodeParam`, `SignalGraph` (allowed
   params, ranges, count limit), `SignalRuntime`, `LiveRenderer` stateful set,
   `EditorState` defaults/steps/clamps, `RotaryKnob` labels, editor palette. `GraphJson`
   matches types by name, so enum order does not matter.

### Tests

- Finite and within bounds under full-scale noise for 10 s.
- Measured T60 within ±20% of `decaySeconds`, using Schroeder backward integration of the
  impulse response energy.
- Left/right correlation of the impulse response below 0.5.
- Two runtimes fed identical input produce bit-identical output.
- `reset()` clears all state.
- Zero allocation in `process`.
- Late join triggers a history recovery for a graph containing only a reverb.
- Third reverb rejected.

---

## 4. Performance fixes

Each fix lands separately with before/after `perfBench` numbers.

| Id | Where | Problem | Fix | Output gate |
| --- | --- | --- | --- | --- |
| P1 | `VoiceDsp.add` | Mono samples run the full resampling kernel twice (once per channel, same data) | Interpolate once per frame, apply both pan gains; for stereo, share kernel setup across channels | Bit-identical |
| P2 | `SamplePlayback.value` | `cos`/`sin` of pan and duration division every call | Precompute in `VoiceDsp.start` | Bit-identical |
| P3 | `SampleData.at` | Steps just under a power of two (15.996x) stay on the higher level with the widest band: 97 taps × 2 phase rows | Move to the next level when within about 2% of a power of two | `ResamplerTests` still ≥ 68.21 dB stopband and ≤ 0.05141% passband error |
| P4 | `LiveRenderer.sample` | Full window scan and up to 32×32 record comparisons every frame | Recompute voice selection only when the cycle crosses the next onset or voice end | Same voices start on the same frames, randomised differential test against the old path |
| P5 | `SignalRuntime.p()` | Map lookup and unboxing per node per frame | Resolve params into `double[]` at construction | Bit-identical |
| P6 | Feedback paths | Subnormal tails | Snap tiny values to 0 on writes | Within 1e-30 |

P1, P2, P5 keep the old code in the test sources for comparison until the phase closes.

---

## 5. Demo audio

Both demos gain reverb so Phase 4 is audible, and `/groove signal-demo` in game picks it
up through `SignalDemo.graph()`.

### `renderSampleDemo` (`SampleDemo`, `core-engine/build/sample-demo.wav`)

Current: sliced swung drums with a reversed snare, pulse bass, and a pulse lead through a
synced 1/8 feedback delay.

Phase 4 changes:

- Lead path: `mix` → `delay` feedback as today, then the delay loop output into a
  `reverb` (`decaySeconds` 2.5, `dampingHz` 5000, `preDelayMs` 20), blended with the dry
  lead in a `mix_bus`.
- Drums: a second short `reverb` (`decaySeconds` 0.6, `dampingHz` 8000) mixed low under
  the dry kit. This uses the second of the two allowed reverbs.
- Add a sustained looped pad voice (a looped slice of a factory sample, whole-cycle
  events) through the long reverb, to demonstrate loops and reverb together.
- Length stays 16 s so the file remains comparable. The last 2 s carry the reverb tail
  after the pattern stops.

`Demo.write` currently takes one `SignalRuntime` for the lead. It needs a second runtime
(or a stereo graph with two sources) for the drum reverb.

### `renderSignalDemo` (`SignalDemo`, `core-engine/build/signal-demo.wav`)

- Insert a `reverb` after the filter/delay `mix` and before `master`, with the dry lead
  still bypassing it, so the file shows dry lead over a wet, echoing bass.
- `SignalDemo.graph()` gets the same reverb so the in-game demo matches the rendered file.

### Checks

- Both demos compile against the 2-reverb limit and render without exceptions in `check`
  (render to a temp file, assert finite samples and non-silent tail energy in the final
  2 s).

---

## 6. Docs

- `ENGINE-UPGRADE-STAGES.md`: Stage 4 usage section (parameters, formulas above), status
  row updated, before/after performance table with hardware noted.
- `PHASE-2-SIGNALS.md`: node table gains `reverb`.
- `FUTURE-WORK.md`: Stage 4 under recently landed.
- Remove this plan's "planned" status or fold it into the stages doc when done.

## Sources

- J. O. Smith, *Physical Audio Signal Processing*, CCRMA:
  [Freeverb](https://ccrma.stanford.edu/~jos/pasp/Freeverb.html),
  [Lowpass-feedback comb](https://ccrma.stanford.edu/~jos/pasp/Lowpass_Feedback_Comb_Filter.html),
  [Achieving desired reverberation times](https://ccrma.stanford.edu/~jos/pasp/Achieving_Desired_Reverberation_Times.html),
  [Choice of delay lengths](https://ccrma.stanford.edu/~jos/pasp/Choice_Delay_Lengths.html)
- [Freeverb `tuning.h`](https://raw.githubusercontent.com/sinshu/freeverb/master/Components/tuning.h)
- J. Dattorro, [Effect Design Part 1: Reverberator and Other Filters](https://ccrma.stanford.edu/~dattorro/EffectDesignPart1.pdf), JAES 45(9), 1997
- [jon-pd](https://github.com/antonhornquist/jon-pd) (Dattorro constants in milliseconds, used to cross-check taps)
- [EarLevel: floating-point denormals](https://www.earlevel.com/main/2019/04/19/floating-point-denormals/),
  L. de Soras, [Denormal numbers in floating point signal processing applications](https://ldesoras.fr/doc/articles/denormal-en.pdf)
- [Sound On Sound: linear or constant-power crossfades](https://www.soundonsound.com/sound-advice/q-should-use-linear-or-constant-power-crossfades)
