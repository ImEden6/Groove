# Editor block design (proposed)

Partially implemented. This is the agreed design for turning the editor from a
chat-command GUI singleton into a placeable, ownable, multi-session block, and
for how headphones and speakers connect to it. See [FUTURE-WORK.md](FUTURE-WORK.md)
for how this relates to the currently-tracked "no live draft-graph monitoring
through headphones" and "no per-player editors/sessions" gaps, both of which
this design closes.

**Landed so far:** `GrooveBlocks.EDITOR` is a placeable, silent block
(`EditorBlock`/`EditorBlockEntity`) with its own textures/model, and
right-clicking it opens the editor GUI (`EditorBlockInteraction`). Everything
else below — independent sessions, draft vs. committed, permissions,
headphone/speaker binding, retiring the monitor stream — is not built yet:
the block currently just opens the same one global session `/groove-editor`
already did.

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
- Gated to owner + allowlist: editing the draft, committing, managing the
  allowlist, breaking the block.
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
