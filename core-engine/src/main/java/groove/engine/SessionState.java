package groove.engine;

/** Server monotonic clock domain. Anchor preserves musical position through tempo changes. */
public record SessionState(long revision, long effectiveNanos, double anchorCycle,
                           double bpm, boolean playing, Graph graph) {
    public SessionState {
        if (revision < 0 || !Double.isFinite(anchorCycle) || Math.abs(anchorCycle) > 1e9
                || !Double.isFinite(bpm) || bpm < 30 || bpm > 300 || graph == null)
            throw new IllegalArgumentException("Invalid session state");
    }
    public double cycleAt(long nanos) {
        return anchorCycle + (playing ? (nanos - effectiveNanos) / 1e9 * bpm / 240.0 : 0);
    }
}
