package groove.engine;

import java.util.List;
import java.util.Map;

/** Step 7 P6: delay, filter and reverb state never decays into subnormal doubles. */
final class DenormalTests {
    private static int checks;
    private static final int RATE = LiveRenderer.SAMPLE_RATE;
    private static final double SEED = 1e-305;

    static void run() {
        long startNanos = System.nanoTime();
        reverbShortTail();
        reverbSeeded();
        biquadInput();
        delayFeedback();
        System.out.printf("Denormal snap regressions passed (%d checks) in %.0f ms.%n", checks, (System.nanoTime() - startNanos) / 1e6);
    }

    static void reverbShortTail() {
        // 0.1 s decay reaches 1e-30 (-600 dB, about 10 T60) within 10 s of silence
        var shortTail = new Reverb();
        shortTail.setParams(0.1, 6000, 12000, 0);
        double[] out = new double[2];
        shortTail.process(1.0, 0, out);
        for (int f = 1; f < 10 * RATE; f++) shortTail.process(0, f, out);
        check(shortTail.snappedWrites() > 0, "Short reverb tail is snapped before it turns subnormal");
        check(shortTail.stateClean(), "Short reverb state holds no subnormal or non-finite values");
    }

    static void reverbSeeded() {
        double[] out = new double[2];

        // Longer decays would take 10 s and 200 s to get there, so seed state just above the subnormal
        // range; a 1e-25 seed cannot decay into subnormals within 5 s, so it could not fail without the snap
        for (double decay : new double[] {1, 20}) {
            var reverb = new Reverb();
            reverb.setParams(decay, 6000, 12000, 0);
            reverb.seedState(SEED);
            for (int f = 0; f < 5 * RATE; f++) reverb.process(0, f, out);
            check(reverb.snappedWrites() > 0, "Seeded reverb at decay " + decay + " snaps");
            check(reverb.stateClean(), "Seeded reverb at decay " + decay + " holds no subnormal or non-finite values");
        }

    }

    static void biquadInput() {
        // Filter output below 1e-15 was already zeroed, so the stored input is what could hold a subnormal
        var filter = new Biquad();
        filter.setLowPass(800, 10, RATE);
        filter.seedState(SEED);
        for (int f = 0; f < RATE; f++) filter.process(1e-310);
        check(filter.snappedWrites() > 0 && filter.stateClean(), "Resonant biquad fed subnormal input snaps and stays clean");

    }

    static void delayFeedback() {
        double[] out = new double[2];
        // Delay feedback at gain 0.5 feeding a resonant filter
        Graph graph = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)),
                new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 12000.0)),
                new Graph.Node("filter", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, 800.0, NodeParam.RESONANCE_Q, 10.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "mix"), Graph.edge("mix", "delay"),
                        Graph.edge("delay", "mix"), Graph.edge("mix", "filter"),
                        new Graph.Edge("filter", "out", "out", "audio")));
        var signals = GraphCompiler.compile(graph).signals();
        var runtime = signals.runtime(new SessionState(1, 0, 0, 120, true, graph));
        runtime.seedState(SEED);
        double[][] sources = new double[signals.sourceCount()][2];
        var triggers = new LookaheadScheduler.Window[signals.triggerCount()];
        for (int f = 0; f < 5 * RATE; f++) runtime.process(sources, triggers, out, Math.round(f * 1e9 / RATE));
        check(runtime.snappedWrites() > 0, "Seeded delay feedback and filter snap");
        check(runtime.stateClean(), "Delay and filter state holds no subnormal or non-finite values");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
