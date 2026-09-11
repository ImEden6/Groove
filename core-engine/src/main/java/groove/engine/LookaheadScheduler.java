package groove.engine;

import java.util.*;

/** Single control-thread writer; any number of audio readers consume immutable snapshots.
 * Four cycles of lookahead plus bounded sample history. Query/allocation never occurs in window().
 */
public final class LookaheadScheduler {
    public static final int LOOKAHEAD_CYCLES = 4;
    private static final int RING_SIZE = 64, MAX_BUCKET_EVENTS = GraphCompiler.MAX_EVENTS * 2;
    public record Entry(Event event, int ordinal) {}
    private record Key(Arc whole, Tone tone, groove.engine.samples.SampleVoice sample, int ordinal) {}
    private record Bucket(long cycle, Entry[] entries) {}
    public static final class Window {
        private final double start, end;
        private final Entry[] entries;
        private Window(double start, double end, Entry[] entries) { this.start = start; this.end = end; this.entries = entries; }
        public boolean contains(double cycle) { return cycle >= start && cycle < end; }
        public int size() { return entries.length; }
        public Entry entry(int index) { return entries[index]; }
    }
    private final Pattern pattern;
    private final int historyCycles;
    private final Bucket[] ring = new Bucket[RING_SIZE];
    private volatile Window window;
    public LookaheadScheduler(Pattern pattern, double sampleHistorySeconds, double bpm) {
        this.pattern = Objects.requireNonNull(pattern);
        if (!Double.isFinite(sampleHistorySeconds) || sampleHistorySeconds < 0 || sampleHistorySeconds > 40
                || !Double.isFinite(bpm) || bpm < 30 || bpm > 300) throw new IllegalArgumentException("Invalid scheduler horizon");
        historyCycles = (int)Math.ceil(sampleHistorySeconds * bpm / 240) + 1;
    }
    public Window window() { return window; }
    /** Control/worker thread only. Reuses fixed cycle buckets, publishing atomically after a complete fill. */
    public void prepare(double cycle) {
        if (!Double.isFinite(cycle) || Math.abs(cycle) > 1e8) throw new IllegalArgumentException("Unsupported scheduler position");
        long base = (long)Math.floor(cycle);
        Window previous = window;
        if (previous != null && previous.start == base - 1) return;
        Map<Key, Entry> unique = new LinkedHashMap<>();
        for (long c = base - historyCycles; c < base + LOOKAHEAD_CYCLES; c++) {
            int slot = Math.floorMod(c, RING_SIZE);
            Bucket bucket = ring[slot];
            if (bucket == null || bucket.cycle != c) {
                List<Event> events = new ArrayList<>(pattern.query(new Arc(c, c + 1)));
                if (events.size() > MAX_BUCKET_EVENTS) throw new IllegalArgumentException("Pattern exceeds per-cycle event budget");
                events.sort(Comparator.comparingDouble(e -> e.whole().start()));
                Entry[] entries = new Entry[events.size()];
                Map<Key, Integer> occurrences = new HashMap<>();
                for (int i = 0; i < entries.length; i++) {
                    Event e = events.get(i);
                    Key key = new Key(e.whole(), e.tone(), e.sample(), 0);
                    int ordinal = occurrences.getOrDefault(key, 0);
                    occurrences.put(key, ordinal + 1);
                    entries[i] = new Entry(e, ordinal);
                }
                ring[slot] = bucket = new Bucket(c, entries);
            }
            for (Entry entry : bucket.entries) {
                Event e = entry.event;
                // Retain tone continuations and sample onsets; discard historical tones that ended.
                if (e.sample() == null && e.whole().end() <= base - 1) continue;
                unique.putIfAbsent(new Key(e.whole(), e.tone(), e.sample(), entry.ordinal), entry);
            }
        }
        Entry[] entries = unique.values().toArray(Entry[]::new);
        Arrays.sort(entries, Comparator.comparingDouble(e -> e.event.whole().start()));
        window = new Window(base - 1, base + LOOKAHEAD_CYCLES, entries);
    }
}
