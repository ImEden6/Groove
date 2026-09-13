# Phase 2: deterministic modulation and delayed audio routing

V3 now supports live signal graphs with typed modulation, trigger and stereo audio
connections. Run `/groove signal-demo`, then `/groove play` after the queued change
applies, to hear a shared-clock filter sweep with feedback echoes. Existing pattern
graphs and their v1/v2 serialization continue to work unchanged.

## Nodes and sockets

| JSON node type | Inputs | Outputs | Parameters and defaults |
| --- | --- | --- | --- |
| `audio_render` | `in`: PATTERN | `out`: AUDIO | None |
| `lfo` | None | `out`: MOD_FLOAT | `rate=1` (.001–40); `sync=0` (0=Hz, 1=cycles); `wave=0` (sine, triangle, square, saw: 0–3) |
| `step_sequence` | None | `out`: MOD_FLOAT; `trigger`: TRIGGER | `steps=4` (1–8); `rate=1` (.125–16 sequences/cycle); `gate=.5` (.001–1); `value0` through `value7` (-1–1, engine default 0) |
| `envelope` | `trigger`: TRIGGER | `out`: MOD_FLOAT | `attack=.01`, `decay=.1`, `release=.1` (0–8 cycles); `sustain=.5` (0–1); `mode=0` (ONE_SHOT=0, GATED=1) |
| `attenuverter` | `in`: MOD_FLOAT | `out`: MOD_FLOAT | `scale=1`, `offset=0` (each -20000–20000); result clamped to that range |
| `filter` | `in`: AUDIO; optional `cutoff`: MOD_FLOAT | `out`: AUDIO | `cutoffHz=20000` (20–20000); `resonanceQ=.70710678` (.1–20) |
| `delay` | `in`: AUDIO | `out`: AUDIO | `frames=64` (integer 64–48000, at 48 kHz) |
| `mix_bus` | `in`: AUDIO, 1–16 sources; optional `gain`: MOD_FLOAT | `out`: AUDIO | `gain=1` (0–1) |
| `output` | Either `in`: PATTERN or `audio`: AUDIO | None | Exactly one input across both sockets |

This implementation supports one `audio_render` source per graph. Stack independent
patterns upstream, then fan its stereo audio out to multiple filters, buses and
delays. A connected control replaces the corresponding static cutoff/gain value;
values clamp to that parameter's range. Use an attenuverter to map bipolar LFO
output to cutoff Hz or a unipolar gain. Audio processing happens before the existing
final tanh limiter. Legacy offline `Score`/`Renderer` APIs remain pattern renderers;
signal graphs execute through `LiveRenderer`.

Every sequence step emits a trigger whole arc with duration `gate/(steps*rate)`
cycles, independent of its numeric value. These periodic trigger arcs are evaluated
analytically, without allocating event lists in audio callbacks. Envelopes retrigger
at each whole-arc start. ONE_SHOT begins release after attack plus decay; GATED
begins release at the trigger's whole-arc end, including when that end occurs during
attack or decay. Release starts from the level at that end. Arbitrary pattern-event
trigger extraction and overlapping polyphonic envelopes are not implemented.

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
Delay lengths have a 64-frame minimum, a 48000-frame per-node maximum, and a combined
192000-frame budget (about 3 MiB of stereo double storage per renderer/program).
Mix/filter output and feedback writes are bounded to ±8 to prevent runaway state;
this is intentional overload saturation, not a transparent limiter.

Each frame first reads all delay cells, evaluates the remaining routing in dependency
order, then writes all delay inputs. Delays therefore retain their exact sample count
across callback sizes. Programs can be shared by monitor and speaker renderers;
mutable filters, delay memory, cursors and control buffers are renderer-private.
Publication, resync and seek start fresh effect state. They do not reconstruct the
feedback history of a continuously running client; late-join equivalence applies
to modulation phase, not historical echoes or filter transients.

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
and continues through at least 256 reads, with worker preparation and an induced
underrun followed by explicit stream closure.

Command: `./gradlew.bat test :core-engine:check runClient -PaudioSmoke`.

Validation on 2026-09-13 passed the engine and backend suites. The final Minecraft
smoke test completed 260 reads, zero scheduler misses, one deliberately induced
underrun recovery, and explicit stream closure. The warmed signal and live
render allocation checks each measured zero bytes over 128000 frames. These tests
do not establish a worst-case CPU budget for every allowed graph or speaker count.
