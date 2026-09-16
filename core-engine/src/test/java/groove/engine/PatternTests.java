package groove.engine;

import java.util.*;

/** Continuous swing warping and pattern reversal regressions. */
final class PatternTests {
    private static final Tone TONE_A = new Tone(Tone.Wave.SINE, 220, .3, 0, 20000);
    private static final Tone TONE_B = new Tone(Tone.Wave.SINE, 440, .3, 0, 20000);

    static void run() {
        testReverseInvolution();
        testReverseEuclid();
        testReverseMultiCycle();
        testSwingBijection();
        testSwingRatios();
        testPartitionIndependence();
        testComposition();
        testGraphCompilerValidation();
        System.out.println("Pattern tests passed.");
    }

    private static void testReverseInvolution() {
        Pattern base = Pattern.stack(
                Pattern.tone(TONE_A).euclid(8, 3, 1),
                Pattern.tone(TONE_B).euclid(16, 5, -2)
        );
        Pattern doubleRev = base.reverse().reverse();
        Arc window = new Arc(-2, 3);
        List<Event> originalEvents = new ArrayList<>(base.query(window));
        List<Event> recoveredEvents = new ArrayList<>(doubleRev.query(window));
        check(originalEvents.size() == recoveredEvents.size(), "Reverse involution preserves count");
        Comparator<Event> comp = Comparator
                .comparingDouble((Event e) -> e.whole().start())
                .thenComparingDouble(e -> e.whole().end())
                .thenComparingDouble(e -> e.part().start())
                .thenComparingDouble(e -> e.part().end())
                .thenComparingDouble(e -> e.tone() != null ? e.tone().frequency() : 0);
        originalEvents.sort(comp);
        recoveredEvents.sort(comp);
        for (int i = 0; i < originalEvents.size(); i++) {
            Event orig = originalEvents.get(i);
            Event rec = recoveredEvents.get(i);
            check(Math.abs(orig.whole().start() - rec.whole().start()) < 1e-9, "Involution whole start match");
            check(Math.abs(orig.whole().end() - rec.whole().end()) < 1e-9, "Involution whole end match");
            check(Math.abs(orig.part().start() - rec.part().start()) < 1e-9, "Involution part start match");
            check(Math.abs(orig.part().end() - rec.part().end()) < 1e-9, "Involution part end match");
            check(Objects.equals(orig.tone(), rec.tone()), "Involution tone match");
        }
    }

    private static void testReverseEuclid() {
        // Euclid(4, 3, 0) has pulses at steps 0, 2, 3 (times [0, 0.25], [0.5, 0.75], [0.75, 1.0])
        Pattern p = Pattern.tone(TONE_A).euclid(4, 3, 0).reverse();
        List<Event> events = p.query(new Arc(0, 1));
        check(events.size() == 3, "Reversed Euclid has 3 pulses");
        events.sort(Comparator.comparingDouble(e -> e.whole().start()));
        // In reverse within [0, 1):
        // [0.75, 1.0] -> [0.0, 0.25]
        // [0.5, 0.75] -> [0.25, 0.5]
        // [0, 0.25]   -> [0.75, 1.0]
        check(Math.abs(events.get(0).whole().start() - 0.00) < 1e-9, "First reversed pulse at 0.00");
        check(Math.abs(events.get(0).whole().end() - 0.25) < 1e-9, "First reversed pulse ends at 0.25");
        check(Math.abs(events.get(1).whole().start() - 0.25) < 1e-9, "Second reversed pulse at 0.25");
        check(Math.abs(events.get(1).whole().end() - 0.50) < 1e-9, "Second reversed pulse ends at 0.50");
        check(Math.abs(events.get(2).whole().start() - 0.75) < 1e-9, "Third reversed pulse at 0.75");
        check(Math.abs(events.get(2).whole().end() - 1.00) < 1e-9, "Third reversed pulse ends at 1.00");
    }

    private static void testReverseMultiCycle() {
        // fast(0.5) creates notes of length 2 cycles, e.g. [0, 2)
        Pattern slow = Pattern.tone(TONE_A).fast(0.5);
        Pattern revSlow = slow.reverse();
        List<Event> events = revSlow.query(new Arc(0, 1));
        check(!events.isEmpty(), "Reversed multi-cycle note present");
        for (Event e : events) {
            check(e.whole().start() >= 0, "Reversed multi-cycle note whole start not negative");
            check(e.whole().end() <= 1.0, "Reversed multi-cycle note whole end clamped to cycle");
        }
    }

