# Phase 4 plan: reverb, sustained sample loops, measured performance

Status: planned, revision 4. Revisions 1, 2, and 3 were reviewed on 2026-09-16
([PHASE-4-PLAN.review.json](PHASE-4-PLAN.review.json)). This revision addresses the
revision 3 findings: unreadable project asset pinning across restart [W1], explicit
network channel namespace [W2], replay lease release on audio stream close [W3],
loop geometry non-negative guards, and configuration player name retrieval. Tags from
prior reviews are not reused.

Scope comes from the Stage 4 row in [ENGINE-UPGRADE-STAGES.md](ENGINE-UPGRADE-STAGES.md).

## Decisions

| Question | Decision |
| --- | --- |
| Late join for looped sample voices | Join mid-note. Loop position is a function of voice age, so a late joiner starts at the right place behind the existing 240-frame fade. One-shot samples keep skipping onsets before the anchor. |
| Reverb limit | At most 2 `reverb` nodes per graph, separate from the 192,000-frame delay budget. A CPU and memory bound. |
| Benchmark harness | Dependency-free. No JMH. |
| Compatibility | Saves are forward-only. Downgrading Phase 4 to Phase 3 is unsupported. The server rejects clients on a different Groove protocol before any play packet [R1, W2]. |
| Reverb loudness contract | Output peak magnitude response is −1 dB at every `decaySeconds`. Saved patches rely on this, so it is never retuned in place [W3, N5]. |
| Reverb in feedback loops | Allowed only when the compiler can prove the loop's worst-case gain is at most −1 dB. Existing loops without a reverb keep today's behaviour [R3]. |

## Order of work

Each step lands as its own commit(s) and leaves `check` green.

| Step | Work | Depends on |
| --- | --- | --- |
| 0 | Compatibility guard: protocol check, unreadable-project state, session `.bak`, decode-only fixtures | none [N3] |
| 1 | Baseline: `perfBench`, float64 goldens including Phase 3 fixture renders, tighter resampler asserts, allocation helper | 0 |
| 2 | P1, P2, P5 (output-preserving) | 1 |
| 3 | Per-source scheduler history, pruning, single lifetime function | 1 |
| 4 | Sustained sample loops | 2, 3 |
| 5a | Reverb tracer bullet: node, params, loop-gain check, editor, JSON, trivial DSP, playable in game | 0, 1 |
| 5b | Dattorro tank behind the same interface, denormal snap inside `Reverb` | 5a |
| 6 | Replay leasing | 5b |
| 7 | P6 for delay and `Biquad` state, then P3 in its own commit | 5b |
| 8 | P4 voice selection, only if benchmarks still show the scan | 3, 6 |
| 9 | Demo audio | 4, 5b |
| 10 | Docs | all |

---

## 0. Compatibility guard

Policy: a world or patch saved by Phase 4 may not load in Phase 3, and downgrading is
unsupported. Phase 4 code cannot protect a Phase 3 build: Phase 3's unguarded
`EditorProject.load` and `/groove save` will still discard data [W2]. `BACKEND-USAGE.md`
gets a "back up your world before downgrading Groove" note. From Phase 4 on, a future
build's data is preserved and edits are refused rather than lost.

### 0.1 Protocol check on the server [R1]

Today the server sends the session snapshot on `JOIN` (`MusicServer.java:83-86`),
`fabric.mod.json` has no Groove version constraint, and a Phase 3 client that cannot decode
a Phase 4 graph just logs "Rejected Groove session" (`MusicClient.java:214`) and stays silent.

- Scheme: one exact integer, `GrooveProtocol.VERSION = 4`, bumped whenever a phase adds
  saved or network data older builds cannot read. No ranges.
- Channel: `GrooveMod.id("protocol")` (wire identifier `modid:protocol`, matching all existing
  Groove packets), a configuration-phase payload carrying the version int [W2].
- Server, in `ServerConfigurationConnectionEvents.CONFIGURE`:
  - `!ServerConfigurationNetworking.canSend(handler, ProtocolPayload.TYPE)`: the client has
    no Groove or a pre-Phase-4 Groove. Disconnect with
    `"This server requires Groove protocol 4. Update Groove."`
  - otherwise add a configuration task that sends the server version and waits for the
    client's reply. A different version disconnects with
    `"Groove version mismatch: server 4, client <n>"`.
- Both checks finish before the play phase, so no graph packet reaches a mismatched client.
- Client: a server without the `GrooveMod.id("protocol")` channel is a server without Groove (or an
  older Groove). The client does not disconnect; older servers only send graphs a newer
  client can read.
- Integrated singleplayer passes trivially (same build).
- Log a WARN with the player name (via `handler.getOwner().getName()`) and both versions on
  each rejection [W15].

### 0.2 Unreadable editor projects [W1]

`EditorProject.load` (`EditorProject.java:52`) decodes `Draft` and `Published` with no error
handling, and `EditorProject.session()` is read by `SpeakerServer` (`:107, :191`),
`HeadphoneServer` (`:60, :100`) and `EditorServer` (`:65, :68, :105`).

- Decode `Draft` and `Published` separately. For each that fails, keep the raw string and
  mark that field unreadable, with the decode error message.
- While either field is unreadable:
  - `EditorServer` rejects DRAFT, COMMIT, BPM, play/stop and every other mutating packet
    with `"This project needs a newer Groove version"`, and sends that flag to the viewer
    instead of an empty graph.
  - `SpeakerServer` and `HeadphoneServer` answer as unavailable with the same reason, not an
    empty committed or preview graph.
  - Samples referenced by the project count as referenced, so no asset is dropped [W1]. On load,
    `EditorProject` extracts referenced sample hashes directly from the unreadable raw JSON string
    (scanning for `"sample"` objects) into a preserved `Set<AssetRef>` so `SpeakerServer.allowsAsset`
    and `HeadphoneServer.allowsAsset` continue serving them across server restarts.
  - Save writes the raw strings back byte for byte.
