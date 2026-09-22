package groove.engine;

import java.util.*;

final class QuantizeTests {
    private static final int RATE = LiveRenderer.SAMPLE_RATE, BLOCK = 256;
    private static final double BPM = 120, NOTE = 0.5;
    /** Major degrees 0, 2, 4 and 7 from A3. */
    private static final double[] MELODY = {220, 220 * Math.pow(2, 4 / 12.0), 220 * Math.pow(2, 7 / 12.0), 440};
    private static int checks;

    static void run() {
        mapping();
        compileRules();
        stepMelody();
        oddGrid();
        lateJoin();
        transposedAfter();
        worldDriven();
        System.out.println("Quantize checks passed (" + checks + " checks).");
    }

    private static void mapping() {
        var d = new Event.Degree("c", Pitch.Scale.MAJOR, 0, 7);
        check(d.degree(0) == 0 && d.degree(1) == 7 && d.degree(.4999) == 3 && d.degree(.5) == 4, "0..1 spans low..high in equal steps");
        check(d.degree(Double.NaN) == 0 && d.degree(-3) == 0 && d.degree(5) == 7, "Out-of-range control clamps");
        check(new Event.Degree("c", Pitch.Scale.MAJOR, 3, 3).degree(.9) == 3, "A single degree ignores the control");
        invalid(() -> new Event.Degree("c", Pitch.Scale.MAJOR, 4, 3));
    }

    private static Graph.Node node(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }

    private static Map<String, Double> quantize(double low, double high) {
        return Map.of(NodeParam.ROOT, 57.0, NodeParam.SCALE, 0.0, NodeParam.LOW, low, NodeParam.HIGH, high);
    }

    /** Four notes a cycle through a quantizer, optionally transposed, whose degree comes from control. */
    private static Graph melody(Graph.Node control, double semitones) {
        var nodes = new ArrayList<>(List.of(node("tone", NodeType.TONE, Map.of(NodeParam.GAIN, .5)),
                node("four", NodeType.FAST, Map.of(NodeParam.FACTOR, 4.0)), node("q", NodeType.QUANTIZE, quantize(0, 7)),
                node("up", NodeType.TRANSPOSE, Map.of(NodeParam.SEMITONES, semitones)),
                node("render", NodeType.AUDIO_RENDER, Map.of()), node("out", NodeType.OUTPUT, Map.of()), control));
        return new Graph(3, nodes, List.of(Graph.edge("tone", "four"), Graph.edge("four", "q"), Graph.edge("q", "up"),
                Graph.edge("up", "render"), new Graph.Edge(control.id(), "out", "q", "degree"), new Graph.Edge("render", "out", "out", "audio")));
    }

    private static Graph.Node steps() {
        Map<String, Double> p = new HashMap<>(Map.of(NodeParam.STEPS, 4.0, NodeParam.RATE, 1.0));
        int[] degrees = {0, 2, 4, 7};
        for (int i = 0; i < 4; i++) p.put(NodeParam.VALUES[i], (degrees[i] + .5) / 8);
        return node("seq", NodeType.STEP_SEQUENCE, p);
    }

    private static void compileRules() {
        Graph plain = new Graph(3, List.of(node("tone", NodeType.TONE, Map.of()), node("q", NodeType.QUANTIZE, quantize(2, 7)),
                node("out", NodeType.OUTPUT, Map.of())), List.of(Graph.edge("tone", "q"), Graph.edge("q", "out")));
        close(GraphCompiler.compile(plain).event(0).tone().frequency(), MELODY[1], 1e-9, "An unwired quantizer plays degree low");
        check(GraphCompiler.compile(plain).event(0).degree() == null, "An unwired quantizer leaves no tag");
        invalid(() -> GraphCompiler.compile(replace(plain, "q", quantize(5, 4))));
        invalid(() -> GraphCompiler.compile(replace(plain, "q", Map.of(NodeParam.ROOT, 127.0, NodeParam.LOW, 7.0, NodeParam.HIGH, 7.0))));
        invalid(() -> GraphCompiler.compile(replace(plain, "q", Map.of(NodeParam.ROOT, 1.0))));
        invalid(() -> GraphCompiler.compile(replace(plain, "q", Map.of(NodeParam.CHANCE, 1.0))));
        // The top of the range is checked once transposed, not only the root
        invalid(() -> GraphCompiler.compile(replace(melody(steps(), 24), "q", quantize(0, 35))));
        GraphCompiler.compile(replace(melody(steps(), 0), "q", quantize(0, 35)));
    }

    private static Graph replace(Graph g, String id, Map<String, Double> params) {
        return new Graph(g.version(), g.nodes().stream().map(n -> n.id().equals(id) ? new Graph.Node(id, n.type(), params) : n).toList(), g.edges());
    }

