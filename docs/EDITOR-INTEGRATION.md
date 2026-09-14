# Live editor integration

## Block sessions

Right-click a placed editor to open its independent server-owned draft. Edits,
undo/redo, and BPM changes autosave (at most once every 400 ms); the screen polls
for shared changes once a second when idle. Clean screens adopt the latest draft;
unsent local edits remain local until their next last-write-wins submission.
Incomplete graphs may be saved as drafts. Commit first saves local edits, then
validates the acknowledged shared revision and exact server sample assets before
queuing the published patch for the safe downbeat. A stale or invalid commit
preserves both draft and published state. Play/Stop changes draft transport;
Commit also publishes that transport state.

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
loaded editor is missing/replaced. Stored/unworn link cleanup remains pending.
Headphones no longer use the global monitor fallback.

Use Speakers (next to Access) to list placed speakers within 64 blocks of the
editor and link/unlink each one by clicking its row; binding is unrestricted,
like headphones. The link lives on the speaker's block entity and survives
chunk unload/reload. A linked speaker plays the editor's committed patch
(never the draft), positionally, with the existing tower-height/distance
rules layered on top; an unlinked speaker is silent. Breaking the editor
unlinks every speaker still pointing at it within that same 64-block radius.

## Legacy command editor

The following Apply workflow still describes `/groove-editor` and `/groove`.

## Still unimplemented (from this doc)

- Sample drawer favorites, animated/resizable drawers, vanilla-sound indexing, pack-tree navigation.
- No automatic merge of concurrent drafts.

Open `/groove-editor` (or specify tactical, clockwork, crt, vanilla). The default is now the visible vanilla theme. The editor waits for a server snapshot rather than opening a disconnected demo.

- **Apply** validates and submits the working graph and BPM. **Play/Stop** submits the draft and toggles shared playback. BPM accepts 30–300.
- Server authorization remains operator level 2. Requests carry session epoch, base revision, and a correlation ID. Stale, malformed, unauthorized, rapid, or already-pending edits are rejected without changing playback. Accepted changes use the existing safe-downbeat scheduler.
- Edits and undo/redo remain local until Apply. A rejection preserves the draft. Reload explicitly fetches the latest received server state; repeat Reload to discard a changed draft. Escape warns before discarding an unsent graph. `/groove save` is still required for world persistence.
- The displayed cycle and cable phase use the synchronized server clock.
- The sample drawer reads the hot-reloaded client catalog. Search supports text, `@factory`, and `@custom`. Click to audition; arrows browse/preview, Enter toggles preview, and Space previews while browsing. Ctrl+B toggles the overlay without changing canvas coordinates.
- Drag a sample onto the canvas to create a version-2 sample generator; connect its output before Apply. Drop onto a sample generator to preserve parameters and outgoing wires. Tone generators can also be converted. Undo restores the previous reference and cables.
- The Inspector shows sample availability, an asynchronously decoded waveform, and themed rotary parameter controls. Drag a knob or its label/value vertically; hold Ctrl when starting for fine adjustment. Each gesture is one undo step. Scroll over the Inspector to reach clipped controls. Indicators reflect the current draft, including undo/redo; Apply is still required for playback changes. Delete removes selected nodes.
- Preview audio is local and separate from the session. Escape stops it first; closing the screen stops it as well.

## Custom packs and limitations

World persistence now saves patch and tempo together in `groove-session.json`.
See [session saves](BACKEND-USAGE.md#session-persistence-and-transactional-saves) for legacy compatibility and atomic-save guarantees.

Client packs live in `<game directory>/groove/samples`. For shared playback, install the exact same custom file at the matching relative path in `<world>/sequencer_samples` on the server. Submission checks the full hash against the server catalog. It does not upload files or browse unreferenced server-only packs. Existing bounded asset transfers serve accepted patch references to listeners.

This is functional integration, not completion of all proposed drawer polish: favorites, animated/resizable drawers, vanilla-sound indexing, and pack-tree navigation remain separate UI work. There is no automatic merge of concurrent drafts. See [FUTURE-WORK.md](FUTURE-WORK.md) for the full list of what's still missing.

## Verification

`gradlew build` runs packet round-trip and editor-state regressions alongside engine tests. Block-session checks cover draft isolation, last-write-wins updates, stale/pending/invalid commit rejection, incomplete-draft persistence, saved publication and transport, ownership/allowlist persistence, replacement identity, and packet round trips. The editor regressions cover sample creation, swap parameter/cable preservation, undo/redo, and stale/pending session rejection. These tests do not substitute for interactive two-player acceptance testing.
