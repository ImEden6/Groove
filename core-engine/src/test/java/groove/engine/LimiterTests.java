package groove.engine;

import java.util.*;

/** Master output safety net: SPECS.md promises non-bypassable tanh soft saturation as the
 *  final output stage (LiveRenderer.render), but that only holds if nothing upstream ever
 *  hands it a non-finite sample -- tanh(NaN) is NaN. These checks push both the raw Biquad
 *  filter and a legally-compiled high-load graph as hard as the engine's own budgets allow,
 *  and assert every rendered sample stays finite and within tanh's own [-1, 1] bound. */
final class LimiterTests {
    static void run() {
        biquadStability();
        adversarialStack();
        softSaturation(false);
        softSaturation(true);
        System.out.println("Master limiter regressions passed.");
    }

    /** A full-scale square-wave step train is the transient most likely to ring a resonant
     *  filter into instability, swept at representative Q/cutoff values including the legal endpoints. Biquad
     *  already self-heals a non-finite sample (see Biquad.process), so this is a regression
     *  guard on that contract, not just on the RBJ coefficients being stable. */
    private static void biquadStability() {
        for (double q : new double[]{.1, 1, 4, 20}) {
            for (double cutoff : new double[]{20, 100, 1000, 10000, 20000}) {
                Biquad filter = new Biquad();
                filter.setLowPass(cutoff, q, 48000);
                String context = " at Q=" + q + ", cutoff=" + cutoff;
                for (int i = 0; i < LiveRenderer.SAMPLE_RATE; i++) {
                    double x = (i % 100) < 50 ? 1 : -1;
                    double y = filter.process(x);
                    check(Double.isFinite(y), "Biquad stays finite" + context);
                }
                for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                    filter.process(1); // Contaminate history before testing recovery.
                    check(filter.process(bad) == 0, "Non-finite filter input is silenced" + context);
                    Biquad fresh = new Biquad(); fresh.setLowPass(cutoff, q, LiveRenderer.SAMPLE_RATE);
                    for (int i = 0; i < 128; i++) {
                        double input = i == 0 ? .5 : 0;
                        check(filter.process(input) == fresh.process(input), "Filter history resets after non-finite input" + context);
                    }
                }
            }
        }
    }

    /** Constant stereo PCM has a known settled sum: left +1 and right -2.
     *  Check the actual soft-saturation curve, not merely bounds (hard clipping or
     *  a silent channel would satisfy a peak-ceiling assertion alone). */
    private static void softSaturation(boolean signals) {
        var ref = groove.engine.samples.FactorySamples.ref("factory:basic/kick.wav");
        var nodes = new ArrayList<Graph.Node>(); var edges = new ArrayList<Graph.Edge>();
        nodes.add(n("sum", NodeType.STACK, Map.of())); nodes.add(n("out", NodeType.OUTPUT, Map.of()));
        for (int i = 0; i < 4; i++) {
            nodes.add(new Graph.Node("sample" + i, NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, 1.0), ref));
            edges.add(Graph.edge("sample" + i, "sum"));
        }
        if (signals) {
            nodes.add(n("render", NodeType.AUDIO_RENDER, Map.of()));
            edges.add(Graph.edge("sum", "render")); edges.add(out("render"));
        } else edges.add(Graph.edge("sum", "out"));
        Graph graph = new Graph(signals ? 3 : 2, nodes, edges);
        var plan = GraphCompiler.compile(graph);
        var state = new SessionState(1, 0, 0, 120, true, graph);
        LiveRenderer renderer = new LiveRenderer();
        float[] block = new float[512];
        int at = 0;
        for (int polarity : new int[]{1, -1}) {
            float[] pcm = new float[LiveRenderer.SAMPLE_RATE * 2];
            for (int i = 0; i < pcm.length; i += 2) { pcm[i] = .25f * polarity; pcm[i + 1] = -.5f * polarity; }
            var data = new groove.engine.samples.SampleData(LiveRenderer.SAMPLE_RATE, 2, pcm);
            var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, plan, Map.of(ref, data)), null);
            renderer.publish(timeline); // Second pass also covers publication crossfade.
            int start = at;
            for (; at < start + 4096; at += 256) {
                long now = Math.round(at * 1e9 / LiveRenderer.SAMPLE_RATE);
                timeline.prepare(now); renderer.render(block, 256, now);
                for (int i = 0; i < block.length; i += 2) {
                    check(Float.isFinite(block[i]) && Float.isFinite(block[i + 1]), "Both limiter channels remain finite");
                    check(Math.abs(block[i]) <= 1 && Math.abs(block[i + 1]) <= 1, "Crossfade stays within ceiling");
                    if (at - start >= 1024) {
                        check(Math.abs(block[i] - Math.tanh(polarity)) < 1e-5, "Left output follows tanh, not hard clipping or bypass");
                        check(Math.abs(block[i + 1] - Math.tanh(-2 * polarity)) < 1e-5, "Right output follows tanh with independent polarity");
                    }
                }
            }
        }
        check(renderer.scheduleMisses() == 0, "Reference fixture schedules cleanly");
    }

    private static Graph.Node n(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }
    private static Graph.Edge out(String from) { return new Graph.Edge(from, "out", "out", "audio"); }

    /** Stacks eight render branches and 32 simultaneous voices: eight independent
     *  MAX_AUDIO_SOURCES branches, each four full-gain simultaneous tones (STACK) through a
     *  max-resonance filter, all summed at unity gain into a second max-resonance filter and
     *  a unity-gain delay feedback loop. Well within GraphCompiler's node/event budgets
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
        edges.add(Graph.edge("delay", "mix")); // unity-gain feedback, bounded by the signal runtime
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
