# Phase 2, stage 1: rolling pattern scheduling

Implemented the scheduling foundation while retaining graph v1/v2 and the existing packet format. Typed ports, v3 migration, modulation nodes, feedback routing, and the port editor remain later stages.

## Behavior

- `FAST.factor` accepts finite values from 0.25 through 16, including 1.5. The editor preserves fractional edits instead of rounding them to integers.
- `GraphCompiler` retains the continuous compiled `Pattern`. `LoopPlan.size/event` are now a first-cycle preview for compiled graphs, not the complete repeating playback schedule. Package-local hand-built plans retain the old periodic fixture behavior.
- Live playback uses absolute event arcs. Notes spanning cycle boundaries keep their voice/filter state; simultaneous identical notes remain distinct. Mixed fractional periods are evaluated continuously rather than restarted every cycle.
- `LookaheadScheduler` maintains a 64-slot cycle-bucket ring. It prepares four cycles ahead, retains the previous cycle for callbacks in flight at rollover, and retains additional onset history for sample tails (up to 40 seconds for a ten-second sample at 0.25 pitch).
- Pattern queries, sorting, deduplication, and snapshot allocation happen on the control worker. Audio callbacks capture an immutable window once per render block and reuse preallocated voice storage. Window changes do not republish/reset the audio program.
- Stable voice matching uses absolute whole arcs, settings, and duplicate occurrence ordinals. Query clipping is not part of voice identity.
- A missed window produces silence and increments `scheduleMisses()`; the audio callback never queries a pattern or repeats the old first cycle. After worker preparation, playback reconstructs from the current transport position. Seeks do not replay intervening cycles.
- Tone continuations survive a transport revision whose anchor lies inside their whole arc. Sample history remains gated to the program's anchor, matching the existing new-program sample-onset policy.

## Integration contract

Call `Timeline.prepare(serverNanos)` periodically from a **single control worker**, and before publishing a late-join timeline. Constructors prepare the transport anchor only. Do not call preparation from `render` or an audio callback.

`MusicClient` owns a dedicated 50 ms preparation worker, shared across monitor and speaker renderers through the published timeline. It prepares late joins before publication and shuts the worker down with the client. Active speaker renderers also receive graph changes; previously only the monitor renderer was republished.

A preparation failure is logged once for that timeline; audio stops once its prepared coverage expires. A subsequent graph publication permits a fresh preparation attempt. The scheduler enforces a bounded event count per queried cycle; it does not accept arbitrary unbounded patterns.

Old integer-speed saves continue to load. New fractional-speed saves use the same schema, but older builds with integer-only validation may reject them. No claim of backward readability by older binaries is made.

## Verification

`SchedulerTests` is invoked by the engine suite. It checks:

- Twelve seconds of live rendering against the continuous offline renderer for 1.5 speed, 0.25 speed, and stacked mixed periods, across worker refills.
- Multi-cycle tone continuation after a tempo revision.
- Incremental bucket reuse, forward/backward seeks, and coverage at worker rollover.
- Duplicate simultaneous voices and maximum pitched-sample history.
- Identical late-join sample playback across two renderers.
- No pattern queries during audio rendering, including missed-window recovery.
- Zero bytes allocated during 128,000 warmed render frames when the JVM exposes thread allocation counters. This measures the Java render loop; it does not imply allocation-free worker preparation or Minecraft streaming-buffer creation.

Backend tests round-trip fractional graphs through v1/v2 JSON. Existing packet, DSP, late-join, and underrun tests remain enabled. The extended Minecraft smoke test runs for at least 256 streaming reads, refills lookahead on the control thread, rejects scheduler starvation, induces an underrun, and checks explicit stream closure.

Validation passed on 2026-09-12: engine checks, full mod/packet tests, and the extended Minecraft audio smoke test. The final smoke run completed 261 reads, one induced-underrun recovery, zero schedule misses, and explicit stream closure.

Commands: `./gradlew.bat -p core-engine check` and `./gradlew.bat test :core-engine:check runClient -PaudioSmoke`.

## Next stage

Introduce typed ports and v3 migration as a separately verified increment, followed by deterministic LFO/envelope evaluation, then delayed feedback/audio routing and editor integration. The separately upgraded resampler now passes its defined 50 dB rejection tests; see [ENGINE-EVOLUTION.md](ENGINE-EVOLUTION.md).
