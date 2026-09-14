package groove.engine;

import java.util.ArrayList;
import java.util.List;

/** Control-thread API: queries may allocate and must never run in the audio loop. */
@FunctionalInterface
public interface Pattern {
    List<Event> query(Arc arc);

    static Pattern tone(Tone tone) {
        if (tone == null) throw new IllegalArgumentException("Missing tone");
        return trigger(tone, null);
    }
    static Pattern sample(groove.engine.samples.SampleVoice sample) {
        if (sample == null) throw new IllegalArgumentException("Missing sample");
        return trigger(null, sample);
    }
    private static Pattern trigger(Tone tone, groove.engine.samples.SampleVoice sample) {
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            if (arc.start() == arc.end()) return events;
            for (long c = (long) Math.floor(arc.start()); c < Math.ceil(arc.end()); c++) {
                Arc whole = new Arc(c, c + 1.0);
                Arc part = whole.intersect(arc);
                if (part != null) events.add(new Event(whole, part, tone, sample));
            }
            return events;
        };
    }

    static Pattern stack(Pattern... patterns) {
        List<Pattern> children = List.of(patterns);
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Pattern child : children) events.addAll(child.query(arc));
            return events;
        };
    }

    /** Slow concatenation: each child advances one local cycle per complete rotation. */
    static Pattern alternate(Pattern... patterns) {
        List<Pattern> children = List.of(patterns);
        if (children.isEmpty() || children.size() > 16) throw new IllegalArgumentException("Expected 1..16 patterns");
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            if (arc.start() == arc.end()) return events;
            for (long c = (long)Math.floor(arc.start()); c < Math.ceil(arc.end()); c++) {
                Arc visible = new Arc(c, c + 1.0).intersect(arc);
                long localCycle = Math.floorDiv(c, children.size());
                double shift = c - localCycle;
                Pattern child = children.get(Math.floorMod(c, children.size()));
                for (Event e : child.query(new Arc(visible.start() - shift, visible.end() - shift))) {
                    events.add(new Event(new Arc(e.whole().start() + shift, e.whole().end() + shift),
                            new Arc(e.part().start() + shift, e.part().end() + shift), e.tone(), e.sample()));
                }
            }
            return events;
        };
    }

    /** Ordered steps at a shared pulse rate; sequence length may differ from pulses per cycle. */
    static Pattern polymeter(int stepsPerCycle, Pattern... patterns) {
        if (stepsPerCycle < 1 || stepsPerCycle > 64) throw new IllegalArgumentException("Expected 1..64 steps per cycle");
        return alternate(patterns).fast(stepsPerCycle);
    }

    /** Whole-arc hashing is independent of query partition/order. Coincident notes share a decision. */
    default Pattern probability(double chance, int seed) {
        if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("Chance must be 0..1");
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Event e : query(arc)) {
                long bits = mixSeed(Double.doubleToLongBits(e.whole().start() == 0 ? 0 : e.whole().start()) ^ seed);
                bits = mixSeed(bits ^ Double.doubleToLongBits(e.whole().end() == 0 ? 0 : e.whole().end()));
                if ((bits >>> 11) * 0x1.0p-53 < chance) events.add(e);
            }
            return events;
        };
    }

    private static long mixSeed(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    default Pattern fast(double factor) {
        if (!Double.isFinite(factor) || factor <= 0)
            throw new IllegalArgumentException("Speed must be finite and positive");
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Event e : query(arc.scale(factor))) {
                Arc whole = e.whole().scale(1 / factor);
                Arc part = e.part().scale(1 / factor).intersect(arc);
                if (part != null) events.add(new Event(whole, part, e.tone(), e.sample()));
            }
            return events;
        };
    }

    /** Each active step restarts the child at cycle zero. Positive rotation moves hits later. */
    default Pattern euclid(int steps, int pulses, int rotation) {
        if (steps < 1 || steps > 1024 || pulses < 0 || pulses > steps)
            throw new IllegalArgumentException("Expected 1 <= steps <= 1024 and 0 <= pulses <= steps");
        int shift = Math.floorMod(rotation, steps);
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            if (arc.start() == arc.end() || pulses == 0) return events;
            for (long c = (long) Math.floor(arc.start()); c < Math.ceil(arc.end()); c++) {
                for (int s = 0; s < steps; s++) {
                    int index = Math.floorMod(s - shift, steps);
                    if (index * pulses % steps >= pulses) continue;
                    double start = c + (double) s / steps;
                    Arc slot = new Arc(start, c + (double) (s + 1) / steps);
                    Arc visible = slot.intersect(arc);
                    if (visible == null) continue;
                    // Roundoff at a slot boundary must not query the next child cycle.
                    Arc local = new Arc(Math.max(0, Math.min(1, (visible.start() - start) * steps)),
                            Math.max(0, Math.min(1, (visible.end() - start) * steps)));
                    for (Event e : query(local)) {
                        Arc whole = new Arc(start + e.whole().start() / steps, start + e.whole().end() / steps);
                        Arc part = new Arc(start + e.part().start() / steps, start + e.part().end() / steps).intersect(visible);
                        if (part != null) events.add(new Event(whole, part, e.tone(), e.sample()));
                    }
                }
            }
            return events;
        };
    }

    private static void checkQuery(Arc arc) {
        if (arc == null || Math.abs(arc.start()) > 1e9 || Math.abs(arc.end()) > 1e9
                || arc.end() - arc.start() > 4096)
            throw new IllegalArgumentException("Query exceeds supported horizon");
    }
}
