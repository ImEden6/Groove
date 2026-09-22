package groove.engine;

import java.util.*;

/** Single control-thread writer; any number of audio readers consume immutable snapshots.
 * Four cycles of lookahead plus bounded sample history. Query/allocation never occurs in window().
 */
public final class LookaheadScheduler {
    public static final int LOOKAHEAD_CYCLES = 4;
    private static final int RING_SIZE = 64, MAX_BUCKET_EVENTS = GraphCompiler.MAX_EVENTS * 2;
    public record Entry(Event event, int ordinal, double durationSeconds, int matchHash) {
        Entry(Event event, int ordinal, double durationSeconds) { this(event, ordinal, durationSeconds, event.matchHash()); }
        public double durationSeconds() {
            assert !Double.isNaN(durationSeconds) : "Trigger entry duration should not be read";
            return durationSeconds;
        }
    }
    private record Key(Arc whole, Tone tone, groove.engine.samples.SampleVoice sample, Event.Pick pick, int ordinal) {}
    private record Bucket(long cycle, Entry[] entries) {}
    public static final class Window {
        private final double start, end;
        private final Entry[] entries;
        private Window(double start, double end, Entry[] entries) { this.start = start; this.end = end; this.entries = entries; }
        public boolean contains(double cycle) { return cycle >= start && cycle < end; }
        double startCycle() { return start; }
        public int size() { return entries.length; }
        public Entry entry(int index) { return entries[index]; }
    }
    private final Pattern pattern;
    private final int historyCycles;
    private final double bpm;
    private final boolean retainAllEvents;
    private final java.util.function.ToDoubleFunction<Event> durationFunction;
    private final Bucket[] ring = new Bucket[RING_SIZE];
    private volatile Window window;

    public LookaheadScheduler(Pattern pattern, double sampleHistorySeconds, double bpm,
                              java.util.function.ToDoubleFunction<Event> durationFunction) {
        this.pattern = Objects.requireNonNull(pattern);
        this.durationFunction = Objects.requireNonNull(durationFunction);
        if (!Double.isFinite(sampleHistorySeconds) || sampleHistorySeconds < 0 || sampleHistorySeconds > 40
                || !Double.isFinite(bpm) || bpm < 30 || bpm > 300) throw new IllegalArgumentException("Invalid scheduler horizon");
        historyCycles = (int)Math.ceil(sampleHistorySeconds * bpm / 240) + 1;
        if (historyCycles > RING_SIZE - LOOKAHEAD_CYCLES - 1) throw new IllegalArgumentException("Invalid scheduler horizon");
        this.bpm = bpm;
        this.retainAllEvents = false;
    }

    public LookaheadScheduler(Pattern pattern, double sampleHistorySeconds, double bpm) {
        this(pattern, sampleHistorySeconds, bpm, e -> e.sample() == null ? (e.whole().end() - e.whole().start()) * 240.0 / bpm : sampleHistorySeconds);
    }

    /** Cycle-bounded lookback, independent of tempo/sample duration, that retains every event
     *  (not just sample onsets) back to historyCycles before the earliest covered cycle.
     *  One extra bucket covers the published base-1 position; the maximum requested history
     *  is therefore 59 cycles in the 64-slot ring. Used by trigger sources, where a consumer
     *  (e.g. ENVELOPE) needs to see a non-sample onset up to a fixed number of cycles back
     *  regardless of bpm; the seconds-based constructor's "discard ended tone events past one
     *  cycle back" rule (correct for audio sources, where a finished tone's pattern event stops
     *  mattering once the voice itself ends) would otherwise prune exactly the onsets a
     *  multi-cycle envelope release needs to stay visible. */
    public LookaheadScheduler(Pattern pattern, int historyCycles) {
        this(pattern, historyCycles, e -> Double.NaN);
    }

    private LookaheadScheduler(Pattern pattern, int historyCycles, java.util.function.ToDoubleFunction<Event> durationFunction) {
        this.pattern = Objects.requireNonNull(pattern);
        this.durationFunction = Objects.requireNonNull(durationFunction);
        if (historyCycles < 0 || historyCycles > RING_SIZE - LOOKAHEAD_CYCLES - 1) throw new IllegalArgumentException("Invalid scheduler horizon");
        // Published coverage starts at base-1, so its earliest position needs one extra
        // bucket of history as well. Include that bucket in the ring capacity check above.
        this.historyCycles = historyCycles + 1;
        this.bpm = 120;
        this.retainAllEvents = true;
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
                    Key key = new Key(e.whole(), e.tone(), e.sample(), e.pick(), 0);
                    int ordinal = occurrences.getOrDefault(key, 0);
                    occurrences.put(key, ordinal + 1);
                    double duration = durationFunction.applyAsDouble(e);
                    entries[i] = new Entry(e, ordinal, duration);
                }
                ring[slot] = bucket = new Bucket(c, entries);
            }
            for (Entry entry : bucket.entries) {
                Event e = entry.event;
                // Retain tone continuations and sample onsets; discard historical tones that ended and samples that finished.
                // A trigger scheduler (retainAllEvents) keeps every event back to historyCycles instead,
                // since a still-releasing envelope voice needs its onset visible well past one cycle back.
                if (!retainAllEvents) {
                    if (e.sample() == null) {
                        if (e.whole().end() <= base - 1) continue;
                    } else if (e.sample().loop()) {
                        if (e.whole().end() <= base - 1) continue;
                    } else {
                        double durationSec = entry.durationSeconds;
                        if (durationSec < 0) {
                            if (e.whole().start() <= base - 1) continue;
                        } else {
                            if (e.whole().start() + durationSec * bpm / 240.0 <= base - 1) continue;
                        }
                    }
                }
                unique.putIfAbsent(new Key(e.whole(), e.tone(), e.sample(), e.pick(), entry.ordinal), entry);
            }
        }
        Entry[] entries = unique.values().toArray(Entry[]::new);
        Arrays.sort(entries, Comparator.comparingDouble(e -> e.event.whole().start()));
        window = new Window(base - 1, base + LOOKAHEAD_CYCLES, entries);
    }
}
