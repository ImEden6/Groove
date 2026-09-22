# Live editor integration

## Block sessions

Right-click a placed editor to open its independent server-owned draft. Edits,
undo/redo, and BPM changes autosave (at most once every 400 ms); the screen polls
for shared changes once a second when idle. Clean screens adopt the latest draft;
unsent local edits remain local until their next last-write-wins submission.
Incomplete graphs may be saved as drafts. Commit first saves local edits, then
validates the acknowledged shared revision and exact server sample assets before
queuing the published patch for the safe downbeat. A stale or invalid commit
preserves both draft and published state. Play/Stop changes draft transport and
queues the same start or stop for the published patch, keeping its graph and tempo,
so linked speakers follow it without a commit; Commit also publishes that transport
state, and supersedes a queued play change since it carries one itself. While a
commit is queued, Play/Stop changes the draft alone and that commit lands with its
own transport state.

Sessions, owner/allowlist, draft revision, both graphs, tempos, and transport
flags save with the editor's chunk; no `/groove save` is needed for block sessions.
On reload, transport restarts its cycle origin. Breaking and replacing creates a
new session. Editing and player breaking require owner/allowlist access. Use Access to add or remove players by name (owner only, up to 64 editors).
Scroll the list and click a row to fill the removal field. Uncached names appear
as full UUIDs, which can also be removed. Offline name lookup runs asynchronously;
permissions and block identity are checked again before applying the result. Legacy unowned blocks are claimed when an operator first opens them.

Holding headphones in the main hand and right-clicking an editor binds that pair
to the editor's dimension, position, and session. This is saved as item data;
previous prototype links in `groove-headphones.json` need rebinding once.
Worn headphones play the linked draft on the server transport clock, including
custom samples available on the server. Invalid/unavailable drafts are silent.
The server clears worn links beyond 16 blocks, across dimensions, or when the
loaded editor is missing/replaced. Unworn pairs in the player's own inventory are
cleaned up too; a pair in a chest, on the ground, or in an item frame keeps its link.
Headphones no longer use the global monitor fallback.

Use Speakers (next to Access) to list placed speakers within 64 blocks of the
editor and link/unlink each one by clicking its row; binding is unrestricted,
like headphones. The link lives on the speaker's block entity and survives
chunk unload/reload. A linked speaker plays the editor's committed patch
(never the draft), positionally, with the existing tower-height/distance
rules layered on top; an unlinked speaker is silent. Breaking the editor
unlinks every speaker still pointing at it within that same 64-block radius.

## Discs and jukeboxes

Right-click an editor holding a **Blank Disc** to burn its committed patch and
tempo onto a **Groove Disc** (never the draft); anyone can burn, as anyone can link a
speaker. Right-click an editor holding a Groove Disc to replace its draft with the
disc's patch, keeping the draft's play state; this needs edit access, and a Commit
publishes it. The patch lives on the item, so it survives inventories, chests and
trades. LFO birth stamps are not stored, and a disc whose data was edited into an
invalid patch or tempo is refused. A burned disc's tooltip shows its tempo and node
count; rename it on an anvil to label it.

Put a Groove Disc in a vanilla jukebox, and any speaker tower standing directly on
the jukebox plays it, positionally, under the usual tower and distance rules,
instead of the editor it may be linked to. Playback starts at cycle 0 when the
server first sees the disc playing, loops, and stops when the disc comes out.
Every listener hears the same position on the server clock. A different disc, or
the same one put back in, starts over; so does a jukebox whose chunk stopped
ticking while nobody was near. The jukebox shows vanilla's "Now Playing: Groove
patch" and gives a comparator signal of 15; the disc's own song is silent. Custom
samples on a disc play only where the server has the same files.

To copy a disc, craft a Groove Disc with a Blank Disc: the copy comes out and the
original stays in the grid.

| Item | Recipe |
| --- | --- |
| Blank Disc ×2 | Black concrete on four sides of a redstone dust |
| Speaker | Planks all round a note block, with an iron ingot below it |
| Editor | Iron ingots in the corners, redstone on the sides, a note block in the middle, a comparator below it |
| Headphones | Leather, iron ingot, leather over two note blocks |

## Legacy command editor

The following Apply workflow still describes `/groove-editor` and `/groove`.

## Still unimplemented (from this doc)

