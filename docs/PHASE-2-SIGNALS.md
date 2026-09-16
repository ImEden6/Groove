# Phase 2: deterministic modulation and delayed audio routing

V3 now supports live signal graphs with typed modulation, trigger and stereo audio
connections. Run `/groove signal-demo`, then `/groove play` after the queued change
applies, to hear a shared-clock filter sweep with feedback echoes and an independent
dry lead. Existing pattern
graphs and their v1/v2 serialization continue to work unchanged.

## Nodes and sockets

| JSON node type | Inputs | Outputs | Parameters and defaults |
| --- | --- | --- | --- |
| `audio_render` | `in`: PATTERN | `out`: AUDIO | None |
| `trigger_render` | `in`: PATTERN | `out`: TRIGGER | None |
| `lfo` | None | `out`: MOD_FLOAT | `rate=1` (.001–40); `sync=0` (0=Hz, 1=cycles); `wave=0` (sine, triangle, square, saw: 0–3) |
| `step_sequence` | None | `out`: MOD_FLOAT; `trigger`: TRIGGER | `steps=4` (1–8); `rate=1` (.125–16 sequences/cycle); `gate=.5` (.001–1); `value0` through `value7` (-1–1, engine default 0) |
| `envelope` | `trigger`: TRIGGER | `out`: MOD_FLOAT | `attack=.01`, `decay=.1`, `release=.1` (0–8 cycles); `sustain=.5` (0–1); `mode=0` (ONE_SHOT=0, GATED=1) |
| `attenuverter` | `in`: MOD_FLOAT | `out`: MOD_FLOAT | `scale=1`, `offset=0` (each -20000–20000); result clamped to that range |
| `filter` | `in`: AUDIO; optional `cutoff`: MOD_FLOAT | `out`: AUDIO | `cutoffHz=20000` (20–20000); `resonanceQ=.70710678` (.1–20) |
| `delay` | `in`: AUDIO | `out`: AUDIO | `sync=0` (0=free frames, 1=tempo-synced); `frames=64` (integer 64–48000, at 48 kHz, used when `sync=0`); `division=2` (integer 0–7: 1/16, 1/8T, 1/8, 1/4T, 1/8D, 1/4, 1/4D, 1/2; used when `sync=1`) |
| `mix_bus` | `in`: AUDIO, 1–16 sources; optional `gain`: MOD_FLOAT | `out`: AUDIO | `gain=1` (0–1) |
| `output` | Either `in`: PATTERN or `audio`: AUDIO | None | Exactly one input across both sockets |

Graphs support one through eight independent `audio_render` sources. Connect each
source's pattern to its own render node, then route its stereo audio through separate
filters/delays or combine it with other sources at a mix bus. Shared upstream patterns
are allowed: two render nodes fed by the same pattern produce independent voices,
while one render node fanned out to several effects produces its audio once.
A connected control replaces the corresponding static cutoff/gain value;
values clamp to that parameter's range. Use an attenuverter to map bipolar LFO
output to cutoff Hz or a unipolar gain. Audio processing happens before the existing
final tanh limiter. Legacy offline `Score`/`Renderer` APIs remain pattern renderers;
signal graphs execute through `LiveRenderer`.

## Independent source pipelines

Each render node compiles its complete upstream pattern subgraph independently.
The compiler validates connectivity and delay-cut cycles across the entire graph
before splitting sources. The existing conservative 128-event budget applies to
the **sum** of source costs, including repeated rendering of shared patterns; the
64-node, 128-edge and nesting limits still apply. The eight-source cap bounds the
additional scheduler and voice storage rather than multiplying the event budget.

Each source owns a rolling scheduler, up to 32 active voices and 32 stealing tails,
and a reusable stereo frame in each renderer. Thus a dense source can steal its own
voices without silencing a different source. Sample data is shared through the
immutable sample bank; sample/filter playback state remains private. All sources
use the same transport and graph revision, including pending tempo changes.

`SignalGraph.sourceNodeId(index)` identifies source order (render nodes in graph
node order); `SignalGraph.triggerNodeId(index)` does the same for trigger sources.
`SignalRuntime.process(double[][] sources, LookaheadScheduler.Window[] triggers,
double[] output, long now)` maps each source frame and each trigger's prepared
window to its render/trigger node's position in the DSP arrays before evaluating
routing. Control indices and audio source/trigger ordinals are all separate. Two
narrower overloads remain: `process(double[][] sources, double[] output, long now)`
supplies no trigger windows (fine for graphs with none) and the original in-place
`process(double[], long)` remains valid for single-audio-source, no-trigger graphs;
both reject a graph shape they don't match instead of silently ignoring it.

