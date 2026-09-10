# Transactional session saves

`/groove save` queues a save of both the selected graph and its BPM into the world's
`groove-session.json`. Version 1 stores `version`, `bpm`, and `graphJson` (the graph
JSON encoded as a string). The temporary file is flushed before a single atomic
replacement. Unsupported atomic replacement reports a failure; there is no
non-atomic fallback. This prevents mixed graph/tempo generations, but is not a
guarantee against every filesystem or power-loss failure.

Startup and `/groove load` prefer this file and restore graph and tempo together.
Startup remains stopped; explicit load preserves the current playback setting and
uses the normal scheduled transition. A malformed combined save reports an error
rather than silently loading stale legacy files (startup uses the demo defaults).

When no combined file exists, legacy `groove-patch.json` plus `groove-tempo.txt` are
read. Missing legacy tempo defaults to 128. The next save creates the combined
file; legacy files are not deleted or updated. After migration, edit the combined
file, not the old files. The existing ordered background queue remains in use.
