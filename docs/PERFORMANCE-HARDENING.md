# Performance hardening

- Snapshot codecs read bounded JSON strings only. The compiler worker decodes and
  validates the timeline, prepares samples, then hands results to the client thread.
  The existing generation guard rejects work superseded by another snapshot,
  catalog refresh, or disconnect. Encoding preserves the existing wire layout.
- Sample search caches by exact query and catalog identity. Query tokenization
  happens once per refresh, not once per sample per frame.
- `LiveRenderer.publish` prepares renderer-private filter arrays on its caller's
  control thread, before a volatile handoff. Audio rendering neither allocates
  filters nor performs weak-map lookups. Shared immutable programs remain safe
  across independent renderers. Old playback state is released after crossfading.
- Saves capture the session/state and use a per-session single-worker queue
  bounded to 16 waiting requests. Queued is not saved: completion/failure feedback
  returns to the server thread. Shutdown drains saves for up to 30 seconds and
  warns if the deadline expires. Graph and tempo now commit together in one
  atomically replaced file; see [SESSION-SAVES.md](SESSION-SAVES.md). Startup and explicit load reads have
  not been moved off-thread in this change.

Verification: full build and executable regression suites, including raw malformed
snapshot decoding followed by deferred validation, sample-cache reuse, ordered
off-thread saves, and existing shared-program renderer isolation checks. These
are correctness checks, not measured frame-time or audio-underrun benchmarks.