- Log a WARN with block position and decode error on load [W15].

### 0.3 Session store backup [N6]

`SessionStore.write` copies the current file to `<name>.bak` when that file fails to decode,
with no stored flag. An existing `.bak` is never overwritten. Log a WARN with the path [W15].

### 0.4 Additive data

- `Graph.CURRENT_VERSION` stays 3; new params have defaults, so Phase 3 saves load unchanged.
- Loop keys and canonicalisation are handled in step 4, where they exist [N3].

### 0.5 Decode-only fixtures

Check in Phase 3 graph JSON (signal graph with synced delay, sample graph with slices and
reverse, v2 pattern graph) and test that they decode and compile. Render comparison moves to
step 1c [N3].

### 0.6 Tests in `backendTest` [R6]

- Protocol: equal versions complete configuration; a client without the channel and a client
  with a different version are disconnected with the exact messages above.
- Editor project with an unknown node type in `Draft` and an out-of-range param in
  `Published`: loads; each field is flagged independently; DRAFT, COMMIT and BPM packets are
  rejected; speaker and headphone requests answer unavailable; save and re-read yields
  byte-identical raw strings.
- Session store: a corrupt file gets a `.bak` with the original bytes before any write; a
  second corrupt read does not overwrite an existing `.bak`.

---

## 1. Baseline

### 1a. `perfBench` harness

A `JavaExec` task in `core-engine/build.gradle`, main class `groove.engine.PerfBench` in test
sources, `classpath = sourceSets.test.runtimeClasspath`. Not wired into `check`.

- One forked JVM, `-Xms1g -Xmx1g -XX:+AlwaysPreTouch`, GC logging on; GC pauses reported
  separately.
- Warm up until 3 consecutive trial medians are within 5%, capped at 120 s; past the cap the
  result is reported as `unsettled` and not used for decisions [N8].
- Each trial renders at least 2,000 blocks of 512 frames. Per-block `nanoTime` values are
  pooled across trials; median, p99 and max come from the pooled set.
- Budget per block is 10.67 ms; real-time ratio is median block time / 10.67 ms.
- Before/after comparisons run interleaved (A B A B A B) in one JVM, with the old path
  selectable by a system property only while that change is under review; the property and
  old path are deleted when it lands [N8].
- Spread is max − min of trial medians within each arm. A change counts as faster only if its
  median gain exceeds both arms' spreads in all 3 rounds [N8].
- Reports: JDK, OS, CPU model (`PROCESSOR_IDENTIFIER` on Windows, `/proc/cpuinfo` on Linux,
  else "unknown"), logical cores, Windows power plan (`powercfg /getactivescheme`, best
  effort), bytes allocated per `LiveRenderer.publish`, and for B9 the `prepare()` time and
  allocation per cycle change [R4].

**Reference machine [W14].** The machine the step-1 baseline runs on is recorded in
`ENGINE-UPGRADE-STAGES.md` (CPU, cores, power plan, JDK). Timing criteria below refer to it.
Criteria that must hold everywhere are checked in `check` by counting work, not timing it.

### 1b. Scenarios

| Id | Scenario | Added in |
| --- | --- | --- |
| B1 | 32 tone voices (saw, pulse) | 1 |
| B2 | 32 mono sample voices at 1x | 1 |
| B3 | 32 stereo sample voices at 15.996x | 1 |
| B4 | 32 stereo sample voices at 16.000x | 1 |
| B5 | 8 audio sources, filters, feedback delay | 1 |
| B6 | B5 plus 2 reverbs | 5b |
| B7 | 32 sustained looped voices, and a variant with dense one-shots beside one loop | 4 |
| B8 | 1, 4 and 8 renderers on one thread publishing the B6+B7 graph together, plus a scheduled commit (current and pending both recovering), measured during recovery | 6 |
| B9 | Adversarial: 128 events per cycle (compiler maximum), 8 sources, 2 trigger sources with dense patterns, 32 looped stereo voices at 15.996x, 2 reverbs at 20 s | 4, then 5b |

**Pass criteria [W14].**

- B8 (reference machine): pooled p99 ≤ 5.33 ms and max ≤ 10.67 ms at 8 renderers.
- B9 (reference machine): pooled p99 ≤ 10.67 ms for one renderer; bytes per publish recorded
  and gated at the step-5b value + 10%.
- Everywhere (`check`): replay work per output frame across all renderers sharing a budget
  never exceeds `leases × 4` frames (counted, see step 6).

### 1c. Golden renders [W4]

- Scenarios: saw and pulse tones; mono and stereo samples at 1x, 15.996x and 16x; reverse;
  slice; the synced-delay signal graph; and the Phase 3 fixtures from 0.5. About 0.5 s each.
- Stored as float64 (`.f64`, about 190 KB per stereo scenario) under
  `core-engine/src/test/resources/golden/stage3/`.
- Gate in `check`: max absolute difference ≤ 1e-7. `Math.sin`, `Math.cos`, `Math.tanh` and
  `Math.exp` may use CPU intrinsics that differ by an ulp across machines, so exact
  comparison would fail on other hardware.
- For P1, P2 and P5, the author also runs the goldens with `-Dgroove.goldenExact=true` on the
  machine that captured them, which requires bit-for-bit equality.
- Only P3 (step 7) re-captures goldens, in its own commit.

### 1d. Resampler asserts

`ResamplerTests` asserts `rejection >= 50` (`ResamplerTests.java:24`) and `relative < .003`
(`:40`); the 68.21 dB and 0.05141% figures are only printed. Tighten to `>= 68` dB and
`< .00052`.

### 1e. Allocation checks cannot skip silently [W13]

