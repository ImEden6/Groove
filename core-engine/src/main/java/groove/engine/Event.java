package groove.engine;

/** Whole identifies the note; part is the portion visible in a query. */
public record Event(Arc whole, Arc part, Tone tone, groove.engine.samples.SampleVoice sample) {
    public Event(Arc whole, Arc part, Tone tone) { this(whole, part, tone, null); }
    public Event {
        if (whole == null || part == null || (tone == null) == (sample == null) || whole.start() >= whole.end()
                || part.start() >= part.end() || part.start() < whole.start() || part.end() > whole.end())
            throw new IllegalArgumentException("Event part must lie inside a nonempty whole");
    }
}
