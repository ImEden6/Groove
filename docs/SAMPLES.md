# Samples and packs

The backend supports pitched WAV/OGG sample graphs, local auditioning, content
hashes, hot-reloaded catalogs, and restricted server-to-client asset transfer.
These command controls are independent of the editor UI.

On join and whenever its scanned catalog changes, the server sends its full custom-pack catalog listing (asset IDs and
hashes only, never bytes) so `/groove-samples list` can browse everything the
server has, not just assets already wired into the shared graph. Fetching the
actual bytes for something you find this way is a separate, explicit step
(`/groove-samples install <id>`, see below) — joining a server never triggers
a bulk download on its own.

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
procedural samples, requiring no third-party audio downloads. `factory:basic/break.wav`
is one bar of those three at 96 BPM (2.5 s): kick, snare on two and four, hats on
every eighth, a ghost snare and a closing roll. Cut into eight slices, each lands on
an eighth note, ready for a slice index control to rearrange.

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
| `/groove-samples install <id>` | Explicitly fetch one server catalog asset into the persistent download cache |

`list` shows both locally installed samples and server catalog entries not yet
installed (labeled accordingly). Assets already referenced by the shared graph
are still fetched automatically, exactly as before; `install` is for anything
else in the server's catalog — it requires operator permission level 2 (the
same threshold as editing the shared graph), since it lets a player pull any
catalog asset regardless of graph reference. A level-2 player already has an
equivalent path today via a throwaway graph edit; `install` just removes that
workaround.

## Graph format

Sample nodes require graph `version: 2` or `3`; version-1 tone graphs still load. Use the
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

`generator/sample` also supports source-frame `startFrame`/`endFrame` bounds
(end=0 means asset end) and `reverse` (0/1). The `sample_slice` pattern node
selects an equal region with `slices`, `index`, and `reverse`; connect several
slices to `polymeter` to rearrange a break. These controls are in the editor.
Set `loop` to 1 to sustain a sample for its whole event, cycling between `loopStart` and
`loopEnd` (fractions of the region, defaults 0.25 and 0.9) with a `loopFadeMs` crossfade
(default 20, up to 500). The editor warns when a loop is too short or had to move inward; see
[Stage 4](ENGINE-UPGRADE-STAGES.md#sustained-sample-loops).
Regions are isolated and prefiltered before playback, with a combined memory
budget. Invalid regions or region-budget overflow reject preparation of the
replacement program. See [Stage 2](ENGINE-UPGRADE-STAGES.md#stage-2-usage) for
semantics, limits, offline rendering, and a sample demo.

SHA-256 covers the exact encoded bytes. A different file with the same ID is never
silently substituted. An exact cached copy or verified server copy can satisfy
the reference; otherwise the node is silent with a missing/mismatch status.
An intentional replacement requires accepting the new hash in the graph. Reloads
do not mutate PCM already pinned to an audio program. Waveform peak extraction
is available for the inspector to consume.

## Transfer and memory limits

The server shares exact ID/hash pairs referenced by the current or pending
graph, or requested explicitly by an operator through `install`. A client requests one asset at a time in 48 KiB
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
| Each catalog (user packs, managed downloads, or server) | 256 assets including factory kit / 32 MiB encoded |
| Combined client catalog | Up to 512 references / 64 MiB encoded; duplicate factory entries are shared in the combined view |
| Decoded LRU cache | 64 MiB |
| Current + pending graph bank | 32 MiB source + isolated-region PCM, including prefiltered levels; 128 voice settings |
| Received encoded cache | 32 MiB |

Verified downloads are persisted under
`<game directory>/groove/downloaded-samples/<pack>/<file>` using a temporary file
and atomic rename. This folder is managed by Groove: only downloads there are
eligible for eviction. Files under `groove/samples` are user-owned and are never
deleted or overwritten by transfers. Files downloaded by older builds into
`groove/samples` are also left alone because their ownership cannot be inferred.

When a local pack and a download share an ID with different hashes, browsing,
`ref`, and ID-based audition prefer the local pack. Graph playback can resolve
either exact hash. Managed downloads have a separate 90%-of-catalog disk budget
(230 files, about 28.8 MiB); the oldest unprotected downloads are evicted first.
Current/pending graph IDs and IDs in the connected server catalog are protected.
Reinstalling an ID accounts for replacement size rather than an extra file.

Install paths reject linked directories, including Windows junctions. Disconnect
cancels pending disk commits; a commit already in progress completes before the
connection's cancellation returns. Decoding and temporary-file staging happen
outside that commit lock. Disk-install failures remain visible in
`/groove-samples status`, while verified audio may still play from memory.
Disconnect clears transfer state and the short-lived received cache; managed
files persist across connections. Explicit installs waiting in the queue survive
graph refreshes. No files are removed if staging or atomic replacement fails.

Sample pitching uses a bandlimited 48-tap Kaiser-windowed sinc resampler with
four prefiltered octave levels (68.21 dB minimum stopband rejection); see
[engine evolution](ENGINE-EVOLUTION.md).

Minecraft owns OpenAL and buffer cleanup. An underrun recovery hook restarts only
live Groove streams after refill and rebases subsequent rendering to playback
time. Explicit stop and finite audition streams are excluded from recovery.

## Verification

`./gradlew -p core-engine check` covers PCM conversion, malformed WAVs, pitch,
sample tails, immutable data, LRU eviction, catalog mutations, and verified
transfers. Installation regressions cover local-file preservation, exact-hash
collisions, replacement accounting, failed writes, linked parents/roots,
disconnect cancellation, and explicit request retention. `./gradlew build` also
covers graph JSON, Minecraft packet codecs, and catalog update announcements.
`./gradlew runClient -PaudioSmoke` decodes a real Minecraft Vorbis asset, plays the
sample kit through silent OpenAL, deliberately stalls a refill to test recovery,
and verifies explicit stop closes the stream. Actual two-machine transfer and
speaker-output latency have not been tested.

Vorbis integration uses the [LWJGL STB Vorbis API](https://javadoc.lwjgl.org/org/lwjgl/stb/STBVorbis.html).
