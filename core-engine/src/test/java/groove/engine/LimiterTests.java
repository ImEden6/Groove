package groove.engine;

import java.util.*;

/** Master output safety net: SPECS.md promises non-bypassable tanh soft saturation as the
 *  final output stage (LiveRenderer.render), but that only holds if nothing upstream ever
 *  hands it a non-finite sample -- tanh(NaN) is NaN. These checks push both the raw Biquad
 *  filter and a legally-compiled worst-case graph as hard as the engine's own budgets allow,
 *  and assert every rendered sample stays finite and within tanh's own [-1, 1] bound. */
final class LimiterTests {
    static void run() {
        biquadStability();
        adversarialStack();
        System.out.println("Master limiter regressions passed.");
    }

    /** A full-scale square-wave step train is the transient most likely to ring a resonant
     *  filter into instability, swept across the entire legal Q and cutoff range. Biquad
     *  already self-heals a non-finite sample (see Biquad.process), so this is a regression
     *  guard on that contract, not just on the RBJ coefficients being stable. */
    private static void biquadStability() {
        for (double q : new double[]{.1, 1, 4, 20}) {
            for (double cutoff : new double[]{20, 100, 1000, 10000, 19999}) {
                Biquad filter = new Biquad();
                filter.setLowPass(cutoff, q, 48000);
                for (int i = 0; i < 48000; i++) {
                    double x = (i % 100) < 50 ? 1 : -1;
                    double y = filter.process(x);
                    check(Double.isFinite(y), "Biquad stays finite at Q=" + q + ", cutoff=" + cutoff);
                }
            }
        }
    }

    private static Graph.Node n(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }
    private static Graph.Edge out(String from) { return new Graph.Edge(from, "out", "out", "audio"); }

    /** Stacks everything a legally compiled graph can carry at once: eight independent
     *  MAX_AUDIO_SOURCES branches, each four full-gain simultaneous tones (STACK) through a
     *  max-resonance filter, all summed at unity gain into a second max-resonance filter and
     *  a near-lossless delay feedback loop. Well within GraphCompiler's node/event budgets
     *  (60 of 64 nodes, 32 of 128 events), so this compiles as a genuinely valid patch, not
     *  a malformed one -- the point is what a legitimate extreme patch can still do. */
    private static void adversarialStack() {
        var nodes = new ArrayList<Graph.Node>();
        var edges = new ArrayList<Graph.Edge>();
        nodes.add(n("out", NodeType.OUTPUT, Map.of()));
        nodes.add(n("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)));
        nodes.add(n("post", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, 220.0, NodeParam.RESONANCE_Q, 20.0)));
        for (int s = 0; s < SignalGraph.MAX_AUDIO_SOURCES; s++) {
            String stackId = "stack" + s, renderId = "render" + s, filterId = "filter" + s;
            nodes.add(n(stackId, NodeType.STACK, Map.of()));
            for (int v = 0; v < 4; v++) {
                String toneId = "tone" + s + "_" + v;
                nodes.add(n(toneId, NodeType.TONE, Map.of(NodeParam.FREQUENCY, 55.0 * (v + 1), NodeParam.GAIN, 1.0)));
                edges.add(Graph.edge(toneId, stackId));
            }
            nodes.add(n(renderId, NodeType.AUDIO_RENDER, Map.of()));
            edges.add(Graph.edge(stackId, renderId));
            nodes.add(n(filterId, NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, 220.0, NodeParam.RESONANCE_Q, 20.0)));
            edges.add(Graph.edge(renderId, filterId));
            edges.add(Graph.edge(filterId, "mix"));
        }
        nodes.add(n("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 64.0)));
        edges.add(Graph.edge("mix", "post"));
        edges.add(Graph.edge("post", "delay"));
        edges.add(Graph.edge("delay", "mix")); // near-lossless feedback: mix gain is unity
        edges.add(out("post"));
        Graph graph = new Graph(3, nodes, edges);
        LoopPlan plan = GraphCompiler.compile(graph); // throws if this ever stops being a legal graph
        var state = new SessionState(1, 0, 0, 120, true, graph);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, plan), null);
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(timeline);
        float[] block = new float[512];
        boolean sawLoudAudio = false;
        for (int at = 0; at < 2 * 48000; at += 256) {
            long now = Math.round(at * 1e9 / 48000);
            timeline.prepare(now);
            renderer.render(block, 256, now);
            for (float sample : block) {
                check(Float.isFinite(sample), "Adversarial stack output stays finite");
                check(Math.abs(sample) <= 1.0f, "Adversarial stack never exceeds tanh's own bound");
                if (Math.abs(sample) > .5f) sawLoudAudio = true;
            }
        }
        check(sawLoudAudio, "Adversarial fixture is actually loud, not silently trivial");
        check(renderer.scheduleMisses() == 0, "Adversarial stack schedules cleanly");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
