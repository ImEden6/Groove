package groove.engine;

/** Whole identifies the note; part is the portion visible in a query. */
public record Event(Arc whole, Arc part, Tone tone, groove.engine.samples.SampleVoice sample, Pick pick) {
    /** Something the renderer picks when the note starts, from a control node's value at the onset. */
    public sealed interface Pick permits Degree, Slice {
        String control();
    }

    /** A scale degree. The tone's frequency is then degree 0's, times any later transposition. */
    public record Degree(String control, Pitch.Scale scale, int low, int high) implements Pick {
        public Degree {
            if (control == null || scale == null || low < -64 || high > 64 || low > high) throw new IllegalArgumentException("Invalid degree range");
        }
        public int degree(double value) { return low + step(value, high - low + 1); }
        public double ratio(double value) { return Math.pow(2, scale.semitones(degree(value)) / 12.0); }
    }

    /** One of a sample's equal slices, all prepared in advance. The event's own sample is the unmodulated one. */
    public record Slice(String control, java.util.List<groove.engine.samples.SampleVoice> voices) implements Pick {
        public Slice {
            if (control == null || voices == null || voices.isEmpty() || voices.size() > 64) throw new IllegalArgumentException("Invalid slice choice");
            voices = java.util.List.copyOf(voices);
        }
        public groove.engine.samples.SampleVoice voice(double value) { return voices.get(step(value, voices.size())); }
    }

    /** Control 0 to 1 spans the choices in equal steps; values outside clamp. */
    static int step(double value, int choices) {
        if (!(value > 0)) return 0;
        return (int) Math.min(choices - 1, Math.floor(value * choices));
    }

    /** Hash of the fields voice matching compares, so unequal events are rejected without a field walk. */
    int matchHash() { return java.util.Objects.hash(whole, tone, sample, pick); }

    public Event(Arc whole, Arc part, Tone tone) { this(whole, part, tone, null, null); }
    public Event(Arc whole, Arc part, Tone tone, groove.engine.samples.SampleVoice sample) { this(whole, part, tone, sample, null); }
    public Event {
        if (whole == null || part == null || (tone == null) == (sample == null) || whole.start() >= whole.end()
                || part.start() >= part.end() || part.start() < whole.start() || part.end() > whole.end()
                || pick instanceof Degree && tone == null || pick instanceof Slice && sample == null)
            throw new IllegalArgumentException("Event part must lie inside a nonempty whole");
    }
}
