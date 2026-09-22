package groove.engine;

import java.util.ArrayList;
import java.util.List;

/** Control-thread API: queries may allocate and must never run in the audio loop. */
@FunctionalInterface
public interface Pattern {
    List<Event> query(Arc arc);

    /** Select an equal-sized region of each sample, without changing event timing. */
    default Pattern slice(int slices, int index, boolean reverse) { return slice(slices, index, reverse, null); }

    /** Like slice, with the slice picked from control as each note starts; index is the one offline renders play. */
    default Pattern slice(int slices, int index, boolean reverse, String control) {
        new groove.engine.samples.SampleRegion(0, 0, slices, index, reverse);
        var choices = new java.util.concurrent.ConcurrentHashMap<groove.engine.samples.SampleVoice, Event.Slice>();
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Event e : query(arc)) {
                if (e.sample() == null) throw new IllegalArgumentException("Sample slicing requires sample events");
                Event.Slice pick = control == null ? null : choices.computeIfAbsent(e.sample(), voice -> new Event.Slice(control,
                        java.util.stream.IntStream.range(0, slices).mapToObj(i -> voice.slice(slices, i, reverse)).toList()));
                events.add(new Event(e.whole(), e.part(), null, e.sample().slice(slices, index, reverse), pick));
            }
            return events;
        };
    }

    /** Pitch mapping runs only during control-thread queries, preserving event identity/timing. */
    default Pattern transpose(double semitones) {
        if (!Double.isFinite(semitones) || Math.abs(semitones) > 48)
            throw new IllegalArgumentException("Transpose must be -48..48 semitones");
        double ratio = Math.pow(2, semitones / 12);
        return mapTones(t -> Pitch.withFrequency(t, t.frequency() * ratio), true);
    }

    /** Sets each note to a scale degree picked when it starts, from control; with no control, to degree low. */
    default Pattern quantize(int root, Pitch.Scale scale, int low, int high, String control) {
        if (control == null) {
            double hz = Pitch.degreeHz(root, scale, low);
            return mapTones(t -> Pitch.withFrequency(t, hz), false);
        }
        Event.Degree degree = new Event.Degree(control, scale, low, high);
        double base = Pitch.degreeHz(root, scale, 0);
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Event e : query(arc)) {
                if (e.tone() == null) throw new IllegalArgumentException("Pitch transforms require tone events");
                events.add(new Event(e.whole(), e.part(), Pitch.withFrequency(e.tone(), base), null, degree));
            }
            return events;
        };
    }

    /** Absolute scale pitches applied to successive branches, at a shared step rate. */
    default Pattern scaleSequence(int root, Pitch.Scale scale, int stepsPerCycle, int... degrees) {
        if (degrees.length < 1 || degrees.length > 8) throw new IllegalArgumentException("Expected 1..8 degrees");
        Pattern[] steps = new Pattern[degrees.length];
        for (int i = 0; i < degrees.length; i++) {
            double hz = Pitch.degreeHz(root, scale, degrees[i]);
            if (hz < 20 || hz > 16000) throw new IllegalArgumentException("Scale pitch must be 20..16000 Hz");
            steps[i] = mapTones(t -> Pitch.withFrequency(t, hz), false);
        }
        return polymeter(stepsPerCycle, steps);
    }

    /** Chord gain is divided by voice count to retain the input's summed gain. */
    default Pattern chord(Pitch.Chord chord, int inversion) {
        if (chord == null) throw new IllegalArgumentException("Missing chord");
        int[] intervals = chord.intervals(inversion);
        double[] ratios = new double[intervals.length];
        for (int i = 0; i < intervals.length; i++) ratios[i] = Math.pow(2, intervals[i] / 12.0);
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Event e : query(arc)) {
                if (e.tone() == null) throw new IllegalArgumentException("Chords require tone events");
                for (double ratio : ratios) {
                    Tone t = Pitch.withFrequency(e.tone(), e.tone().frequency() * ratio);
                    events.add(new Event(e.whole(), e.part(), new Tone(t.wave(), t.frequency(),
                            t.gain() / ratios.length, t.pan(), t.cutoffHz(), t.resonanceQ(), t.pulseWidth()), null, e.pick()));
                }
            }
            return events;
        };
    }

    /** keepPick false: the mapper sets an absolute pitch, replacing any degree picked later. */
    private Pattern mapTones(java.util.function.UnaryOperator<Tone> mapper, boolean keepPick) {
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            for (Event e : query(arc)) {
                if (e.tone() == null) throw new IllegalArgumentException("Pitch transforms require tone events");
                events.add(new Event(e.whole(), e.part(), mapper.apply(e.tone()), null, keepPick ? e.pick() : null));
            }
            return events;
        };
    }

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
                            new Arc(e.part().start() + shift, e.part().end() + shift), e.tone(), e.sample(), e.pick()));
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
                if (part != null) events.add(new Event(whole, part, e.tone(), e.sample(), e.pick()));
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
                        if (part != null) events.add(new Event(whole, part, e.tone(), e.sample(), e.pick()));
                    }
                }
            }
            return events;
        };
    }

    /** Reverses events within each integer cycle [c, c+1). */
    default Pattern reverse() {
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            if (arc.start() == arc.end()) return events;
            for (long c = (long) Math.floor(arc.start()); c < Math.ceil(arc.end()); c++) {
                Arc visible = new Arc(c, c + 1.0).intersect(arc);
                if (visible == null) continue;
                Arc childArc = new Arc(2 * c + 1.0 - visible.end(), 2 * c + 1.0 - visible.start());
                for (Event e : query(childArc)) {
                    double revStart = 2 * c + 1.0 - e.whole().end();
                    double revEnd = 2 * c + 1.0 - e.whole().start();
                    Arc whole = new Arc(Math.max(c, revStart), Math.min(c + 1.0, revEnd));
                    Arc part = new Arc(Math.max(c, 2 * c + 1.0 - e.part().end()),
                            Math.min(c + 1.0, 2 * c + 1.0 - e.part().start())).intersect(visible);
                    if (part != null) events.add(new Event(whole, part, e.tone(), e.sample(), e.pick()));
                }
            }
            return events;
        };
    }

    /** Continuous bijective swing warping over periods of width T = 2 / subdivision. */
    default Pattern swing(int subdivision, double amount) {
        if (subdivision < 2 || subdivision > 64 || subdivision % 2 != 0)
            throw new IllegalArgumentException("Subdivision must be an even integer in 2..64");
        if (!Double.isFinite(amount) || amount < 0 || amount > 1)
            throw new IllegalArgumentException("Swing amount must be in 0..1");
        double T = 2.0 / subdivision;
        double R = 0.5 + 0.25 * amount;
        return arc -> {
            checkQuery(arc);
            List<Event> events = new ArrayList<>();
            if (arc.start() == arc.end()) return events;
            Arc childArc = new Arc(unwarp(arc.start(), T, R), unwarp(arc.end(), T, R));
            for (Event e : query(childArc)) {
                Arc whole = new Arc(warp(e.whole().start(), T, R), warp(e.whole().end(), T, R));
                Arc part = new Arc(warp(e.part().start(), T, R), warp(e.part().end(), T, R)).intersect(arc);
                if (part != null) events.add(new Event(whole, part, e.tone(), e.sample(), e.pick()));
            }
            return events;
        };
    }

    private static double warp(double t, double T, double R) {
        long k = (long) Math.floor(t / T);
        double tau = Math.max(0.0, Math.min(T, t - k * T));
        double w = tau < 0.5 * T ? 2.0 * R * tau : R * T + 2.0 * (1.0 - R) * (tau - 0.5 * T);
        return k * T + w;
    }

    private static double unwarp(double sigma, double T, double R) {
        long k = (long) Math.floor(sigma / T);
        double tau = Math.max(0.0, Math.min(T, sigma - k * T));
        double u = tau < R * T ? tau / (2.0 * R) : 0.5 * T + (tau - R * T) / (2.0 * (1.0 - R));
        return k * T + u;
    }

    private static void checkQuery(Arc arc) {
        if (arc == null || Math.abs(arc.start()) > 1e9 || Math.abs(arc.end()) > 1e9
                || arc.end() - arc.start() > 4096)
            throw new IllegalArgumentException("Query exceeds supported horizon");
    }
}
