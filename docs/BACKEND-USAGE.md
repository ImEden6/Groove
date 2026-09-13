# Running the Groove backend

## Still unimplemented (from this doc)

- Multiple independent audio-render sources and reconstruction of effect history on late join remain future work. Modulation, mix buses and delayed feedback are implemented in v3.
- Headphone items remain future work — monitor audio mutes for in-world speaker streams instead.

Target: Minecraft 1.21.1, Fabric Loader, Fabric API, Java 21. Install the built mod
and Fabric API on both server and clients. This version has one shared session
for the whole server, played as a stereo monitor through the Jukebox/Note Blocks
volume category. No blocks or node editor are required.

## Commands

Enable cheats in a test single-player world, or use an operator account on a server.

| Command | Behavior |
| --- | --- |
| `/groove` | Show playback state, tempo, revision, and whether a change is queued |
| `/groove play` | Start the current patch (the built-in demo is the initial patch) |
| `/groove stop` | Stop on a safe downbeat |
| `/groove tempo 140` | Change tempo while preserving cycle position |
| `/groove demo` | Replace the patch with the built-in demo |
| `/groove sample-demo` | Load the factory sample-based drum demo |
| `/groove signal-demo` | Load the v3 filter sweep and feedback echo demo |
| `/groove save` | Save the accepted patch and tempo in the world folder |
| `/groove load` | Validate and load graph and BPM from `groove-session.json` (legacy two-file saves remain readable) |

Mutations require operator permission level 2. One change can be pending at a time;
another command is rejected until it applies. Edits have at least one second of
lead time and, while playing, apply at an integer cycle boundary. A cycle is four
beats. Starting from stopped has one second of lead time. Saving includes a pending
accepted edit; loading a saved world restores the patch and tempo in stopped state.
Use the volume slider to mute the monitor immediately.

## Patch format

Run `/groove save` to get an editable demo. This smaller example creates a rhythm:

```json
{
  "version": 1,
  "nodes": [
    {"id": "tone", "type": "tone", "params": {"frequency": 220, "gain": 0.25, "wave": 0}},
    {"id": "rhythm", "type": "euclid", "params": {"steps": 16, "pulses": 5, "rotation": 0}},
    {"id": "out", "type": "output", "params": {}}
  ],
  "edges": [
    {"fromNode": "tone", "fromPort": "out", "toNode": "rhythm", "toPort": "in"},
    {"fromNode": "rhythm", "fromPort": "out", "toNode": "out", "toPort": "in"}
  ]
}
```

Version-1 nodes: `tone`, `euclid`, `fast`, `stack`, `output`. Version 2 adds
`generator/sample`; see [samples and packs](SAMPLES.md). Tone parameters are
`frequency` (20–16000 Hz), `gain` (0–1), `pan` (-1–1), and `wave` (0=sine, 1=saw).
`fast.factor` is a finite decimal from 0.25 to 16.0 (e.g. 0.25, 1.5, 2.0). `euclid.steps` is 1–64, `pulses` is 0–steps,
and positive `rotation` moves hits later. Stack accepts up to 16 incoming edges.
V1/v2 edges carry patterns through `out`/`in` ports. Exactly one output is required;
every node must reach it. There are no feedback cycles in v1/v2.

Validation caps graphs at 64 nodes, 128 edges, 16 levels of nesting, and a conservative
128 events per cycle before evaluating them. File inputs are capped at 32 KiB.
The renderer plays at most 32 simultaneously active notes in stable graph order.
The rolling lookahead scheduler evaluates fractional speed transforms continuously
without restarting every cycle. V3 adds LFO/envelope/sequence controls, filters,
multiple mix buses and delayed feedback; see [signal graph usage](PHASE-2-SIGNALS.md)
for sockets, parameter units, server phase rules and the single audio-source limit.

## Session persistence and transactional saves

`/groove save` queues a save of both the selected graph and its BPM into the world's
`groove-session.json`. Version 1 stores `version`, `bpm`, and `graphJson` (the graph
JSON encoded as a string). The temporary file is flushed before a single atomic
replacement. Unsupported atomic replacement reports a failure; there is no
non-atomic fallback. This prevents mixed graph/tempo generations, but is not a
guarantee against every filesystem or power-loss failure.

Startup and `/groove load` prefer this file and restore graph and tempo together.
Startup remains stopped; explicit load preserves the current playback setting and
uses the normal scheduled transition. A malformed combined save reports an error
rather than silently loading stale legacy files (startup uses the demo defaults).

When no combined file exists, legacy `groove-patch.json` plus `groove-tempo.txt` are
read. Missing legacy tempo defaults to 128. The next save creates the combined
file; legacy files are not deleted or updated. After migration, edit the combined
file, not the old files. The existing ordered background queue remains in use.

## Timing and audio

The server shares a session epoch, revision, tempo, cycle anchor, effective time,
and graph. Clients estimate clock offset from ping replies, compile immutable
programs on a bounded worker queue, and switch at the scheduled audio time.
Late joins reconstruct the current note phase directly, with a short fade-in.
Small timing errors slew at up to 0.1%; gaps over 250 ms trigger a faded resync.
No audio is sent over the network.

Minecraft owns OpenAL and its sound executor. A narrowly scoped channel hook reads
queued-buffer duration and source sample position so the next rendered block is
aligned to its estimated playback time. Four 2048-frame blocks at 48 kHz give about
171 ms of buffering. The adapter transfers native PCM buffers to Minecraft for
release; unlike the core rendering calculation, this integration allocates per block.

This is approximate cross-client synchronization: asymmetric network latency and
unmeasured device/output latency remain. It does not promise sample-accurate sound
across different computers. Resource/device reload or underrun restarts are retried
within five seconds. Disconnect closes the stream. Single-player pause follows
Minecraft's sound pause; on resume the session rejoins the monotonic transport.
Speaker towers (`GrooveBlocks.SPEAKER`) provide physical positional audio in the world,
stacking up to 32 blocks tall with height-scaled volume and distance culling for up to 8
nearest towers within 64 blocks. When speakers are in range, monitor audio mutes in favor
of in-world mono streams. Headphone items remain future work.

## Verification

```powershell
.\gradlew.bat -p core-engine check
.\gradlew.bat build
.\gradlew.bat runClient -PaudioSmoke
```

The first command checks the standalone engine, graph bounds, timeline transitions,
clock estimation, and live rendering. The full build additionally checks graph JSON
and Minecraft packet round trips. The opt-in client test launches a development
client with OpenAL's silent output driver and exits after verifying streamed PCM
and queue timing; normal runs do not enable it.

For a real multiplayer acceptance test, connect two clients, run play/tempo/stop,
join a third client mid-cycle, reload resources, disconnect/reconnect, and introduce
network delay. Verify phase by recording outputs with known device latency, not
just comparing command arrival times.

Integration references: [Fabric networking](https://docs.fabricmc.net/develop/networking)
and [OpenAL specification](https://www.openal.org/documentation/openal-1.1-specification.pdf).
Exact Fabric sound hook signatures were verified against the locally installed
1.21.1 Fabric sound API sources.
