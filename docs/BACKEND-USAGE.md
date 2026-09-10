# Running the Groove backend

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
| `/groove save` | Save the accepted patch and tempo in the world folder |
| `/groove load` | Validate and load `groove-patch.json` from the world folder |

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
`fast.factor` is an integer from 1–16. `euclid.steps` is 1–64, `pulses` is 0–steps,
and positive `rotation` moves hits later. Stack accepts up to 16 incoming edges.
All edges carry patterns through `out`/`in` ports. Exactly one output is required;
every node must reach it. There are no feedback cycles in v1.

Validation caps graphs at 64 nodes, 128 edges, 16 levels of nesting, and a conservative
128 events per cycle before evaluating them. File inputs are capped at 32 KiB.
The renderer plays at most 32 simultaneously active notes in stable graph order.
These restrictions keep this first live scheduler periodic and bounded. Fractional
speed transforms, automation, multiple buses, and feedback DSP require
additional graph types and scheduling work.

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
Physical speaker positioning and headphone items are still future work.

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