Allocation checks skip when measurement is unsupported (`SignalTests.java:556`,
`FilterModeTests.java:55`, `PitchTests.java:85`, `DspTests.java:199`). Replace all with one
helper that fails when `isThreadAllocatedMemorySupported()` is false, unless
`-Dgroove.allowNoAllocCheck=true` is set, in which case it prints
`"allocation check skipped"`. Standard OpenJDK builds report `java.vm.name` as
"OpenJDK 64-Bit Server VM", so a VM-name check would never fire.

### 1f. `check` runtime budget [W17]

Every suite prints its wall time. Phase 4 may add at most 60 s to `./gradlew :core-engine:check`
on the reference machine. Long renders (20 s decays, long feedback checks) run in `perfBench`
or an opt-in `longTest` task, not in `check`, and are listed as such below.

---

## 2. Output-preserving performance fixes

Gate: goldens within 1e-7 in `check`, bit-exact with `groove.goldenExact` on the capture
machine, zero allocation.

| Id | Where | Problem | Fix |
| --- | --- | --- | --- |
| P1 | `VoiceDsp.add` | Mono samples run the whole resampling kernel twice per frame on identical data | Interpolate once and apply both pan gains; for stereo, share kernel setup across channels |
| P2 | `SamplePlayback.value` | `cos`/`sin` of pan and a duration division on every call | Precompute per voice in `VoiceDsp.start` |
| P5 | `SignalRuntime.p()` | `Map<String,Double>` lookup and unboxing per node per frame | Resolve params into `double[]` when the runtime is built |

---

## 3. Scheduler history, pruning and lifetimes

### 3a. One lifetime function [W10]

`PreparedSamples.lifetimeSeconds(SampleVoice voice, double eventCycles, double secondsPerCycle)`
is the single source of truth:

- missing asset: −1 (silent, as today)
- one-shot: prepared duration / pitchRatio
- looped (step 4): `min(eventCycles · secondsPerCycle, 40)`

Every place that decides duration calls it:

| Place | Today |
| --- | --- |
| `Score.java:53` | `start + ceil(sample.duration() · rate)` |
| `LiveRenderer` scheduler path (`eventDuration`, `:424`) | `sample.duration()` |
| `LiveRenderer` no-scheduler path (`:346-356`, `:379`) | `eventDuration` without an `Entry` |
| `Renderer.java:80` | `note.sample().duration()` |
| `SamplePlayback.value` | returns 0 past PCM duration with its own envelope |

### 3b. Cached durations in the window

- `LookaheadScheduler`'s audio-source constructor takes a `ToDoubleFunction<Event>` backed by
  `lifetimeSeconds`, and stores `durationSeconds` in each `Entry`.
- The trigger constructor passes `e -> Double.NaN`; an assert guards against reading it.
- `LiveRenderer` reads `entry.durationSeconds` instead of hashing `SampleVoice` per entry per
  frame.
- `Timeline.withSamples` already rebuilds `Program`s with one shared `PreparedSamples`, so
  current and pending programs see identical durations.

### 3c. Per-source history [R4]

Today history is `max(pcm.duration()/.25)` over the whole bank (`LiveRenderer.java:51-53`),
and the bank is shared by every source, so one long asset stretches every source's window.

- Each source `Program` sizes history from its own `plan.sampleVoices()`:
  `max(lifetimeSeconds)` over its one-shot voices. Tone-only sources get 0.
- Looped voices add nothing to history. A multi-cycle event is returned by every bucket its
  `whole` overlaps (`Pattern.java:160-170` clips `part` and keeps `whole`) and merged by `Key`,
  so it stays in the window for as long as it spans, and the looped prune rule keeps it until
  `whole.end`.
- The seconds constructor gains the same ring-capacity assert as the cycle constructor. At the
  existing 40 s cap and 300 BPM: `ceil(40 · 300/240) + 1 = 51` history cycles (including the
  base−1 bucket) plus 4 lookahead = 55 buckets ≤ 64 [N2].

### 3d. Prune rules for audio sources [B5 from review 1]

`LookaheadScheduler.java:80` prunes only events without a sample today.

- tones: unchanged, `whole.end ≤ base − 1`
- one-shot samples: `whole.start + durationSeconds · bpm/240 ≤ base − 1`
- looped samples: `whole.end ≤ base − 1` (release sits inside the event)
- trigger sources: out of scope, unchanged (they keep every event back
  `MAX_ENVELOPE_TAIL_CYCLES` for envelope release). Their cost is measured by B9.

### 3e. Tests

- After 60 cycles of a dense one-shot pattern, the window holds only still-sounding entries.
- One source with a 10 s asset does not change the history of a tone source in the same graph.
- Late join and seek still start every still-sounding one-shot; goldens unchanged.
- A 1 s looped asset on a 30-cycle event at 300 BPM, joined late at cycle 25, keeps sounding
  with per-source history (step 4).

---

## 4. Sustained sample loops

### 4a. Parameters

| Param | Range | Default when loop is switched on | Meaning |
| --- | --- | --- | --- |
| `loop` | 0 or 1 | 0 | 0 keeps one-shot behaviour |
| `loopStart` | 0..1 | 0.25 | Fraction of the prepared region, in playback order |
| `loopEnd` | 0..1 | 0.9 | Fraction of the prepared region, in playback order |
| `loopFadeMs` | 0..500 | 20 | Crossfade length at the seam |

- Server-side compile rule: `loopStart < loopEnd`.
- Param cap: `GENERATOR_SAMPLE` already uses all 8 allowed keys (`GraphCompiler.java:55`,
  `GraphJson.java:113`). Raise it to 12 for `GENERATOR_SAMPLE` in both places.
- Editor keys [W11]: `EditorState` adds `loopStart`, `loopEnd` and `loopFadeMs` with defaults
  when `loop` becomes 1, and removes them when `loop` becomes 0, so an un-looped patch has the
  same keys as in Phase 3.