    private static void stepMelody() {
        float[] audio = render(new LiveRenderer(), melody(steps(), 0), 0, seconds(4));
        for (int cycle = 0; cycle < 2; cycle++)
            for (int n = 0; n < 4; n++) {
                double start = cycle * 2 + n * NOTE;
                close(pitch(audio, 0, start + .1, start + .4), MELODY[n], .5, "Step " + n + " of cycle " + cycle + " plays its degree");
            }
    }

    /** Onsets that land on a step edge only up to rounding still read the step they start. */
    private static void oddGrid() {
        for (double bpm : new double[] {97.3, 137.7, 211.1}) {
            // A server clock value and anchor like a long-running session's, where onsets round both ways
            long effective = 6_588_672_394_716L;
            double anchor = 410.0362353821504, secondsPerCycle = 240 / bpm;
            Graph graph = melody(steps(), 0);
            var state = new SessionState(1, effective, anchor, bpm, true, graph);
            var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(graph)), null);
            var renderer = new LiveRenderer();
            renderer.publish(timeline);
            long to = effective + seconds(3 * secondsPerCycle);
            float[] audio = render(renderer, timeline, effective, to);
            for (int k = 1642; k < 1652; k++) {
                double start = (k / 4.0 - anchor) * secondsPerCycle, length = secondsPerCycle / 4;
                close(pitch(audio, 0, start + .2 * length, start + .8 * length), MELODY[k % 4], .5, "Step " + k + " at " + bpm + " BPM");
            }
        }
    }

    /** A renderer starting mid-note reads the control at the note's onset, not when it joined. */
    private static void lateJoin() {
        long join = seconds(0.6);
        float[] audio = render(new LiveRenderer(), melody(steps(), 0), join, seconds(2));
        close(pitch(audio, join, .7, .95), MELODY[1], .5, "A late joiner hears the note it joined");
        close(pitch(audio, join, 1.1, 1.4), MELODY[2], .5, "Then the next step");
    }

    private static void transposedAfter() {
        float[] audio = render(new LiveRenderer(), melody(steps(), 12), 0, seconds(2));
        for (int n = 0; n < 4; n++)
            close(pitch(audio, 0, n * NOTE + .1, n * NOTE + .4), 2 * MELODY[n], 1, "A later transpose shifts step " + n);
    }

    private static void worldDriven() {
        Graph graph = melody(node("rain", NodeType.WORLD, Map.of(NodeParam.SOURCE, (double) WorldInputs.RAIN, NodeParam.SMOOTH, 0.0)), 0);
        var renderer = new LiveRenderer();
        double[] values = new double[WorldInputs.COUNT];
        Arrays.fill(values, Double.NaN);
        values[WorldInputs.RAIN] = .99;
        renderer.world().update(values);
        float[] audio = render(renderer, graph, 0, seconds(1));
        close(pitch(audio, 0, .1, .4), MELODY[3], .5, "Heavy rain picks the top degree");
    }

    private static long seconds(double s) { return Math.round(s * 1e9); }

    private static float[] render(LiveRenderer renderer, Graph graph, long from, long to) {
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(new SessionState(1, 0, 0, BPM, true, graph), GraphCompiler.compile(graph)), null);
        renderer.publish(timeline);
        return render(renderer, timeline, from, to);
    }

    private static float[] render(LiveRenderer renderer, LiveRenderer.Timeline timeline, long from, long to) {
        int frames = (int) Math.round((to - from) * RATE / 1e9);
        float[] out = new float[frames * 2], block = new float[BLOCK * 2];
        for (int at = 0; at < frames; at += BLOCK) {
            int n = Math.min(BLOCK, frames - at);
            long now = from + Math.round(at * 1e9 / RATE);
            timeline.prepare(now);
            renderer.render(block, n, now);
            System.arraycopy(block, 0, out, at * 2, n * 2);
        }
        return out;
    }

    /** Frequency from the first and last rising zero crossings in [from, to) seconds, interpolated. */
    private static double pitch(float[] audio, long origin, double from, double to) {
        int a = (int) Math.round((seconds(from) - origin) * RATE / 1e9), b = (int) Math.round((seconds(to) - origin) * RATE / 1e9);
        double first = -1, last = -1;
        int count = 0;
        for (int i = a; i < b; i++) {
            double x = audio[i * 2], y = audio[i * 2 + 2];
            if (x < 0 && y >= 0) {
                double at = i + x / (x - y);
                if (first < 0) first = at;
                last = at; count++;
            }
        }
        check(count > 2, "Audible note in " + from + ".." + to);
        return (count - 1) * RATE / (last - first);
    }

    private static void close(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance, message + ": " + actual + " != " + expected);
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
}
