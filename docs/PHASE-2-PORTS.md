# Phase 2, stage 2: typed port foundation and v3 migration

This page records the initial foundation increment. Modulation, audio routing and
named editor sockets have subsequently landed; see [current signal support](PHASE-2-SIGNALS.md).

The graph compiler accepts versions 1, 2 and 3. `PortType` defines `PATTERN`,
`MOD_FLOAT`, `TRIGGER` and `AUDIO` as distinct signal domains without implicit
conversions. Node types own immutable named input/output declarations, including
input cardinality. Compiler validation and editor connection checks share these
declarations.

Existing tone/sample generators and pattern transforms still carry `PATTERN` on
their `out`/`in` sockets. Output consumes a pattern; it does not yet expose an audio
bus. Modulation, trigger and audio domains are reserved for subsequent node stages.
This increment introduces no modulation evaluation, control buffers or feedback DSP.
All graph cycles remain rejected.

## Compatibility

V3 retains the existing node and edge JSON shape. Types are determined by node
socket declarations rather than trusting a redundant type supplied by a client.
IDs, parameters, sample references, edge order and existing socket names survive
migration unchanged. The packet and session-envelope formats remain unchanged.

`Graph.toV3()` validates the original version before upgrading and is idempotent.
`GraphJson.decodeCurrent()` imports a validated legacy graph as v3. Ordinary
`GraphJson.decode()` preserves the declared version for exact persisted and wire
snapshot round trips. The editor opens legacy graphs and emits v3 on editing or
submission, including undo/redo snapshots. Loading alone does not rewrite save files.
Older binaries reject v3 graphs; backward readability is not promised.

## Verification

`PortTests` checks all signal-domain compatibility pairs, unknown/reversed sockets,
generator input rejection, cycles, unsupported versions, invalid v1 sample migration,
idempotence, unchanged assets/order and continuous event queries across migration.
Backend tests cover v1/v2/v3 JSON, canonical import, editor undo/sample insertion,
v3 sample submission packets and transactional v3 persistence. Existing scheduling,
DSP and playback suites remain enabled.

Run `./gradlew.bat test :core-engine:check`.

Validation passed on 2026-09-13: engine, rolling scheduler, DSP, live playback,
sample, JSON, editor, persistence and Minecraft packet suites. No in-game smoke
test was run for this schema/validation increment.

## Following stages

These stages have since landed: deterministic LFO/envelope/attenuverter/sequence
nodes, reusable control buffers, filter/delay/mix-bus routing, delay-cut validation
and named editor sockets. See [signals](PHASE-2-SIGNALS.md) for current behavior,
verification and remaining extensions. The description above records stage 2's scope.
