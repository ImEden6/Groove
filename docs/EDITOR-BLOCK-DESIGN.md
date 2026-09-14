# Editor block design (proposed)

Partially implemented. This is the agreed design for turning the editor from a
chat-command GUI singleton into a placeable, ownable, multi-session block, and
for how headphones and speakers connect to it. See [FUTURE-WORK.md](FUTURE-WORK.md)
for how this relates to the currently-tracked "no live draft-graph monitoring
through headphones" and "no per-player editors/sessions" gaps, both of which
this design closes.

**Landed so far:** Each placed editor now owns an independent, persistent session.
Right-click requests that block's draft from the server. Draft edits autosave,
including unfinished wiring; Commit validates and queues a separate published
patch for the safe downbeat. Ownership, a persisted owner-managed allowlist and Access UI, player-break
protection, and last-write-wins draft updates are implemented. Draft revisions
survive chunk reloads. A replacement block gets a new session identity.
Holding the headphones item and right-clicking an editor block now records an
exclusive link in that item's custom data (survives inventory save/reload and
travels with the item when traded or dropped), including dimension, position,
and the editor's current session; binding itself is unrestricted (no ACL check),
matching this doc's "Open to anyone" rule below. The server requires headphones
held in the main hand and rejects spectator binds. The old player-based
`groove-headphones.json` file is left untouched but no longer read: existing
prototype links need a one-time rebind because they did not identify an item or
dimension. Worn (Trinkets-equipped) and linked headphones now poll the linked
editor's live draft (`HeadphonePackets.Preview`/`Draft`, unrestricted like
binding, resolved server-side from the actual worn item rather than trusting
a client-supplied position) and play it privately, replacing the shared
monitor stream only while linked and within 16 blocks of the bound editor
(auto-unlink range enforced client-side, checked every tick); outside that
range, or unlinked, headphones still fall back to the legacy monitor stream
for now.

**Still pending:** Speaker binding, link cleanup on block break, and
retirement of the legacy monitor stream as the headphone fallback once
speakers can also target a specific editor's committed patch.
`/groove-editor` and `/groove` still operate the legacy global session.
Existing editor blocks without a recorded owner can be claimed by an operator
opening them; newly placed blocks record their placer automatically.

## Summary of the current state (before this design)

- The editor is opened via `/groove-editor` or by right-clicking a placed
  `GrooveBlocks.EDITOR` block, but either way it's the same singleton; see
  [EDITOR-INTEGRATION.md](EDITOR-INTEGRATION.md).
- There is exactly one server-wide shared session/patch. Concurrent edits from
  another player are rejected, not merged.
- Headphones (`HeadphonesItem`) are a Trinkets equip-slot item that reroutes a
  player to the shared non-positional "monitor" stream ahead of speaker audio.
  They do not preview unsaved edits; they hear the same committed patch as
  everyone else.
- Speakers (`GrooveBlocks.SPEAKER`) are a placeable multiblock tower playing
  the one committed patch, positionally, with distance culling.

## Editor block

The editor becomes a physical, placeable block, following the same trajectory
speakers already took (chat-command/global concept -> world object).

- Placing an editor block creates a **new, independent session** with its own
  draft graph and its own committed patch. Multiple editor blocks in a world
  are separate projects, not views onto one shared graph.
- The block itself is **silent** — it is an authoring/UI trigger only. All
  audible output happens through separately-connected speakers. This keeps
  "author" and "broadcast" as distinct responsibilities, matching the existing
  editor/speaker split in the docs.
- Editing is **draft vs. committed**:
  - Edits apply live to the draft, visible to anyone previewing it (see
    Headphones below).
  - A dedicated **Commit/Publish** button in the editor GUI promotes the
    current draft to the committed patch — the only thing linked speakers
    ever play.
- Editing is **collaborative**: everyone with edit rights (see Permissions)
  edits the same shared draft simultaneously. No edit lock.
- Breaking the block **destroys the session** and clears every headphone and
  speaker link pointing at it. There is no way to preserve or recover a
  session by breaking and re-placing the block.

## Permissions (ACL)

- **Owner** = whoever placed the block.
- The owner maintains a flat **allowlist** of other players who may edit.
  There are no permission tiers beyond owner / allowlisted-editor.
- Gated to owner + allowlist: editing the draft, committing, and breaking
  the block. Only the owner may manage the allowlist.
- Open to anyone regardless of ACL: binding headphones to preview the draft,
  binding a speaker to broadcast the committed patch. Listening is
  deliberately not gated — the ACL protects the work, not who can hear it.
- The allowlist is managed from the same editor GUI as connections and the
  commit button (add/remove player by name).

## Headphones

- **Bind**: hold the headphones item and right-click the editor block. This
  overwrites any existing editor link for those headphones — links are
  exclusive, one editor at a time, and there is no separate unlink action.
- **Listen**: the headphones must be worn (Trinkets equip slot) to hear
  anything; holding them only performs the bind action.
- While worn and linked, the player hears the **live draft** in real time —
  a private, personal feed, never broadcast to speakers or other players.
  Multiple players' headphones can link to the same editor and all hear the
  same shared live draft.
- The link is otherwise wireless/persistent (survives moving around, and
  survives logout/relogin) but **auto-unlinks past 16 blocks** from the bound
  editor.
- No link means silence — there is no more shared non-positional "monitor"
  fallback (see Retired below).

## Speakers

- **Bind**: through the editor's GUI, picking from a list of nearby placed
  speakers (not a world right-click, since one editor may have several
  speakers to manage). Exclusive per speaker, one editor at a time; relinking
  overwrites the previous link.
- Once linked, a speaker plays the editor's **committed patch only**, never
  its draft.
- Existing distance-culling/positional rules (up to 32-block towers,
  8-nearest-within-64-blocks) still apply on top of the link — the link
  decides *which* patch, proximity still decides *whether it's audible here*.
- Links persist across chunk unload/reload.
- No link means silence.

## Retired: the shared global "monitor" stream

The old fallback — a single shared non-positional stream for anyone without
a speaker in range — assumed there was only one patch in the world. That
stops being true once every editor block has its own independent committed
patch, so the concept is retired outright rather than redefined. Audio only
ever reaches a player through a speaker in range or headphones bound to a
specific editor.

## Explicitly out of scope

- Any ACL tier beyond owner/allowlisted-editor (e.g. view-only vs. co-owner).
- Concurrent-edit conflict resolution beyond "last write wins" on a shared
  draft — not designed here.
- Preserving a session through block breakage (e.g. a "burn patch to an item"
  mechanic) — a separate, larger feature if ever pursued.
- Any occlusion, world-protection/claims integration, or per-player audio bus
  configuration beyond what speakers already do.
