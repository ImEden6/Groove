package groove.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable bounded schedule compiled off the render thread. Frame zero is the score origin. */
public final class Score {
    public record Note(long start, long end, Tone tone) {}
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
        if (!Double.isFinite(cycles) || cycles <= 0 || cycles > 256)
            throw new IllegalArgumentException("Score length must be in (0, 256] cycles");
        List<Note> notes = new ArrayList<>();
        for (Event e : pattern.query(new Arc(0, cycles))) {
            if (e.sample() != null) throw new IllegalArgumentException("Use LiveRenderer with a sample bank for sample events");
            // Continuations do not trigger a second attack.
            if (e.whole().start() < 0 || e.whole().start() >= cycles) continue;
            if (e.tone().frequency() >= transport.sampleRate() * 0.5)
                throw new IllegalArgumentException("Frequency must be below Nyquist");
            long start = transport.frameAt(e.whole().start());
            long end = transport.frameAt(e.whole().end());
            if (end > start) notes.add(new Note(start, end, e.tone()));
            if (notes.size() > 100_000) throw new IllegalArgumentException("Too many scheduled notes");
        }
        notes.sort(Comparator.comparingLong(Note::start));
        return new Score(notes, transport.frameAt(cycles), transport.sampleRate());
    }
}
