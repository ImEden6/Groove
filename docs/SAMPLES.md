# Samples and packs

## Still unimplemented (from this doc)

- The `/groove-samples list` command does not browse the server's whole catalog (only assets already referenced by the shared graph).

The backend supports pitched WAV/OGG sample graphs, local auditioning, content
hashes, hot-reloaded catalogs, and restricted server-to-client asset transfer.
These command controls are independent of the editor UI.

## Try the factory kit

```text
/groove-samples list
/groove-samples audition factory:basic/kick.wav
/groove-samples stop
/groove sample-demo
```

After the queued sample-demo edit applies, `/groove play` starts it. Shared edits
require cheats/operator permission level 2. `/groove stop` stops the shared track;
`/groove-samples stop` stops only your preview. Preview uses its own non-positional
sound source. The factory kick, snare, and hat are original deterministic
procedural samples, requiring no third-party audio downloads.

## Custom packs

Add lowercase `.wav` or `.ogg` files under:

- Client: `<game directory>/groove/samples/<pack>/<file>`.
- Server: `<world>/sequencer_samples/<pack>/<file>`.

For example, `groove/samples/lofi/snare.wav` is `custom:lofi/snare.wav`. Allowed
path characters are lowercase letters, digits, underscore, hyphen, dot, and slash.
The client folder is created automatically. Create the server folder when adding
packs. Both catalogs rescan every five seconds, without restarting the game.
Scans exclude file symlinks and paths outside the root, and cap traversal at eight
levels / 4096 filesystem entries.

| Command | Behavior |
| --- | --- |
| `/groove-samples list snare` | Filter local/factory IDs by substring; show up to 20 matches |
| `/groove-samples audition <id>` | Decode off-thread and preview locally |
| `/groove-samples stop` | Cancel pending/current audition |
| `/groove-samples ref <id>` | Copy the full ID/hash JSON reference to the clipboard |
| `/groove-samples reload` | Rescan local files and retry graph resolution |
| `/groove-samples status` | Report graph asset status, transfer errors, and scan warnings |

The list does not yet browse the server's whole catalog. Network requests fetch
only assets already referenced by the shared graph.

## Graph format

Sample nodes require graph `version: 2`; version-1 tone graphs still load. Use the
reference copied by `/groove-samples ref` in the node's `sample` field:

```json
{
  "id": "snare",
  "type": "generator/sample",
  "params": {"pitchRatio": 1.0, "gain": 0.8, "pan": 0.0},
  "sample": {
    "assetId": "custom:lofi/snare.wav",
    "sha256": "<replace with the full 64-character hash from the command>"
  }
}
```

The hash placeholder must be replaced before loading. `/groove sample-demo`
followed by `/groove save` gives a complete valid graph to edit. Connect samples
through the existing Euclid, fast, stack, and output nodes. Pitch is a playback
rate ratio from 0.25–4, gain is 0–1, and pan is -1–1. One-shot tails cross rhythm
steps and cycle boundaries while sharing the 32-voice limit.

SHA-256 covers the exact encoded bytes. A different file with the same ID is never
silently substituted. An exact cached copy or verified server copy can satisfy
the reference; otherwise the node is silent with a missing/mismatch status.
An intentional replacement requires accepting the new hash in the graph. Reloads
do not mutate PCM already pinned to an audio program. Waveform peak extraction
is available for the inspector to consume.

## Transfer and memory limits

The server shares exact ID/hash pairs from its catalog only when referenced by
the current or pending graph. A client requests one asset at a time in 48 KiB
chunks, with sequential assembly, retries/timeouts, and final hash verification.
It does not accept unsolicited assets. Invalid audio, unavailable files, or
budget failures remain silent and expose a status. Failed requests have a
30-second cooldown; use reload after correcting packs to retry.

| Item | Limit |
| --- | --- |
| Formats | WAV PCM 8/16/24/32-bit or float32; Ogg Vorbis (not Opus) |
| Channels/rate | Mono/stereo, 8–192 kHz; output at 48 kHz stereo |
| Duration | 10 seconds before pitch adjustment |
| Encoded / decoded asset | 4 MiB / 8 MiB |
| Catalog | 256 assets including factory kit / 32 MiB encoded |
| Decoded LRU cache | 64 MiB |
| Current + pending graph bank | 32 MiB unique decoded PCM |
| Received encoded cache | 32 MiB |

Received assets stay in memory; they are not installed into local folders.
Disconnect clears transfer state and the received encoded cache. Decoded PCM
may remain in the bounded LRU. Active banks, crossfades, and decoder scratch
space also consume memory, so the cache budget is not a total JVM heap guarantee.
Sample pitching uses a bandlimited 48-tap Kaiser-windowed sinc resampler with
four prefiltered octave levels (68.21 dB minimum stopband rejection); see
[engine evolution](ENGINE-EVOLUTION.md).

Minecraft owns OpenAL and buffer cleanup. An underrun recovery hook restarts only
live Groove streams after refill and rebases subsequent rendering to playback
time. Explicit stop and finite audition streams are excluded from recovery.

## Verification

`./gradlew -p core-engine check` covers PCM conversion, malformed WAVs, pitch,
sample tails, immutable data, LRU eviction, catalog mutations, and verified
transfers. `./gradlew build` also covers graph JSON and Minecraft packet codecs.
`./gradlew runClient -PaudioSmoke` decodes a real Minecraft Vorbis asset, plays the
sample kit through silent OpenAL, deliberately stalls a refill to test recovery,
and verifies explicit stop closes the stream. Actual two-machine transfer and
speaker-output latency have not been tested.

Vorbis integration uses the [LWJGL STB Vorbis API](https://javadoc.lwjgl.org/org/lwjgl/stb/STBVorbis.html).
