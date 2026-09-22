package groove.engine;

import groove.engine.samples.AssetRef;
import groove.engine.samples.FactorySamples;
import groove.engine.samples.SampleData;
import java.util.*;

/** A control picks a sample slice as each note starts. */
final class SliceIndexTests {
    private static final int RATE = LiveRenderer.SAMPLE_RATE, BLOCK = 256;
    private static final AssetRef REF = FactorySamples.ref("factory:basic/kick.wav");
    /** Each quarter of the test sample is a different sine. */
    private static final double[] SLICE_HZ = {220, 330, 440, 660};
    private static final int[] ORDER = {2, 0, 3, 1};
    private static int checks;

    static void run() {
        compileRules();
        steppedSlices();
        lateJoin();
        System.out.println("Slice index checks passed (" + checks + " checks).");
    }

    private static SampleData quarters() {
        float[] pcm = new float[RATE];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (.5 * Math.sin(2 * Math.PI * SLICE_HZ[i / (RATE / 4)] * i / RATE));
        return new SampleData(RATE, 1, pcm);
    }

    private static Graph.Node node(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }

    /** Four notes a cycle from one sample, sliced in four, the slice picked by a step sequence. */
    private static Graph graph(boolean wired) {
        Map<String, Double> steps = new HashMap<>(Map.of(NodeParam.STEPS, 4.0, NodeParam.RATE, 1.0));
        for (int i = 0; i < 4; i++) steps.put(NodeParam.VALUES[i], (ORDER[i] + .5) / 4);
        var edges = new ArrayList<>(List.of(Graph.edge("hit", "four"), Graph.edge("four", "slice"), Graph.edge("slice", "render"),
                new Graph.Edge("render", "out", "out", "audio")));
        if (wired) edges.add(new Graph.Edge("seq", "out", "slice", "index"));
        var nodes = new ArrayList<>(List.of(new Graph.Node("hit", NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, .8), REF, null),
                node("four", NodeType.FAST, Map.of(NodeParam.FACTOR, 4.0)),
                node("slice", NodeType.SAMPLE_SLICE, Map.of(NodeParam.SLICES, 4.0, NodeParam.INDEX, 1.0)),
                node("render", NodeType.AUDIO_RENDER, Map.of()), node("out", NodeType.OUTPUT, Map.of())));
        if (wired) nodes.add(node("seq", NodeType.STEP_SEQUENCE, steps));
        return new Graph(3, nodes, edges);
    }

    private static void compileRules() {
        check(GraphCompiler.compile(graph(true)).sampleVoices().size() == 4, "A controlled slice prepares every slice");
        check(GraphCompiler.compile(graph(false)).sampleVoices().size() == 1, "An uncontrolled slice prepares only its own");
    }

    private static void steppedSlices() {
        float[] audio = render(graph(true), 0, seconds(4));
        for (int cycle = 0; cycle < 2; cycle++)
            for (int n = 0; n < 4; n++) {
                double start = cycle * 2 + n * .5;
                close(pitch(audio, 0, start + .05, start + .2), SLICE_HZ[ORDER[n]], 2, "Step " + n + " of cycle " + cycle + " plays its slice");
            }
        float[] fixed = render(graph(false), 0, seconds(1));
        close(pitch(fixed, 0, .55, .7), SLICE_HZ[1], 2, "Unwired, every note plays the index slice");
    }

    /** A renderer joining mid-note starts the slice the note picked at its onset. */
    private static void lateJoin() {
        long join = seconds(.55);
        float[] audio = render(graph(true), join, seconds(2));
        close(pitch(audio, join, .6, .7), SLICE_HZ[ORDER[1]], 2, "A late joiner hears the slice it joined");
    }

    private static long seconds(double s) { return Math.round(s * 1e9); }

    private static float[] render(Graph graph, long from, long to) {
        var state = new SessionState(1, 0, 0, 120, true, graph);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(graph), Map.of(REF, quarters())), null);
        var renderer = new LiveRenderer();
        renderer.publish(timeline);
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
}
