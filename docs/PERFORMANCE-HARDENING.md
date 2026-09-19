# Performance hardening

## Implemented

- Startup and explicit load file checks, reads, and JSON decoding run on the
  bounded persistence worker, in order with saves. Results return to the server
  thread; session identity, revision, and load generation checks discard stale
  results. Startup stays stopped and rejects edits/saves until restoration finishes.
  Saved startup state takes effect immediately with a new revision and no pending
  transition, so edits can be accepted as soon as startup completes.
  Explicit loads report queued, applied, failed, or superseded status.

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
  atomically replaced file; see [BACKEND-USAGE.md](BACKEND-USAGE.md#session-persistence-and-transactional-saves). Reads share the same queue and shutdown drain.

Verification: full build and executable regression suites, including raw malformed
snapshot decoding followed by deferred validation, sample-cache reuse, ordered
off-thread saves and reads, owner-thread read completion, missing/malformed saves,
queue shutdown rejection, and existing shared-program renderer isolation checks. These
are correctness checks, not measured frame-time or audio-underrun benchmarks.
