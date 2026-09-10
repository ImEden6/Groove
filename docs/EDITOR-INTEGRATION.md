# Live editor integration

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
See [session saves](SESSION-SAVES.md) for legacy compatibility and atomic-save guarantees.

Client packs live in `<game directory>/groove/samples`. For shared playback, install the exact same custom file at the matching relative path in `<world>/sequencer_samples` on the server. Submission checks the full hash against the server catalog. It does not upload files or browse unreferenced server-only packs. Existing bounded asset transfers serve accepted patch references to listeners.

This is functional integration, not completion of all proposed drawer polish: favorites, animated/resizable drawers, vanilla-sound indexing, and pack-tree navigation remain separate UI work. There is no automatic merge of concurrent drafts. See [FUTURE-WORK.md](FUTURE-WORK.md) for the full list of what's still missing.

## Verification

`gradlew build` runs packet round-trip and editor-state regressions alongside engine tests. The editor regressions cover sample creation, swap parameter/cable preservation, undo/redo, and stale/pending session rejection. These tests do not substitute for interactive two-player acceptance testing.