    private static void testSwingBijection() {
        int[] subdivisions = {2, 4, 8, 16, 32, 64};
        double[] amounts = {0.0, 0.2, 0.333, 0.5, 0.667, 0.75, 1.0};
        Pattern p = Pattern.tone(TONE_A).fast(16);
        for (int sub : subdivisions) {
            for (double amt : amounts) {
                Pattern sw = p.swing(sub, amt);
                List<Event> events = sw.query(new Arc(-2, 3));
                check(!events.isEmpty(), "Swung events not empty");
                for (Event e : events) {
                    check(e.whole().start() <= e.whole().end(), "Whole arc ordering");
                    check(e.part().start() <= e.part().end(), "Part arc ordering");
                }
            }
        }
    }

    private static void testSwingRatios() {
        // 16th notes: subdivision = 16. Period T = 2 / 16 = 0.125.
        // A note at [0, 0.0625] (downbeat) and [0.0625, 0.125] (upbeat).
        Pattern base = Pattern.tone(TONE_A).fast(16);

        // Test straight (amount = 0 => R = 0.5)
        Pattern straight = base.swing(16, 0.0);
        List<Event> stEvents = straight.query(new Arc(0, 0.125));
        stEvents.sort(Comparator.comparingDouble(e -> e.whole().start()));
        check(stEvents.size() == 2, "Straight has 2 notes");
        check(Math.abs(stEvents.get(0).whole().start() - 0.0) < 1e-9, "Downbeat start straight");
        check(Math.abs(stEvents.get(0).whole().end() - 0.0625) < 1e-9, "Downbeat end straight");
        check(Math.abs(stEvents.get(1).whole().start() - 0.0625) < 1e-9, "Upbeat start straight");

        // Test dotted-eighth / 3:1 swing (amount = 1.0 => R = 0.75)
        // Downbeat maps to [0, 0.75 * 0.125] = [0, 0.09375].
        // Upbeat maps to [0.09375, 0.125].
        Pattern hard = base.swing(16, 1.0);
        List<Event> hardEvents = hard.query(new Arc(0, 0.125));
        hardEvents.sort(Comparator.comparingDouble(e -> e.whole().start()));
        check(hardEvents.size() == 2, "Hard swing has 2 notes");
        check(Math.abs(hardEvents.get(0).whole().start() - 0.0) < 1e-9, "Downbeat start hard");
        check(Math.abs(hardEvents.get(0).whole().end() - 0.09375) < 1e-9, "Downbeat end hard (3:1)");
        check(Math.abs(hardEvents.get(1).whole().start() - 0.09375) < 1e-9, "Upbeat start hard (3:1)");
        check(Math.abs(hardEvents.get(1).whole().end() - 0.125) < 1e-9, "Upbeat end hard");

        // Test triplet 2:1 swing (amount = 2.0 / 3.0 => R = 0.5 + 0.25 * (2/3) = 2/3)
        // Downbeat maps to [0, (2/3) * 0.125] = [0, 0.125 * 2 / 3].
        Pattern triplet = base.swing(16, 2.0 / 3.0);
        List<Event> tripEvents = triplet.query(new Arc(0, 0.125));
        tripEvents.sort(Comparator.comparingDouble(e -> e.whole().start()));
        check(tripEvents.size() == 2, "Triplet swing has 2 notes");
        double expectedBoundary = (2.0 / 3.0) * 0.125;
        check(Math.abs(tripEvents.get(0).whole().end() - expectedBoundary) < 1e-9, "Downbeat end triplet (2:1)");
        check(Math.abs(tripEvents.get(1).whole().start() - expectedBoundary) < 1e-9, "Upbeat start triplet (2:1)");
    }

    private static void testPartitionIndependence() {
        Pattern pattern = Pattern.stack(
                Pattern.tone(TONE_A).euclid(8, 5, 0).reverse(),
                Pattern.tone(TONE_B).euclid(16, 7, 2).swing(16, 0.6)
        );
        Arc fullWindow = new Arc(0, 1);
        List<Event> full = pattern.query(fullWindow);

        // Partition [0, 1) into [0, 0.3), [0.3, 0.7), [0.7, 1.0)
        Arc[] parts = {new Arc(0, 0.3), new Arc(0.3, 0.7), new Arc(0.7, 1.0)};
        List<Event> partitioned = new ArrayList<>();
        for (Arc partArc : parts) {
            partitioned.addAll(pattern.query(partArc));
        }

        double fullPartSum = full.stream().mapToDouble(e -> e.part().end() - e.part().start()).sum();
        double partSum = partitioned.stream().mapToDouble(e -> e.part().end() - e.part().start()).sum();
        check(Math.abs(fullPartSum - partSum) < 1e-9, "Coverage identical between full and partitioned query");

        long uniqueFullWholes = full.stream().map(Event::whole).distinct().count();
        long uniquePartWholes = partitioned.stream().map(Event::whole).distinct().count();
        check(uniqueFullWholes == uniquePartWholes, "Same number of unique notes in partitioned query");
    }

