# Groove backend plan

## Still unimplemented (from this doc)

- Headphones are complete: Trinkets equip slot, priority routing (sample audition over the personal
  monitor over speaker audio), live draft monitoring of the linked editor, a head-locked
  (`relative`) sink, and underwater muffling; see [FUTURE-WORK.md](FUTURE-WORK.md).
- Phase 2 extensions are implemented: independent audio-render sources, arbitrary pattern triggers/polyphonic envelopes, and bounded local effect-history recovery. Exact older/cross-revision history remains future work; see [signals](PHASE-2-SIGNALS.md).
- No tempo automation, timeline seeking, or non-integer-cycle start (runtime `/groove tempo` exists; score/pattern-side tempo curves do not).
- No adaptive resync tuning beyond fixed slew/step thresholds under asymmetric/high-jitter conditions.
- No server-side chunk-unload or distance culling for audio session state (session is server-wide/persistent).
- Pattern queries and the Minecraft audio stream adapter still allocate per tick/block (the mixer itself does not).
- Custom samples are distributed on demand (catalog browsing on join, `/groove-samples install <id>`,
  and bounded transfers for referenced assets), but there is no bulk push and no compressed bundle.

See [FUTURE-WORK.md](FUTURE-WORK.md) for the full ranked list.

## Current implementation

Milestones 2, 3, 4, and 5 have landed alongside the core engine. See [backend usage](BACKEND-USAGE.md)
for the exact supported scope. The live graph now uses a rolling lookahead scheduler
with continuous multi-cycle pattern evaluation and fractional speed transforms (Phase 2 Stage 1;
see [PHASE-2-SCHEDULER.md](PHASE-2-SCHEDULER.md)). There is one server-wide monitoring session, with
operator commands, transactional JSON persistence, revisioned downbeat changes, clock offset
estimation, and a Fabric audio stream. Physical speaker blocks (`GrooveBlocks.SPEAKER`) stack
into towers providing positional audio with distance culling. The sample backend includes
WAV/Vorbis decoding, pitched one-shots, auditioning, bounded catalogs/caches, SHA-256
references, and 48-tap Kaiser-windowed sinc resampling; see [samples and packs](SAMPLES.md)
and [engine evolution](ENGINE-EVOLUTION.md). Tone and sample nodes feature biquad low-pass
filtering (`cutoffHz` and `resonanceQ`), with voice-stealing crossfades. V3 adds deterministic
modulation and delayed audio routing (see [signals](PHASE-2-SIGNALS.md)). A wearable headphone
item (Trinkets equip slot) plays the linked editor's draft head-locked, ahead of speaker audio;
score tempo automation remains future work; see [FUTURE-WORK.md](FUTURE-WORK.md) for the full
current list.

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
samples, filters, audio device playback, and network integration were outside this
milestone and landed later.
Notes can ring beyond the score horizon if the caller keeps rendering; the demo
exports exactly the requested horizon. Voice stealing uses a 120-frame (2.5 ms)
raised-cosine crossfade to eliminate clicks. Tanh saturation adds harmonics and is not an
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
speaker blocks have also landed with positional audio (see [BACKEND-USAGE.md](BACKEND-USAGE.md));
headphones have landed too, with priority routing over speaker/personal audio (see above);
remaining gaps are tracked in [FUTURE-WORK.md](FUTURE-WORK.md).