`LoopPlan` retains a combined, onset-sorted first-cycle preview for inspection.
Playback does not use that combined pattern: the control worker prepares every
source scheduler, and the audio callback captures all source windows once per block.
If any source lacks coverage, the entire route is silent and records a schedule
miss; refill resets source voices and effect state together before recovery.
Publication, forward/backward seeks and resync apply to every source pipeline.

Every sequence step emits a trigger whole arc with duration `gate/(steps*rate)`
cycles, independent of its numeric value. These periodic trigger arcs are evaluated
analytically, without allocating event lists in audio callbacks. ONE_SHOT begins
release after attack plus decay; GATED begins release at the trigger's whole-arc
end, including when that end occurs during attack or decay. Release starts from
the level at that end.

`trigger_render` extends this to arbitrary patterns: any pattern subgraph (Euclid,
stacked patterns, sample onsets) can drive an `envelope`'s trigger input, not just
`step_sequence`. Each `trigger_render` compiles and schedules its upstream pattern
independently, the same way an `audio_render` source does, with its own rolling
scheduler bounded to a 24-cycle envelope lookback (the longest an envelope's
attack+decay+release can span), plus one extra history bucket for its advertised
previous-cycle coverage, rather than the sample-duration-based history an
audio source uses. Up to eight `trigger_render` sources are supported per graph,
sharing the same combined 128-event budget as `audio_render` sources.

Every control-block evaluation scans the trigger source's current window fresh —
it never carries voice state forward between calls, so it stays a pure function of
(window, cycle) like the rest of this section's control evaluation, preserving the
late-join/backward-seek determinism guaranteed below. Overlapping voices (a still-
releasing earlier trigger and a newer one) combine with **MAX**, not sum: an
`envelope`'s output stays within its documented 0–1 range regardless of how many
triggers overlap, so existing cutoff/gain mappings don't silently change meaning
under dense triggering. `step_sequence` uses the same MAX-overlapping-voices
semantics without scanning a window. Its envelope rises to a single peak and then
never increases, so only the two periodic voice ages bracketing that peak need
evaluation. Older releases therefore survive new onsets with constant-time work,
including in GATED mode and when attack, decay or release has zero duration.

## Shared time and control buffers

Hz LFOs use a server-assigned `birthNanos` field on their node. The server ignores
submitted birth stamps, preserves an existing ID's timestamp on unrelated edits,
and resets it at the scheduled apply time when rate or synchronization mode changes.
Waveform and downstream parameter changes preserve phase. Startup assigns fresh
timestamps in the new server clock domain; persisted monotonic timestamps are not
reused across server restarts. Cycle LFOs and sequences derive from transport cycles.
Tempo changes preserve cycle position; Hz modulation continues in wall-clock time.

Each renderer owns reusable 64-frame control buffers. Values are sampled at both
ends of absolute server-frame-aligned blocks and linearly interpolated, including
steps and square waves. This provides deterministic ramps rather than a
history-dependent smoother: a fresh client at the same time receives the same
control value. Discontinuities can begin ramping within the preceding control block.
The control graph is evaluated in dependency order with bounded node/edge counts.

## Feedback and state ownership