    private static void testComposition() {
        Pattern p = Pattern.tone(TONE_A).euclid(8, 3, 0);
        Pattern revSwing = p.reverse().swing(8, 0.5);
        Pattern swingRev = p.swing(8, 0.5).reverse();

        List<Event> rsEvents = revSwing.query(new Arc(0, 2));
        List<Event> srEvents = swingRev.query(new Arc(0, 2));
        check(rsEvents.size() == 6, "revSwing note count over 2 cycles");
        check(srEvents.size() == 6, "swingRev note count over 2 cycles");
        for (Event e : rsEvents) {
            check(e.whole().start() >= 0 && e.whole().end() <= 2.0, "revSwing arcs within range");
        }
        for (Event e : srEvents) {
            check(e.whole().start() >= 0 && e.whole().end() <= 2.0, "swingRev arcs within range");
        }
    }

    private static void testGraphCompilerValidation() {
        // Test REVERSE node
        Graph.Node tone = new Graph.Node("t", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 440.0));
        Graph.Node rev = new Graph.Node("r", NodeType.REVERSE, Map.of());
        Graph.Node out = new Graph.Node("o", NodeType.OUTPUT, Map.of());
        Graph gRev = new Graph(3, List.of(tone, rev, out), List.of(
                new Graph.Edge("t", "out", "r", "in"),
                new Graph.Edge("r", "out", "o", "in")
        ));
        LoopPlan planRev = GraphCompiler.compile(gRev);
        check(planRev.pattern() != null, "REVERSE node compiles successfully");

        // Test SWING node
        Graph.Node sw = new Graph.Node("s", NodeType.SWING, Map.of(NodeParam.SUBDIVISION, 16.0, NodeParam.AMOUNT, 0.5));
        Graph gSw = new Graph(3, List.of(tone, sw, out), List.of(
                new Graph.Edge("t", "out", "s", "in"),
                new Graph.Edge("s", "out", "o", "in")
        ));
        LoopPlan planSw = GraphCompiler.compile(gSw);
        check(planSw.pattern() != null, "SWING node compiles successfully");

        // Test invalid subdivision (odd)
        Graph.Node badSub = new Graph.Node("s", NodeType.SWING, Map.of(NodeParam.SUBDIVISION, 15.0, NodeParam.AMOUNT, 0.5));
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(tone, badSub, out), List.of(
                new Graph.Edge("t", "out", "s", "in"),
                new Graph.Edge("s", "out", "o", "in")
        ))));

        // Test invalid subdivision (> 64)
        Graph.Node badSubMax = new Graph.Node("s", NodeType.SWING, Map.of(NodeParam.SUBDIVISION, 66.0, NodeParam.AMOUNT, 0.5));
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(tone, badSubMax, out), List.of(
                new Graph.Edge("t", "out", "s", "in"),
                new Graph.Edge("s", "out", "o", "in")
        ))));

        // Test invalid amount (< 0 or > 1)
        Graph.Node badAmtMin = new Graph.Node("s", NodeType.SWING, Map.of(NodeParam.SUBDIVISION, 16.0, NodeParam.AMOUNT, -0.1));
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(tone, badAmtMin, out), List.of(
                new Graph.Edge("t", "out", "s", "in"),
                new Graph.Edge("s", "out", "o", "in")
        ))));
        Graph.Node badAmtMax = new Graph.Node("s", NodeType.SWING, Map.of(NodeParam.SUBDIVISION, 16.0, NodeParam.AMOUNT, 1.1));
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(tone, badAmtMax, out), List.of(
                new Graph.Edge("t", "out", "s", "in"),
                new Graph.Edge("s", "out", "o", "in")
        ))));

        // Test unknown param rejection
        Graph.Node badParam = new Graph.Node("r", NodeType.REVERSE, Map.of("extra", 1.0));
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(tone, badParam, out), List.of(
                new Graph.Edge("t", "out", "r", "in"),
                new Graph.Edge("r", "out", "o", "in")
        ))));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid argument rejection");
    }
}
