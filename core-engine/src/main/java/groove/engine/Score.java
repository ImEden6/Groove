package groove.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import groove.engine.samples.*;

/** Immutable bounded schedule compiled off the render thread. Frame zero is the score origin. */
public final class Score {
    public record Note(long start, long end, Tone tone, SamplePlayback sample) {
        public Note(long start, long end, Tone tone) { this(start, end, tone, null); }
        public Note {
            if (start < 0 || end <= start || (tone == null) == (sample == null)) throw new IllegalArgumentException("Invalid score note");
        }
    }
    private final Note[] notes;
    private final long frames;
    private final int sampleRate;

    private Score(List<Note> notes, long frames, int sampleRate) {
        this.notes = notes.toArray(Note[]::new);
        this.frames = frames;
        this.sampleRate = sampleRate;
    }
    public int size() { return notes.length; }
    public Note note(int index) { return notes[index]; }
    public long frames() { return frames; }
    public int sampleRate() { return sampleRate; }

    public static Score compile(Pattern pattern, Transport transport, double cycles) {
        return compile(pattern, transport, cycles, Map.of());
    }
    /** Resolves every sample before rendering; missing assets and unsupported rate conversions fail here. */
    public static Score compile(Pattern pattern, Transport transport, double cycles, Map<AssetRef, SampleData> samples) {
        if (!Double.isFinite(cycles) || cycles <= 0 || cycles > 256)
            throw new IllegalArgumentException("Score length must be in (0, 256] cycles");
        List<Event> events = pattern.query(new Arc(0, cycles));
        var voices = new HashSet<SampleVoice>();
        for (Event e : events) if (e.sample() != null && e.whole().start() >= 0 && e.whole().start() < cycles) voices.add(e.sample());
        PreparedSamples bank = new PreparedSamples(Map.copyOf(samples), voices);
        List<Note> notes = new ArrayList<>();
        for (Event e : events) {
            // Continuations do not trigger a second attack.
            if (e.whole().start() < 0 || e.whole().start() >= cycles) continue;
            if (e.tone() != null && e.tone().frequency() >= transport.sampleRate() * 0.5)
                throw new IllegalArgumentException("Frequency must be below Nyquist");
            long start = transport.frameAt(e.whole().start());
            SamplePlayback sample = e.sample() == null ? null : bank.get(e.sample());
            if (e.sample() != null && sample == null) throw new IllegalArgumentException("Missing offline sample: " + e.sample().asset().assetId());
            if (sample != null) sample.validateOutputRate(transport.sampleRate());
            long end = sample == null ? transport.frameAt(e.whole().end()) : start + (long)Math.ceil(sample.duration() * transport.sampleRate());
            if (end > start) notes.add(new Note(start, end, e.tone(), sample));
            if (notes.size() > 100_000) throw new IllegalArgumentException("Too many scheduled notes");
        }
        notes.sort(Comparator.comparingLong(Note::start));
        return new Score(notes, transport.frameAt(cycles), transport.sampleRate());
    }
}