- Canonicalisation: with `loop = 0` the compiler ignores the loop keys and builds a
  `SampleVoice` with default loop fields, so equality and hashing match Phase 3.

### 4b. Loop geometry, one pure function [W8, W9]

```
LoopGeometry.resolve(SampleData prepared, SampleVoice voice, int outputRate)
```

Pure and deterministic, called on the control thread. `PreparedSamples` takes an output rate
(48,000 live; `Score` passes its transport rate) and stores the result in `SamplePlayback`.
Assets are keyed by sha256, so every client resolves the same geometry.

All positions are in prepared frames; `N` is the prepared region length.

```
step0  = assetRate · pitchRatio / outputRate
level  = halvings while step ≥ 2          (step0 / 2^level < 2, level ≤ 4)
band   = ceil(max(0, step0 / 2^level − 1) · 64)
radius = ceil(24 · (1 + band/64))
R      = (radius + 1) · 2^level + 48 · (2^level − 1)
```

The second term is prefilter reach across levels (48 taps at each halving's input rate).
Worst cases at 48 kHz output: step 16 gives R = 1,120; step 15.996 gives R = 728.

Clamping, in this order:

```
Ls  = floor(loopStart · N)
Le  = floor(loopEnd · N)
X'  = round(loopFadeMs / 1000 · assetRate · pitchRatio)     requested fade, prepared frames
X'  = min(X', floor((Le − Ls) / 2))                          fade shrinks first
Ls' = max(Ls, R + X')
Le' = min(Le, N − R)
P   = Le' − Ls'
```

- If `P < 128` (including `Le' ≤ Ls'`), the voice plays one-shot with `fallback = true`, `X = 0`.
- Otherwise `X = min(X', floor(P / 2))`, and every read of `S(u)` and `S(u − P)`, including
  kernel and prefilter footprint, lies inside real prepared audio.

Playback:

```
s = age · assetRate · pitchRatio
u = s < Le' ? s : Ls' + (s − Ls') − P · floor((s − Ls') / P)

if X > 0 and Le' − X ≤ u < Le':
    w   = (u − (Le' − X)) / X
    out = a(w) · S(u) + b(w) · S(u − P)
else:
    out = S(u)
```

Continuity: as `u → Le'`, `w → 1`, so the output tends to `S(Le' − P) = S(Ls')`, where the next
period starts with `w = 0`.

Fade gains, with ρ measured in `resolve` over at most 8,192 evenly spaced frame pairs of the two
fade windows (bounding control-thread cost on long fades):

```
x = S[Le'−X, Le'),  y = S[Ls'−X, Ls')
ρ = Σxy / sqrt(Σx² · Σy²)       ρ = 0 if X = 0 or either sum is 0
ρ = max(ρ, −0.5)

c = cos(πw/2),  d = sin(πw/2),  n = sqrt(1 + 2ρcd)
a(w) = c / n,   b(w) = d / n
```

For unit-power inputs, output power is `a² + b² + 2ρab = (c² + d² + 2ρcd)/n² = 1`. `ρ = 1`
preserves amplitude; `ρ = 0` is equal-power; the clamp keeps `n ≥ sqrt(0.5)`.

### 4c. Lifetime and envelope

- Release sits inside the event, matching tones (`VoiceDsp.java:36`).
- Looped lifetime from `lifetimeSeconds` (3a), with a 1 ms attack and a 20 ms release ending at
  the lifetime.
- `VoiceDsp.start` and `add` receive the lifetime; `SamplePlayback.value` uses the geometry for
  looped voices.
- The anchor skip (`LiveRenderer.java:341`) applies only to one-shot samples.

### 4d. Scheduler history

No change for loops [R4]; see 3c.

### 4e. Tempo changes

Age is computed from cycles at the current program's BPM (`LiveRenderer.java:440`), so a looped
voice sustaining across a tempo change jumps position. Every listener hears the same jump, masked
by the 240-frame program crossfade. A test bounds the discontinuity to that window. Age from onset
nanoseconds is follow-up work.

### 4f. Warnings and counters [W9, W15]

- The editing client runs `LoopGeometry.resolve` on its locally loaded asset for each voice
  compiled from each `sample` node (the editor maps nodes to compiled voices locally, including
  slices), and shows `"Loop too short for this sample; playing one-shot"` or
  `"Loop points moved inward to fit"` on that node. No network packet is involved.
- Listener clients never show UI. Their renderers count `loopFallbacks` and `loopClamps` next to
  `historyRecoveries`, and log once per program at INFO.
- Server compile validates ranges and `loopStart < loopEnd` only; it has no asset lengths.

### 4g. Other edge cases

- Voice stealing: sustained voices hold the 32 slots longer.
- `SampleVoice` gains loop fields: 21 constructor call sites. Loop variants count toward
  `PreparedSamples.MAX_VOICES` (128). Loop fields stay out of `RegionKey`, so no PCM is duplicated.

### 4h. Tests

Comparisons against a reference voice start after the 240-frame join fade plus 100 ms of filter
settling, with both renderers driven from the same absolute nanos [N7].

- Goldens unchanged with `loop = 0`; a looped-then-unlooped node has Phase 3's keys.
- 12-key sample node round-trips through compile, `GraphJson.decode`, `decodeDraft`,
  `SessionStore` and `MusicPackets` encode/decode.
- Geometry unit tests: `R` for steps 1, 2, 15.996, 16 at output rates 8,000 and 48,000;
  clamp order (a 500 ms fade on a 0.25 s asset shrinks the fade and stays looped); `P = 128`
  loops, `P = 127` falls back.
- Seam, hand-built cases on sines at 48 kHz, 20 ms fade [W12]:
  - 90° phase mismatch: max |Δ| over the 2X seam window ≤ 1.25 × max |Δ| over the loop body
  - same case with `loopFadeMs = 0`: that measure > 2 × body (proves the test detects clicks)
  - 0° mismatch (ρ ≈ 1): seam amplitude within ±0.5 dB of body
  - 180° mismatch (ρ clamped to −0.5): output finite, seam dip bounded above −6 dB
  - fixed-seed white noise (ρ ≈ 0): seam-window RMS within ±1.5 dB of body RMS
- Seam matrix, each cell rendering at most 3 loop periods and asserting finite output, peak ≤ 1.5 ×
  source peak and, for fades > 0, the ≤ 1.25 × criterion [W12, W17]:
  - source: fixed-seed noise and a 997 Hz sine factory-style fixture
  - reverse × slice × pitchRatio {0.25, 1, 4} × asset rate {44.1k, 48k}
  - (loopStart, loopEnd) ∈ {(0, 0.5), (0, 1), (0.5, 1)}; equal pairs are expected to be rejected
  - loopFadeMs {0, 20, 500} plus one 15.996x case
- Late join at 30 s into a 40 s looped sustain on a 1 s asset matches a voice that played from the
  start within 1e-9 (cutoff 20000 so the voice filter is a pass-through).
- Tempo 30 → 300 BPM mid-sustain then resync: voice active, `scheduleMisses == 0`, discontinuity
  confined to the crossfade.
- Stealing: 40 sustained looped voices plus a new onset: the oldest is stolen; the steal fade has
  no step larger than the body's max |Δ|.
- Offline/live parity with loops; zero allocation while sustaining; counters increment on fallback.

---

## 5. Reverb (Dattorro plate)

Chosen over Freeverb (8 combs and 4 allpasses per channel) and a feedback delay network (needs a
mixing matrix and more tuning): mono in, stereo out, fixed memory, few parameters, and a modulated
tank that avoids metallic ringing.

`reverb` and `decaySeconds` are a saved contract: the node is always this plate, `decaySeconds`
is its nominal T60, and the loudness contract above holds. Other algorithms get new node types
(for example `reverb_room`), never a mode param or an in-place retune [N5].

### 5a. Tracer bullet

- `NodeType.REVERB`: `in` AUDIO, `out` AUDIO, `isSignalNode()` true (`GraphJson` resolves signal
  node types by name, `GraphJson.java:55-58`).
- `NodeParam` keys and final ranges (5c); `SignalGraph` allowed params, ranges and the 2-reverb
  limit, with its error next to the delay budget error: `"At most 2 reverbs per graph"`.
- Loop-gain check (5e).
- `SignalRuntime`: constructor, routing switch case, `reset()`.
- `LiveRenderer.java:120`: REVERB added to the stateful set.
- Cycle cut rules stay DELAY-only (`SignalGraph.java:168`, `EditorState.java:206,216`).
- Editor: palette entry, defaults, steps, clamps, `RotaryKnob` labels, and the 2-reverb limit and
  loop-gain check mirrored in `EditorState`, so the editor never allows what the server rejects.
- Exhaustiveness test over every `NodeType`: `outputPorts`, `inputPorts`, `isSignalNode`,
  `SignalGraph.validateParams`, loop-gain bounds and the `SignalRuntime` routing switch.
- DSP for 5a: predelay plus one damped feedback comb, already meeting the loudness contract.
- Exit: a graph with a reverb plays in game through `/groove`, survives save and reload, and
  round-trips through `GraphJson`.

### 5b. `Reverb` class

All state lives in a final `Reverb` class (like `Biquad`): the constructor allocates;
`process(in, absoluteFrame, outLR)` and `reset()` never allocate. `SignalRuntime` holds a
`Reverb[]`. The denormal snap is implemented here, not later [N4]. Unit-tested directly.

### 5c. Parameters

The node outputs wet signal only, like `delay`.

| Param | Range | Default |
| --- | --- | --- |
| `decaySeconds` | 0.1..20 | 1.8 |
| `dampingHz` | 200..20000 | 6000 |
| `bandwidthHz` | 200..20000 | 12000 |
| `preDelayMs` | 0..500 | 0 |

### 5d. Topology, constants, gain

Lengths are published at 29,761 Hz; multiply by `48000 / 29761 = 1.61285` and round.

| Part | Lengths at 29,761 Hz | Coefficients |
| --- | --- | --- |
| Input diffusers (series allpass) | 142, 107, 379, 277 | 0.75, 0.75, 0.625, 0.625 |
| Tank half A | modulated allpass 672, delay 4453, allpass 1800, delay 3720 | decay diffusion 1 = 0.70, decay diffusion 2 = 0.50 |
| Tank half B | modulated allpass 908, delay 4217, allpass 2656, delay 3163 | same |
| Modulation excursion | ±16 | ±26 at 48 kHz |
| Left output taps | +266, +2974, −1913, +1996, −1990, −187, −1066 | |
| Right output taps | +353, +3627, −1228, +2673, −2111, −335, −121 | |

Memory: about 36,330 frames at 48 kHz (mono doubles, about 290 KB) plus up to 24,000 predelay
frames (about 190 KB).

**Decay.** The tank totals 21,589 frames at 29,761 Hz: a 0.725 s figure-eight loop with 4 decay
multipliers, one every `τ = 0.181 s`.

```
g = 10^(−3 · τ / T60)
```

`T60 = 20 s` gives `g = 0.939`; `g = 0.5` gives about 1.81 s. Nominal; tests measure it.

**Gain [W3, N1].**

- Input gain `gIn = 1 − g`. Each tank half has 2 decay multipliers and input enters both halves,
  so steady-state tank level at resonance is about `gIn / (1 − g²) = 1/(1 + g)` times input:
  0.52 at 20 s, far from the ±8 guard.
- Output gain `kOut(g) = 10^(−1/20) / p(g)`, where `p(g)` is a cubic fit of the unscaled tap peak
  (modulation off, `dampingHz = bandwidthHz = 20000`). Measured at implementation: several output
  taps read tank delays before a decay multiplier, so the unscaled peak stays nearly flat (3.00 at
  0.1 s, 3.32 at 20 s) instead of scaling as `1/(1 + g)`. The earlier `k0 · (1 + g)` missed the
  contract by −3 dB at 0.1 s and +2.6 dB at 20 s. The fit is within 0.05 dB over 0.1..20 s.
- Remaining loudness trend, documented: broadband tail energy still falls for longer decays, by
  about 9 dB from 1 s to 20 s. `mix_bus` gain is capped at 1 (`SignalGraph.java:212`) and cannot
  make up level; the contract fixes peak response, not tail energy. Users set dry level lower
  instead.
- `bounded(±8)` stays as a NaN/Inf guard only, counted as `reverbGuardHits` [W15].

**Filters.**

```
damping:    y[n] = (1 − d)·x[n] + d·y[n−1],   d = exp(−2π·dampingHz / fs)
bandwidth:  y[n] = b·x[n] + (1 − b)·y[n−1],   b = 1 − exp(−2π·bandwidthHz / fs)
```

Cutoffs clamp to `0.45 · fs`. Input is `(L + R)/2`.

**Modulation.** `phase = floorMod(absoluteFrame, periodFrames) / periodFrames`, computed in `long`
before converting to double; evaluated once per 64-frame control block. Hermite interpolation for
modulated reads.

### 5e. Loop-gain check [R3]

`mix_bus` sums its inputs before a gain of at most 1 (`SignalRuntime.java:100-105`), so a dry-plus-
wet loop such as `mix_bus(delay, reverb(delay)) → delay` has a transfer of `1 + R(z)`, peaking near
1.89. A resonant filter in the loop does the same. A saturated loop depends on its history, so
listeners who joined at different times would never converge.

Rule, applied at compile time and mirrored in the editor, only to strongly connected components
that contain a REVERB. Loops without a reverb keep today's behaviour, since existing patches and
tests use unity-gain feedback (`LimiterTests.java:104,123`). That pre-existing risk is documented,
not changed.

1. Cut edges entering DELAY nodes (as today). For each DELAY `j` in the component, propagate a
   worst-case amplitude bound `A` through the acyclic remainder starting from `A(j out) = 1`:
   - `mix_bus`: `gainBound · Σ A(inputs)`, where `gainBound` is the gain param, or 1 when the gain
     is modulated; inputs from outside the component contribute 0
   - `filter`: `peak(mode, Q) · A(in)`, where `peak` is `1` for band-pass and notch, and for
     low-pass and high-pass `Q / sqrt(1 − 1/(4Q²))` when `Q > 1/sqrt(2)`, else 1, times 1.05
     margin for cutoff warping; `Q` is the node's param (resonance is not modulatable)
   - `reverb`: 0.95 · `A(in)` (peak −1 dB, with margin for modulation; the modulated stability test
     in 5g gates this at ≤ −0.5 dB)
   - `delay` output: 1 for the source delay, 0 for other delays (they start their own row)
2. `M[i][j]` = the bound arriving at DELAY `i`'s input from DELAY `j`'s output.
3. Reject if any row sum `Σ_j M[i][j] > 0.89` (−1 dB), with
   `"Feedback loop through reverb can exceed unity gain (bound <x>)"`.

Row sums ≤ 0.89 bound the loop's infinity-norm gain below 1 (small-gain theorem), so any
difference between two listeners' states shrinks by at least 11% per trip around the shortest
loop. Convergence time is therefore about `trips to −60 dB × loop length`, not T60 [R3, W8 from
review 2].

### 5f. Known limitations, documented

- Replay covers at most 1 s (`HISTORY_FRAMES`). Every publish (including knob drags and headphone
  preview) and every late join rebuilds only that much tail; transport stop cuts tails. Two
  speakers that joined at different times differ until the part of the tail that was not replayed
  has decayed: at most T60 for a reverb outside a loop, and the loop convergence time above for a
  reverb inside one. Carrying effect state across unchanged republishes is follow-up work.
- Memory is per `VoiceProgram` per renderer, not per graph: up to 3.07 MB of delay plus about 1 MB
  for two reverbs per `SignalRuntime`, times current, pending and previous programs, times the
  client's renderers. `perfBench` records bytes per publish; pooling runtime buffers is follow-up
  work if GC pauses show in B8.
- Pre-existing: delay feedback loops without a reverb can still saturate at ±8, which makes late
  joiners diverge. Unchanged by Phase 4.

### 5g. Tests

In `check`:

- Finite and in bounds under 2 s of full-scale noise; `reverbGuardHits == 0`.
- **T60:** `dampingHz = bandwidthHz = 20000`, predelay 0, `decaySeconds ∈ {1, 2.5}`. Unit impulse,
  render `2·T60 + 1 s`, mono sum. Schroeder backward-integrated energy in dB, least-squares fit
  from −5 to −35 dB, `T60 = 2·T30`. Gate ±20%. Below 0.5 s is documented as uncalibrated.
- **Peak gain [W6]:** modulation off, `decaySeconds ∈ {0.1, 0.5, 1, 2.5}`. Render the IR until it is
  below −120 dB and zero-pad to 8x with no window (a window starting at zero removes the early IR,
  which carries the peak); then refine the 10 largest local maxima with stepped steady-state sines at ±0.5 bin spacing. Gate: max = −1 dB ± 0.2 dB.
- **Modulated stability [W6]:** modulation on, `decaySeconds = 2.5`: steady-state gain for sines at
  the 10 peak frequencies found above ≤ −0.5 dB.
- **Stereo width:** zero-lag |ρ| between L and R of the IR from 50 ms to T60 at `decaySeconds = 2.5`,
  < 0.5 with modulation on and off.
- **Loop-gain check [R3]:** rejected: `mix_bus(delay, reverb(delay)) → delay` at gain 1; a Q = 20
  low-pass plus reverb in one delay loop. Accepted: `delay → reverb → mix_bus(gain 0.5) → delay`.
  Existing unity-gain loops without reverb still compile. The editor rejects the same graphs.
- **Feedback convergence [R3]:** the accepted graph above at `decaySeconds = 2.5`, with delay lengths
  {64 frames, 0.5 s, 21,589 × 1.61285 frames (a tank loop length)}. Measured against peak output
  while input was present: after input stops, energy in successive 1 s windows strictly falls from
  2 s on, and falls below −60 dB within the bound computed from the row sum. `reverbGuardHits == 0`.
  Two runtimes started 1 s apart with different histories differ by < 1e-6 after that bound.
- **Headroom:** full-scale sine at the lowest tank resonance, `decaySeconds = 2.5`: tank state max
  < 1.
- NaN, +Inf, −Inf inputs followed by normal input: output finite, recovers within `decaySeconds`.
- `reset()` mid-tail: next 1 s with zero input is exactly 0.
- `preDelayMs` 0 versus 500: IR onset at frame 0 versus 24,000 ± interpolation offset.
- Param bounds: 0.1, 20, 200, 20000 accepted; 0.09, 20.1, 199, 20001 rejected with a range message.
- Block independence: chunk sizes 1, 64, 512 give identical output.
- Offline/live parity; two runtimes with identical input give identical output.
- Late join at `decaySeconds = 0.5`: after history replay catches the clock (about 16,000 frames for
  1 s of history), the 240-frame fade and 100 ms, within 1e-3 of a continuous run [N7].
- Graph limit: exactly 2 accepted, 3 rejected, through compile, `decodeDraft` and the editor.
- `GraphJson` round trip; a partly set reverb passes `decodeDraft`.
- Late join triggers `historyRecoveries` for a graph whose only stateful node is a reverb.
- Zero allocation in `process`.
- Denormals, reverb part [W7]: see step 7.

In `longTest` (opt-in) [W17]: T60 at 5 and 20 s with monotonicity (20 > 5 > 1), peak gain at 5, 10
and 20 s, feedback convergence at 20 s, 10 s noise soak.

---

## 6. Replay leasing [R2]

Adding REVERB to the stateful set makes replayed joins the common case: each recovery runs up to 4
extra frames per output frame (`REPLAY_PER_FRAME`) until it catches the moving clock. It gains 3
frames per output frame, so 48,000 history frames take `ceil(48000/3) = 16,000` output frames
(about 333 ms). An editor commit publishes to every linked emitter, all rendering on one sound thread,
each stream reading its own chunk (`GrooveAudioStream.java:71`).

A shared per-frame budget cannot work: a recovery that gets ≤ 1 replay frame per output frame never
catches up (`LiveRenderer.java:322-326`), and there is no shared block to reset on. Leasing instead:

1. **`ReplayBudget`, injected.** Passed to `LiveRenderer`'s constructor. The client creates one per
   sound thread; tests create their own. Default for existing constructors: a private unlimited
   budget, preserving today's behaviour.
2. **Leases, not fractions.** At most `k = 2` programs on a budget recover at once, each at the full 4
   replay frames per output frame. Programs wanting recovery queue FIFO. Current and pending programs
   of a scheduled commit are separate requests.
3. **Waiting is not recovering.** A queued program keeps capturing fresh windows (so its window never
   goes stale and `markMissed` cannot loop), outputs silence, and `ready()` is false. Recovery start
   and history length are computed when the lease is granted. The lease is released when
   `recovering` becomes false, on reset, or when the program is dropped. When an emitter is stopped
   or closed (e.g. `GrooveAudioStream.close()` or `MusicClient.closeEmitter`), the stream invokes a
   cleanup hook (`renderer.reset()`) that unregisters any queued recovery request and immediately
   releases any active lease back to `ReplayBudget`, preventing lease leakage [W3].
4. **Previous stays audible.** `previous` points at the last program for which `ready()` was true, not
   at the last observed program. A republish while a program waits or recovers therefore keeps the
   last ready audio playing through the crossfade instead of silence.
5. **Worst-case wait**, computed and documented: with `n` queued programs, the last starts after
   `ceil(n/k) − 1` recoveries of at most 16,000 frames each. 8 renderers with 2 scheduled commits
   (`n = 10`): at most 4 × 333 ms ≈ 1.33 s before the last recovery starts.
6. **Counters [W15]:** `replayQueuedFrames` (frames spent waiting for a lease) and `replayLeases`,
   next to `historyRecoveries`.

Tests in `check`:

- One renderer: recovery completes within `16,000 + 240` output frames and ends with
  `recovering == false` [W5].
- 8 renderers on one budget, all publishing the same frame, 2 of them with a scheduled commit (so
  `n = 10` recovering programs): every program reaches `recovering == false` within
  `ceil(n/k) · 16,000 + 240 = 80,240` output frames; total replay
  work per output frame never exceeds `2 · 4` (counted); no program's window is missed while queued.
- A republish while queued keeps the previous ready program audible (RMS > −40 dBFS through the wait).

Benchmark: B8 against its pass criteria (1b).

---

## 7. Denormals and resampler level choice

### P6. Denormals in delay and filter state [W7, N4]

Java cannot enable flush-to-zero; subnormal doubles are 10-100x slower on x86. The reverb snap
already exists from 5b. Add the same snap (`|x| < 1e-30` → 0, counted in a package-private
`snappedWrites` counter) to delay feedback writes and `Biquad` state (`x1, x2, y1, y2`) in `VoiceDsp`
and `SignalRuntime` filters.

Tests, each with a positive control that `snappedWrites > 0`:

- Reverb at `decaySeconds = 0.1`: impulse, then 10 s of silence (crossing 1e-30 is −600 dB, about
  10 · T60).
- Reverb at `decaySeconds` 1 and 20: waiting would take 10 s and 200 s, so seed all state at 1e-25
  through the accessor and run 5 s.
- Delay feedback with gain 0.5 and a resonant filter graph: seeded the same way.
- After each: every state value is finite and none satisfies `0 < |x| < Double.MIN_NORMAL`.
- Goldens stay within 1e-7.

### P3. Resampler level near powers of two (own commit) [W16]

A step just under a power of two (15.996x) stays on the higher level with band 64: 97 taps × 2 phase
rows. Move to the next level when the step is within 2% below a power of two.

- Gate (spectral, not sample difference): at steps 15.7x, 15.996x and 16x, passband magnitude within
  ±0.1 dB and alias rejection ≥ 68 dB, plus the tightened `ResamplerTests`.
- Re-capture goldens in the same commit. Print and record the old-versus-new sample difference, not
  gated.
- Record before/after spectra in the commit's docs update. P3 is the only intentional output change in
  Phase 4.

---

## 8. P4: voice selection

Only if B7, B8 or B9 still show the per-frame scan after steps 3 and 6.

A cached selection must be invalidated by:

- window identity change (`capture()` swaps `program.window` every block)
- `reset`, `markMissed`, resync count change
- `now < lastNow` (seek or replay rewind), recovery start or finish, lease grant
- program change
- crossing the next boundary, found by evaluating the same predicate as today,
  `(cycles − onset) · secondsPerCycle < duration`

Simplest first: recompute at most once per 64-frame control block, or immediately on any invalidation.

The current selection stays as a package-private strategy in main code. A randomised differential test
runs both over patterns with window swaps, pruning, more than 32 active events, resync, seek, leases and
recovery, and asserts identical voice starts and stops per frame.

---

## 9. Demo audio [R5]

Existing demo graphs are test fixtures and stay unchanged: `SignalDemo.multipleSources()`
(`BackendTests.java:508,553`, `AudioSmokeTest.java:56`) and `FactorySamples.demo()` (`PortTests`,
`SampleTests`, `BackendTests`, `SpeakerLinkTests`, `AudioSmokeTest`).

`alternate` cannot make a silent final cycle: it picks child `c mod n` and advances each child one
local cycle per rotation (`Pattern.java:111-128`), so two children silence every other cycle, and eight
children freeze every music child on its own cycle 0. The offline demo therefore renders finite scores
and lets the effects ring out.

### `renderSampleDemo` (`SampleDemo`, `core-engine/build/sample-demo.wav`)

- Four `Score`s compiled from the existing Java patterns for **7 cycles** at 120 BPM (14 s), each a
  separate source so effects apply per source:

| Source | Content | Route |
| --- | --- | --- |
| drums | sliced swung kit with the reversed bar (`alternate` of 4 bars, cycle 3 reversed) | short reverb (`decaySeconds` 0.6, `dampingHz` 8000) mixed low under dry |
| bass | pulse bass | dry |
| lead | swung pulse lead | synced 1/8 feedback delay (mix gain 0.55), then long reverb (`decaySeconds` 2.5, `dampingHz` 5000, `preDelayMs` 20) |
| pad | looped slice of a factory sample, whole-cycle events | long reverb |

- One v3 signal graph with 4 `audio_render` placeholder sources (as `SampleDemo` does today with its
  delay graph) and both reverbs. It passes the loop-gain check: the long reverb sits after the delay
  loop, not inside it.
- New `Demo.write(Path, Score[] sources, SignalGraph graph, int totalFrames)` feeds each score's stereo
  frame into `SignalRuntime.process(double[][] sources, double[] stereo, long nanos)` (which already
  exists) and keeps processing silence after the scores end.
- `totalFrames` = 16 s: 14 s of music, then a 2 s tail.

### `renderSignalDemo` and in-game demos

- New `SignalDemo.reverbSources()`: `multipleSources()` with a reverb after the bass filter/delay mix
  and before `master`; the lead stays dry. The reverb is outside the delay loop.
- New `FactorySamples.reverbDemo()`: the kit graph as v3 with `audio_render` and a short room reverb.
- `renderSignalDemo` renders `reverbSources()` through `LiveRenderer` for 8 s, as today.
- `/groove signal-demo` and `/groove sample-demo` (`MusicServer.java:196-197`) switch to the reverb
  variants. In game, patterns loop forever, so there is no tail; that is expected.

### Checks in `check`

- Both demos: every sample finite, peak ≤ 1.0, and both graphs pass compile and the loop-gain check.
- `SampleDemo` only [W14 from review 2]: RMS > −40 dBFS in each of cycles 0-6 (so the reversed bar and
  every source played), tail RMS over 14.0-14.2 s > −50 dBFS, and RMS over 15.8-16.0 s below it.
- `SignalDemo` only: RMS > −40 dBFS in every 1 s window.

---

## 10. Docs

- `ENGINE-UPGRADE-STAGES.md`: Stage 4 usage (params, formulas, loudness contract, loop-gain rule, known
  limitations from 4e and 5f), status row, reference machine and before/after performance table.
- `PHASE-2-SIGNALS.md`: `reverb` in the node table; loop-gain rule; replay leasing and memory
  limitations.
- `BACKEND-USAGE.md`: protocol mismatch messages, unreadable projects, `.bak`, and the downgrade
  warning.
- `ENGINE-EVOLUTION.md`: P3 level change and the tightened resampler contract.
- `FUTURE-WORK.md`: Stage 4 under recently landed; follow-ups (carry effect state across republish, age
  from onset nanoseconds, runtime buffer pooling, loop-gain check for non-reverb loops).
- Fold this plan into the stages doc when done.

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
- [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/) (biquad peak gain used in the loop-gain bound)