- Animated/resizable drawers and vanilla-sound indexing.
- No automatic merge of concurrent drafts (not planned; the status line names other editors and offers Reload or Commit on conflict).

Open `/groove-editor` (or specify tactical, clockwork, crt, vanilla). The default is now the visible vanilla theme. The editor waits for a server snapshot rather than opening a disconnected demo.

- **Apply** validates and submits the working graph and BPM. **Play/Stop** submits the draft and toggles shared playback. BPM accepts 30–300.
- Server authorization remains operator level 2. Requests carry session epoch, base revision, and a correlation ID. Stale, malformed, unauthorized, rapid, or already-pending edits are rejected without changing playback. Accepted changes use the existing safe-downbeat scheduler.
- Edits and undo/redo remain local until Apply. A rejection preserves the draft. Reload explicitly fetches the latest received server state; repeat Reload to discard a changed draft. Escape warns before discarding an unsent graph. `/groove save` is still required for world persistence.
- The displayed cycle and cable phase use the synchronized server clock.
- **Pulse: On/Off** (top right) shows or hides the marker that rides each cable on the beat. It is a per-player setting saved to `groove/editor-prefs.properties` and applies to every theme.
- The sample drawer reads the hot-reloaded client catalog. Search supports text, `@factory`, and `@custom`. Click to audition; arrows browse/preview, Enter toggles preview, and Space previews while browsing. Ctrl+B toggles the overlay without changing canvas coordinates.
- Drag a sample onto the canvas to create a version-2 sample generator; connect its output before Apply. Drop onto a sample generator to preserve parameters and outgoing wires. Tone generators can also be converted. Undo restores the previous reference and cables.
- A sample node whose asset is missing, still downloading, or doesn't match the server's copy shows a row of yellow and black stripes on its card; hover the card for the reason.
- Feedback loops are limited to a loop gain of 0.95 so every player hears the same echo tail. A wire or knob change that would go over it is refused and the status line gives the bound. Set a delay's `freeRun` control to **Free-run** to allow self-oscillating feedback through it; its card then says "free-run", and the status line warns that players who join late may hear a different tail. See [feedback stability](FEEDBACK-STABILITY.md).
- The Inspector shows sample availability, an asynchronously decoded waveform, and themed rotary parameter controls. Drag a knob or its label/value vertically; hold Ctrl when starting for fine adjustment. Each gesture is one undo step. Scroll over the Inspector to reach clipped controls. Indicators reflect the current draft, including undo/redo; Apply is still required for playback changes. Delete removes selected nodes.
- Preview audio is local and separate from the session. Escape stops it first; closing the screen stops it as well.

## Custom packs and limitations

World persistence now saves patch and tempo together in `groove-session.json`.
See [session saves](BACKEND-USAGE.md#session-persistence-and-transactional-saves) for legacy compatibility and atomic-save guarantees.

Client packs live in `<game directory>/groove/samples`. For shared playback, install the exact same custom file at the matching relative path in `<world>/sequencer_samples` on the server. Submission checks the full hash against the server catalog. It does not upload files or browse unreferenced server-only packs. Existing bounded asset transfers serve accepted patch references to listeners.

This is functional integration, not completion of all proposed drawer polish: favorites, animated/resizable drawers, vanilla-sound indexing, and pack-tree navigation remain separate UI work. There is no automatic merge of concurrent drafts. See [FUTURE-WORK.md](FUTURE-WORK.md) for the full list of what's still missing.

## Verification

`gradlew build` runs packet round-trip and editor-state regressions alongside engine tests. Block-session checks cover draft isolation, last-write-wins updates, stale/pending/invalid commit rejection, incomplete-draft persistence, saved publication and transport, ownership/allowlist persistence, replacement identity, and packet round trips. The editor regressions cover sample creation, swap parameter/cable preservation, undo/redo, and stale/pending session rejection. These tests do not substitute for interactive two-player acceptance testing.

Speaker playback snapshots include editor identity and the pending publication, so
relinking to a session with a lower revision takes effect and downbeat changes do
not wait for the next poll. Polling, compilation and playback share the same eight
nearest tower bases within 64 blocks; an unlinked selected tower is silent. Each
speaker keeps its own sample requests, and downloaded samples rebuild its timeline.
The Speakers panel refreshes every two seconds and recovers timed-out bind requests.
Server requests validate distance and loaded chunks before accessing blocks. Stale
links in unloaded speaker chunks are cleared when resolved after loading.
