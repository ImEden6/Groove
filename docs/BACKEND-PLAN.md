# Groove backend plan

## Current implementation

Milestones 2, 3, and 5 now have an initial implementation alongside the original
offline engine. See [backend usage](BACKEND-USAGE.md) for the exact supported scope.
The live graph is restricted to bounded one-cycle patterns, so an immutable loop
schedule and direct note-phase reconstruction replace the originally proposed
rolling event queue. There is one server-wide stereo monitoring session, with
operator commands, explicit JSON persistence, revisioned downbeat changes, clock
offset estimation, and a Fabric audio stream. The sample backend now includes
WAV/Vorbis decoding, pitched one-shots, auditioning, bounded catalogs/caches,
SHA-256 references, and restricted asset transfer; see [samples and packs](SAMPLES.md).
Tone nodes now have a biquad low-pass filter (`cutoffHz`), in both the offline
engine and live playback. Physical emitters, richer pattern types, sample-node
filtering, and further synthesis remain future work; see
[FUTURE-WORK.md](FUTURE-WORK.md) for the full current list.

The milestone descriptions below preserve the longer-term design targets, not a
claim that every production feature in those sections is already implemented.

## Architecture

The existing Fabric project targets Minecraft 1.21.1 / Java 21. The new
`core-engine` module has no Minecraft or third-party dependencies. The root mod
includes its jar, and it can also be built independently.

Data flow: persisted graph -> validated graph -> pure pattern -> scheduled notes
-> voice mixer -> audio sink. The graph compiler and scheduler run outside the
audio rendering thread. Each client renders audio locally; the server owns shared
graph revisions, permissions, and transport state.

## Milestone 1: standalone musical engine (implemented)

- Finite half-open cycle arcs and immutable sound events with whole/part semantics.
- Repeating tones, pattern stacking, speed transforms, Euclidean rhythms.
- Explicit beats per cycle, fixed tempo, integer sample-frame playback position.
- Immutable finite score compilation with stable onset ordering.
- Preallocated bounded voice pool, deterministic oldest-voice stealing counter.
- Sine and PolyBLEP saw oscillators, short attack/release ramps, equal-power pan.
- Float stereo mixing with tanh saturation; 16-bit conversion only at WAV output.
- Offline WAV demo and dependency-free regression suite.

The mixer performs no explicit per-render allocation. This is not a claim that
Java provides hard real-time or zero-GC execution. Pattern queries allocate.
Compilation currently materializes results before enforcing the note limit;
it must not accept arbitrary untrusted graphs until bounded evaluation exists.
Scores begin at cycle zero and are finite. Tempo changes, seeking, graph input,
samples, filters, audio device playback, and network integration are not implemented.
Notes can ring beyond the score horizon if the caller keeps rendering; the demo
exports exactly the requested horizon. Voice stealing can click and needs a short
crossfade before production. Tanh saturation adds harmonics and is not an
anti-aliasing filter or a transparent peak limiter.

## Milestone 2: graph and live scheduler

Define a versioned graph DTO independent of NBT. Start with tone, stack, fast,
Euclid, and output nodes. Validate node IDs, typed ports, missing inputs, cycles,
numeric bounds, node count, nesting depth, and evaluation/event budgets.
Keep UI coordinates separate from musical meaning. Compile immutable revisions
on a worker. Use a bounded queue for sample-timestamped events; consume onsets
exactly once across adjacent scheduling windows. Define note identity for
simultaneous stacked events, explicit stop/release behavior, and queue overflow.

Schedule edits at a future cycle beyond queued audio, then swap revisions at
that frame. Define handling for missed deadlines. Add piecewise tempo mapping,
start/stop/seek, deterministic randomness, and cancellation of obsolete future
events. Test chunk-size independence, boundary changes, and scheduler starvation.

## Milestone 3: Minecraft audio adapter

Prototype a single audible client source before building blocks or an editor.
Verify Minecraft/OpenAL context and thread ownership, device reset, resource
reload, pause, disconnect, and clean shutdown. Render ahead into reusable buffers;
measure underruns and tune buffering from observed performance. Wakeups refill
buffers; sample positions determine musical timing. Sleeping is not a timing
guarantee.

Use mono source feeds for portable positional speakers and stereo for headphone
monitoring; verify behavior against the actual OpenAL implementation. A single
stereo mix cannot preserve separate speaker positions. Budget sources per client,
cull inaudible emitters, and avoid assuming a universal 32-source limit.

## Milestone 4: samples and synthesis

Decode samples off-thread, normalize rate/channel format, and bound cache memory.
Add sample voices with interpolation and envelopes, resource-pack asset IDs,
missing-asset behavior, smoothed filter parameters, and click-free voice stealing.
Test aliasing, pitch accuracy, clipping, and CPU cost before increasing polyphony.

## Milestone 5: server state and synchronization

Persist graph snapshots and distribute validated revisioned edits, never PCM.
Transport messages need an epoch, cycle position, tempo, and revision in addition
to an apply-at cycle. Estimate server clock offset, account for queued audio,
correct drift gradually, and handle late packets, join-in-progress, unload, and
reconnect. Different clients' monotonic timestamps cannot be compared directly.
Set ownership/edit permissions and packet/evaluation limits before accepting
client edits. Test with simulated delay, jitter, loss, and concurrent edits.

The node editor, physical speaker blocks, and headphones can be layered onto
these tested interfaces after local playback and synchronization work. The
node editor has since landed (see [EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md));
speaker blocks and headphones have not — see [FUTURE-WORK.md](FUTURE-WORK.md).