Compilation cuts audio edges **entering** delays and rejects any remaining cycle.
It checks every node, so a delay in one arm of a diamond cannot hide a bypass cycle.
Delay lengths have a 64-frame minimum and a combined 192000-frame budget (about 3 MiB
of stereo double storage per renderer/program). Free delays cap at 48000 frames each.
Synced delays are sized from the BPM and charged their 30 BPM length against the budget,
so a 1/2-note synced delay can use all 192000 frames on its own. See
[Stage 3](ENGINE-UPGRADE-STAGES.md#tempo-synced-delay-and-memory-budgeting).
Mix/filter output and feedback writes are bounded to ±8 to prevent runaway state;
this is intentional overload saturation, not a transparent limiter.

Each frame first reads all delay cells, evaluates the remaining routing in dependency
order, then writes all delay inputs. Delays therefore retain their exact sample count
across callback sizes. Programs can be shared by monitor and speaker renderers;
mutable filters, delay memory, cursors and control buffers are renderer-private.
Publication, resync, seek and recovery after a missed schedule rebuild an approximation
of recent effect state locally. For graphs containing a FILTER or DELAY, LiveRenderer
starts from cleared state and silently replays up to one second before the requested
position through all audio sources, trigger windows and routing. Replay is clipped to
the current revision's effective time and the common prepared-window coverage. The
immutable windows are retained during recovery; no pattern queries, allocations or
network snapshots are needed in the audio callback.

For each active program, an output frame performs at most four historical frames, catching a moving clock
in roughly one third of the initial lookback duration (up to about 333 ms at normal
playback speed), then fades in over 5 ms. The completion frame can also render one
current frame. An outgoing program continues during replacement recovery; a fresh
join or seek with no usable outgoing audio is silent until recovery completes.
`historyRecoveries()` and `historyFrames()` expose recovery starts and replay work.
Plain pattern and stateless signal graphs keep their immediate join behavior.

This is bounded approximation, not exact late-join equivalence: echoes older than
the available lookback, long delay chains, persistent feedback and previous graph or
tempo revisions are not reconstructed. A graph edit never replays the previous graph
through the new one. Exact authoritative snapshots remain a possible future refinement.
Recovery has a fixed extra-work bound, not a guarantee that every maximum-size graph
or number of simultaneous speakers meets the audio deadline.

## Editor and compatibility

Quick Spawn includes the new nodes. Named sockets have separate positions, hover
labels, wire hit tests and per-socket disconnection. Connections enforce signal
types, socket arity and delay-cut cycle rules. Inspector controls expose node
parameters and retain birth metadata during edits; the server remains authoritative.
The sequence palette defaults differ from the engine's omitted values to provide
an immediately useful four-step pattern (1, 0, .5, 0).

Graph JSON carries the optional birth timestamp; packet envelopes and session file
versions remain unchanged. Legacy snapshots still round-trip exactly. V3 signal
nodes cannot be opened by binaries that lack these nodes.

## Verification

`SignalTests` covers Hz and cycle LFO phase, late joins, unrelated/rate edits, server
birth-stamp authority, tempo changes, step values, both envelope modes, attenuation,
feedback impulse timing and channel isolation, invalid bounds, mismatched ports,
diamond bypass rejection, live chunk-size equivalence, seek/reset behavior and
zero warmed allocation for both signal DSP and the integrated live renderer.

Backend checks round-trip signal graphs through JSON, snapshots, submissions and
transactional persistence; they also exercise named-port editor connections,
disconnection, pointer targeting, feedback validation and undo metadata.
The audio smoke test switches from samples to modulation/feedback after 128 reads
using two independent render sources, and continues through at least 256 reads,
with worker preparation and an induced
underrun followed by explicit stream closure.

Multi-source tests additionally cover interleaved source/control node positions,
node-order reversal, isolated delay/filter branch references, duplicated shared
patterns, per-source voice stealing, source-count and combined event budgets,
sample-bank playback, scheduler rollover, late joins, missed-window recovery,
backward seeks, pending tempo revisions and allocation-free multi-source rendering.
Backend JSON, packet and transactional persistence checks use the two-source demo.

`trigger_render` tests cover stable trigger-ordinal mapping independent of node
order, live-renderer window preparation/coverage/resync propagation matching audio
sources, the eight-source cap, numeric parity against an equivalent periodic
`step_sequence` trigger, genuine voice overlap (a still-releasing earlier trigger
outweighing a newer, quieter one under MAX combine), late-join and backward-seek
determinism, and allocation-free rendering through the full live renderer.

Regressions also compare overlapping periodic and arbitrary triggers in ONE_SHOT
and GATED modes, including zero-duration stages and negative cycles; check the
oldest releasing trigger within cached backward coverage; enforce the ring's
history capacity; and reject missing trigger windows in both narrow overloads.

Effect-history regressions compare a late join against continuous playback during
a note-free echo tail through delay feedback and filtering. They also cover the
one-second cap, per-callback replay budget, repeated resync/backward seeks, outgoing
audio during publication and pending-revision recovery, deterministic multiple-source
lifecycle behavior, and zero warmed audio-thread allocations during recovery.

The 2026-09-14 trigger fixes and bounded effect recovery passed
`./gradlew.bat test :core-engine:check`, including warmed allocation checks during
recovery. The smoke fixture now checks effect recovery after both late publication
and an induced underrun. The attempted Minecraft run exited before its completion
marker, so that extended integration scenario is not yet validated; a successful
Gradle exit alone is not a smoke pass. The earlier result below is historical evidence.

Command: `./gradlew.bat test :core-engine:check runClient -PaudioSmoke`.

Validation on 2026-09-13 passed the engine and backend suites. The final Minecraft
two-source smoke test completed 261 reads, zero scheduler misses, one deliberately induced
underrun recovery, and explicit stream closure. The warmed signal and live
render allocation checks each measured zero bytes over 128000 frames. These tests
do not establish a worst-case CPU budget for every allowed graph or speaker count.
