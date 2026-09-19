package groove.engine;

/** One committed and at most one future revision. Server mutations are serialized. */
public final class SessionTimeline {
    public record Snapshot(SessionState current, SessionState pending) {
        public Snapshot {
            if (current == null || pending != null && (pending.revision() <= current.revision()
                    || pending.effectiveNanos() <= current.effectiveNanos()))
                throw new IllegalArgumentException("Invalid revision ordering");
        }
        public SessionState at(long nanos) {
            return pending != null && nanos >= pending.effectiveNanos() ? pending : current;
        }
        public long revision() { return pending == null ? current.revision() : pending.revision(); }
    }
    private Snapshot snapshot;
    public SessionTimeline(Graph graph, double bpm, long now) { this(graph, bpm, false, now); }
    public SessionTimeline(Graph graph, double bpm, boolean playing, long now) {
        GraphCompiler.compile(graph);
        snapshot = new Snapshot(new SessionState(0, now, 0, bpm, playing, SignalGraph.assignBirths(graph, null, now)), null);
    }
    public Snapshot snapshot(long now) {
        if (snapshot.pending != null && now >= snapshot.pending.effectiveNanos())
            snapshot = new Snapshot(snapshot.pending, null);
        return snapshot;
    }
    /** Replace startup defaults immediately, advancing revision for clients already connected. */
    public Snapshot restoreStartup(Graph graph, double bpm, long now) {
        if (snapshot.revision() != 0 || snapshot.current.playing() || snapshot.pending != null)
            throw new IllegalArgumentException("Startup restoration requires untouched stopped defaults");
        GraphCompiler.compile(graph);
        snapshot = new Snapshot(new SessionState(1, now, 0, bpm, false,
                SignalGraph.assignBirths(graph, null, now)), null);
        return snapshot;
    }
    public Snapshot schedule(Graph graph, double bpm, boolean playing, long expectedRevision, long now) {
        Snapshot before = snapshot(now);
        if (expectedRevision != before.revision()) throw new IllegalArgumentException("Stale revision; refresh before editing");
        if (before.pending != null) throw new IllegalArgumentException("An edit is already waiting for the downbeat");
        GraphCompiler.compile(graph);
        SessionState current = before.current;
        long earliest = now + 1_000_000_000L;
        double cycle = current.playing() ? Math.ceil(current.cycleAt(earliest)) : current.anchorCycle();
        long at = current.playing()
                ? current.effectiveNanos() + Math.round((cycle - current.anchorCycle()) * 240e9 / current.bpm()) : earliest;
        SessionState next = new SessionState(current.revision() + 1, at, cycle, bpm, playing, SignalGraph.assignBirths(graph, current.graph(), at));
        snapshot = new Snapshot(current, next);
        return snapshot;
    }
}
