package groove.engine;

/** Whole identifies the note; part is the portion visible in a query. */
public record Event(Arc whole, Arc part, Tone tone, groove.engine.samples.SampleVoice sample, Degree degree) {
    /**
     * A scale degree the renderer picks when the note starts, from a control node's value at the
     * onset. The tone's frequency is then degree 0's, times any later transposition.
     */
    public record Degree(String control, Pitch.Scale scale, int low, int high) {
        public Degree {
            if (control == null || scale == null || low < -64 || high > 64 || low > high) throw new IllegalArgumentException("Invalid degree range");
        }
        /** Control 0 to 1 spans low to high in equal steps. */
        public int degree(double value) {
            int steps = high - low + 1;
            if (!(value > 0)) return low;
            return low + (int) Math.min(steps - 1, Math.floor(value * steps));
        }
        public double ratio(double value) { return Math.pow(2, scale.semitones(degree(value)) / 12.0); }
    }

    /** Hash of the fields voice matching compares, so unequal events are rejected without a field walk. */
    int matchHash() { return java.util.Objects.hash(whole, tone, sample, degree); }

    public Event(Arc whole, Arc part, Tone tone) { this(whole, part, tone, null, null); }
    public Event(Arc whole, Arc part, Tone tone, groove.engine.samples.SampleVoice sample) { this(whole, part, tone, sample, null); }
    public Event {
        if (whole == null || part == null || (tone == null) == (sample == null) || whole.start() >= whole.end()
                || part.start() >= part.end() || part.start() < whole.start() || part.end() > whole.end()
                || degree != null && tone == null)
            throw new IllegalArgumentException("Event part must lie inside a nonempty whole");
    }
}
