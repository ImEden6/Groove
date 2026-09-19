package groove.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** The loop-gain limit on every feedback loop, free-running delays, and saved-patch migration. */
final class FeedbackLoopTests {
    private static final double LIMIT = SignalGraph.LOOP_GAIN_LIMIT;

    static void run() {
        bounds();
        filterPeakFormula();
        convergence();
        randomGraphs();
        migration();
        System.out.println("Feedback loop checks passed.");
    }

    // === bounds ===

    private static void bounds() {
        check(bound(mixLoop(0.95, Map.of())) == 0.95, "Mix gain is the loop bound");
        accepts(mixLoop(0.95, Map.of()), "Bound at the limit is accepted");
        rejects(mixLoop(0.96, Map.of()), "bound 0.96", "Bound over the limit is rejected");
        accepts(mixLoop(1.0, Map.of(NodeParam.FREE_RUN, 1.0)), "Free-running delay exempts its loop");

        // A delay feeding itself straight back has gain 1.
        Graph self = graph(List.of(tone(), render(), node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)),
                        node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 64.0)), out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("delay", "delay"),
                        Graph.edge("delay", "bus"), toOut("bus")));
        rejects(self, "bound 1.00", "Self-feeding delay is rejected");

        // Filter peaks: low-pass Q 0.707 is 1 x 1.05 margin; band-pass is exactly 1.
        check(approx(bound(filterLoop(0.9, 0, Biquad.DEFAULT_Q)), 0.9 * 1.05), "Butterworth low-pass bound");
        accepts(filterLoop(0.9, 0, Biquad.DEFAULT_Q), "0.945 accepted");
        rejects(filterLoop(0.91, 0, Biquad.DEFAULT_Q), "bound 0.96", "0.9555 rejected");
        accepts(filterLoop(0.95, 2, 20.0), "Band-pass peak is 1 at any Q");
        accepts(filterLoop(0.95, 3, 20.0), "Notch peak is 1 at any Q");
        double q20 = 20.0 / Math.sqrt(1 - 1 / (4.0 * 400)) * 1.05;
        accepts(filterLoop(0.94 / q20, 0, 20.0), "Resonant low-pass just under the limit");
        rejects(filterLoop(0.96 / q20, 1, 20.0), "bound 0.96", "Resonant high-pass just over the limit");

        // A modulated mix gain counts as 1, whatever its knob says.
        Graph modulated = withLfoOn(mixLoop(0.1, Map.of()), "bus", "gain");
        rejects(modulated, "bound 1.00", "Modulated mix gain is bounded by 1");

        // Two delays fed by one bus: each delay's row sums both paths.
        accepts(crossCoupled(0.47, Map.of()), "Cross-coupled 0.94 accepted");
        rejects(crossCoupled(0.48, Map.of()), "bound 0.96", "Cross-coupled 0.96 rejected");
        accepts(crossCoupled(1.0, Map.of("d2", 1.0)), "One free-running delay exempts the whole loop");

        // Separate loops are judged separately; a free-running delay outside any loop changes nothing.
        Graph twoLoops = twoLoops(0.5, 1.0);
        check(SignalGraph.feedbackLoops(twoLoops.nodes(), twoLoops.edges()).size() == 2, "Two separate loops found");
        rejects(twoLoops, "bound 1.00", "Only the over-limit loop fails");
        Graph chainFreeRun = graph(List.of(tone(), render(),
                        node("chain", NodeType.DELAY, Map.of(NodeParam.FRAMES, 64.0, NodeParam.FREE_RUN, 1.0)), out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "chain"), toOut("chain")));
        check(SignalGraph.feedbackLoops(chainFreeRun.nodes(), chainFreeRun.edges()).isEmpty(), "A delay chain is not a loop");
        accepts(chainFreeRun, "Free-run on a delay outside any loop is allowed");
        rejects(mixLoop(0.95, Map.of(NodeParam.FREE_RUN, 2.0)), "Invalid freeRun", "freeRun only takes 0 or 1");
    }

    // === filter peak formula ===

    /** The loop bound's filter peak must cover the real digital response at every cutoff. */
    private static void filterPeakFormula() {
        Biquad.Mode[] modes = Biquad.Mode.values();
        double[] qs = {0.1, 0.5, Biquad.DEFAULT_Q, 0.8, 1, 2, 5, 10, 20};
        double[] cutoffs = {20, 100, 1000, 5000, 15000, 20000};
        Biquad filter = new Biquad();
        for (Biquad.Mode mode : modes) for (double q : qs) for (double cutoff : cutoffs) {
            filter.set(mode, cutoff, q, 48000);
            double peak = measuredPeak(filter);
            boolean resonant = (mode == Biquad.Mode.LOW_PASS || mode == Biquad.Mode.HIGH_PASS) && q > Biquad.DEFAULT_Q;
            double formula = resonant ? q / Math.sqrt(1 - 1 / (4 * q * q)) : 1.0;
            String where = String.format(Locale.ROOT, "%s Q %.3f at %.0f Hz: peak %.6f, formula %.6f", mode, q, cutoff, peak, formula);
            check(peak <= formula * (1 + 1e-6), "Formula covers the measured peak, " + where);
            if (resonant && cutoff <= 15000)
                check(peak >= formula * (1 - 1e-3), "Formula is the exact resonant peak, " + where);
        }
    }

    /** Dense log sweep of |H| from 1 Hz to Nyquist, refined around the largest value. */
    private static double measuredPeak(Biquad filter) {
        double best = 0, bestOmega = 0;
        int points = 20000;
        for (int i = 0; i <= points; i++) {
            double omega = Math.PI * Math.pow(2 * 1.0 / 48000, 1 - (double) i / points);
            double m = filter.responseMagnitude(omega);
            if (m > best) { best = m; bestOmega = omega; }
        }
        double lo = bestOmega * 0.99, hi = Math.min(Math.PI, bestOmega * 1.01);
        for (int i = 0; i < 200; i++) {
            double a = lo + (hi - lo) / 3, b = hi - (hi - lo) / 3;
            if (filter.responseMagnitude(a) < filter.responseMagnitude(b)) lo = a; else hi = b;
        }
        return Math.max(best, filter.responseMagnitude((lo + hi) / 2));
    }

    // === convergence ===

    /** Two listeners who heard different first seconds end up with the same loop contents. */
    private static void convergence() {
        // Plain loop at the limit, 50 ms delay.
        Graph plain = mixLoop(LIMIT, Map.of(NodeParam.FRAMES, 2400.0));
        double plainTime = converge(plain, 1e-6, trips(LIMIT, 1e-6) * 0.05 + 0.5);
        // Resonant low-pass in the loop, its cutoff swept by a 40 Hz LFO: covered by testing, not proof.
        double q = 2.0, peak = q / Math.sqrt(1 - 1 / (4 * q * q)) * 1.05;
        Graph swept = withLfoOn(filterLoop(0.94 / peak, 0, q, 2400), "filter", "cutoff");
        double sweptTime = converge(swept, 1e-6, trips(0.94, 1e-6) * 0.05 + 1.0);
        System.out.printf(Locale.ROOT, "Loop convergence: plain %.2f s, swept filter %.2f s%n", plainTime, sweptTime);

        // Negative control: a free-running unity loop never forgets the difference.
        Graph unity = mixLoop(1.0, Map.of(NodeParam.FRAMES, 2400.0, NodeParam.FREE_RUN, 1.0));
        double[] residual = difference(unity, 10.0);
        check(residual[1] > 1e-3, "Unity-gain loop keeps the listeners apart: " + residual[1]);
    }

    private static int trips(double bound, double tolerance) {
        return (int) Math.ceil(Math.log(tolerance) / Math.log(bound));
    }

    /** Seconds after the shared input until the listeners stay within tolerance; fails past the bound. */
    private static double converge(Graph graph, double tolerance, double boundSeconds) {
        double[] result = difference(graph, boundSeconds + 1.0, tolerance);
        check(result[0] <= boundSeconds, String.format(Locale.ROOT,
                "Listeners match within %.0e after %.2f s (bound %.2f s)", tolerance, result[0], boundSeconds));
        return result[0];
    }

    private static double[] difference(Graph graph, double seconds) { return difference(graph, seconds, 1e-6); }

    /** {last time the difference was at or above tolerance, final difference}. */
    private static double[] difference(Graph graph, double seconds, double tolerance) {
        LoopPlan plan = GraphCompiler.compile(graph);
        SessionState state = new SessionState(0L, 0L, 0.0, 120.0, true, graph);
        SignalRuntime a = plan.signals().runtime(state), b = plan.signals().runtime(state);
        double[] x = new double[2], y = new double[2];
        Random noise = new Random(11);
        int sharedStart = 48000, sharedEnd = 96000, frames = sharedEnd + (int) (48000 * seconds);
        double lastDiff = 0, diff = 0;
        for (int i = 0; i < frames; i++) {
            double shared = i >= sharedStart && i < sharedEnd ? 0.3 * Math.sin(0.03 * i) : 0.0;
            x[0] = x[1] = i < sharedStart ? 0.3 * Math.sin(0.07 * i) : shared;
            y[0] = y[1] = i < sharedStart ? 0.6 * (noise.nextDouble() - 0.5) : shared;
            long nanos = Math.round(i * 1e9 / 48000);
            a.process(x, nanos);
            b.process(y, nanos);
            if (i < sharedStart) continue;
            diff = Math.max(Math.abs(x[0] - y[0]), Math.abs(x[1] - y[1]));
            if (i >= sharedEnd && diff >= tolerance) lastDiff = (i - sharedEnd) / 48000.0;
        }
        return new double[] {lastDiff, diff};
    }

    // === random graphs ===

    /** Accepted random loops converge; rejected ones really are over the limit. */
    private static void randomGraphs() {
        Random rng = new Random(20260919);
        int accepted = 0, rejected = 0;
        for (int n = 0; n < 60; n++) {
            int k = 1 + rng.nextInt(3);
            double gain = rng.nextDouble() * 0.6;
            List<Graph.Node> nodes = new ArrayList<>(List.of(tone(), render(), node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, gain)), out()));
            List<Graph.Edge> edges = new ArrayList<>(List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), toOut("bus")));
            int longest = 0;
            for (int d = 0; d < k; d++) {
                int frames = 64 + rng.nextInt(129);
                longest = Math.max(longest, frames);
                nodes.add(node("d" + d, NodeType.DELAY, Map.of(NodeParam.FRAMES, (double) frames)));
                if (rng.nextBoolean()) {
                    nodes.add(node("f" + d, NodeType.FILTER, Map.of(NodeParam.MODE, (double) rng.nextInt(4),
                            NodeParam.RESONANCE_Q, 0.1 + rng.nextDouble() * 2.9, NodeParam.CUTOFF_HZ, 200.0 + rng.nextDouble() * 8000)));
                    edges.add(Graph.edge("bus", "f" + d));
                    edges.add(Graph.edge("f" + d, "d" + d));
                } else {
                    edges.add(Graph.edge("bus", "d" + d));
                }
                edges.add(Graph.edge("d" + d, "bus"));
            }
            Graph graph = graph(nodes, edges);
            double bound = bound(graph);
            if (bound > LIMIT) {
                rejects(graph, "unity gain", "Random graph " + n + " over the limit");
                rejected++;
                continue;
            }
            accepted++;
            double loopSeconds = longest / 48000.0;
            double boundSeconds = trips(Math.max(bound, 1e-3), 1e-5) * loopSeconds + 0.5;
            double[] result = difference(graph, boundSeconds + 0.5, 1e-5);
            check(result[0] <= boundSeconds, String.format(Locale.ROOT,
                    "Random graph %d (bound %.3f) converges after %.2f s (bound %.2f s)", n, bound, result[0], boundSeconds));
        }
        check(accepted >= 10 && rejected >= 10, "Random graphs cover both outcomes: " + accepted + "/" + rejected);
    }

    // === migration ===

    private static void migration() {
        Graph unity = mixLoop(1.0, Map.of());
        var result = FeedbackMigration.markFreeRunning(unity);
        check(result.changed() && result.freeRunDelays().equals(List.of("delay")), "Over-limit loop's delay is marked");
        GraphCompiler.compile(result.graph());
        var again = FeedbackMigration.markFreeRunning(result.graph());
        check(!again.changed() && again.graph() == result.graph(), "Migration is idempotent");

        Graph valid = mixLoop(0.5, Map.of());
        var untouched = FeedbackMigration.markFreeRunning(valid);
        check(!untouched.changed() && untouched.graph() == valid, "Valid graphs are left alone");

        var partial = FeedbackMigration.markFreeRunning(twoLoops(0.5, 1.0));
        check(partial.freeRunDelays().equals(List.of("d2")), "Only the over-limit loop is marked: " + partial.freeRunDelays());

        Graph broken = graph(List.of(tone(), render(), node("a", NodeType.MIX_BUS, Map.of()), node("b", NodeType.MIX_BUS, Map.of()), out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "a"), Graph.edge("a", "b"), Graph.edge("b", "a"), toOut("a")));
        check(FeedbackMigration.markFreeRunning(broken).graph() == broken, "Unreadable graphs are returned unchanged");

        // freeRun only changes validation: the same loop sounds bit-identical with and without it.
        Graph plain = mixLoop(0.8, Map.of(NodeParam.FRAMES, 480.0));
        Graph flagged = mixLoop(0.8, Map.of(NodeParam.FRAMES, 480.0, NodeParam.FREE_RUN, 1.0));
        check(java.util.Arrays.equals(render(plain), render(flagged)), "freeRun never changes the audio");
    }

    private static double[] render(Graph graph) {
        SessionState state = new SessionState(0L, 0L, 0.0, 120.0, true, graph);
        SignalRuntime runtime = GraphCompiler.compile(graph).signals().runtime(state);
        double[] out = new double[48000], io = new double[2];
        for (int i = 0; i < 24000; i++) {
            io[0] = io[1] = i < 4800 ? Math.sin(0.05 * i) : 0;
            runtime.process(io, Math.round(i * 1e9 / 48000));
            out[2 * i] = io[0]; out[2 * i + 1] = io[1];
        }
        return out;
    }

    // === graph builders ===

    /** tone, render into bus(gain), bus into delay, delay back into bus, bus to output. */
    private static Graph mixLoop(double gain, Map<String, Double> delayParams) {
        Map<String, Double> params = new LinkedHashMap<>(Map.of(NodeParam.FRAMES, 64.0));
        params.putAll(delayParams);
        return graph(List.of(tone(), render(), node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, gain)),
                        node("delay", NodeType.DELAY, params), out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("bus", "delay"),
                        Graph.edge("delay", "bus"), toOut("bus")));
    }

    private static Graph filterLoop(double gain, int mode, double q) { return filterLoop(gain, mode, q, 64); }

    /** Like mixLoop, with a filter between the delay and the bus. */
    private static Graph filterLoop(double gain, int mode, double q, int frames) {
        return graph(List.of(tone(), render(), node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, gain)),
                        node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, (double) frames)),
                        node("filter", NodeType.FILTER, Map.of(NodeParam.MODE, (double) mode, NodeParam.RESONANCE_Q, q, NodeParam.CUTOFF_HZ, 1000.0)),
                        out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("bus", "delay"),
                        Graph.edge("delay", "filter"), Graph.edge("filter", "bus"), toOut("bus")));
    }

    /** One bus feeding two delays that both return to it. */
    private static Graph crossCoupled(double gain, Map<String, Double> freeRun) {
        return graph(List.of(tone(), render(), node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, gain)),
                        node("d1", NodeType.DELAY, delayParams(64, freeRun.getOrDefault("d1", 0.0))),
                        node("d2", NodeType.DELAY, delayParams(96, freeRun.getOrDefault("d2", 0.0))), out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("bus", "d1"), Graph.edge("bus", "d2"),
                        Graph.edge("d1", "bus"), Graph.edge("d2", "bus"), toOut("bus")));
    }

    /** Two independent bus/delay loops at their own gains, summed to the output. */
    private static Graph twoLoops(double first, double second) {
        return graph(List.of(tone(), render(), node("b1", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, first)),
                        node("b2", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, second)), node("sum", NodeType.MIX_BUS, Map.of()),
                        node("d1", NodeType.DELAY, Map.of(NodeParam.FRAMES, 64.0)), node("d2", NodeType.DELAY, Map.of(NodeParam.FRAMES, 64.0)), out()),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "b1"), Graph.edge("render", "b2"),
                        Graph.edge("b1", "d1"), Graph.edge("d1", "b1"), Graph.edge("b2", "d2"), Graph.edge("d2", "b2"),
                        Graph.edge("b1", "sum"), Graph.edge("b2", "sum"), toOut("sum")));
    }

    /** Adds lfo, range(attenuverter) driving {@code target}'s {@code port}. */
    private static Graph withLfoOn(Graph graph, String target, String port) {
        List<Graph.Node> nodes = new ArrayList<>(graph.nodes());
        nodes.add(node("lfo", NodeType.LFO, Map.of(NodeParam.RATE, 40.0)));
        boolean cutoff = port.equals("cutoff");
        nodes.add(node("range", NodeType.ATTENUVERTER, Map.of(NodeParam.SCALE, cutoff ? 1500.0 : 0.5, NodeParam.OFFSET, cutoff ? 1800.0 : 0.5)));
        List<Graph.Edge> edges = new ArrayList<>(graph.edges());
        edges.add(Graph.edge("lfo", "range"));
        edges.add(new Graph.Edge("range", "out", target, port));
        return graph(nodes, edges);
    }

    private static Map<String, Double> delayParams(int frames, double freeRun) {
        return freeRun == 0 ? Map.of(NodeParam.FRAMES, (double) frames) : Map.of(NodeParam.FRAMES, (double) frames, NodeParam.FREE_RUN, freeRun);
    }

    private static Graph graph(List<Graph.Node> nodes, List<Graph.Edge> edges) { return new Graph(3, nodes, edges); }
    private static Graph.Node node(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }
    private static Graph.Node tone() { return node("tone", NodeType.TONE, Map.of()); }
    private static Graph.Node render() { return node("render", NodeType.AUDIO_RENDER, Map.of()); }
    private static Graph.Node out() { return node("out", NodeType.OUTPUT, Map.of()); }
    private static Graph.Edge toOut(String from) { return new Graph.Edge(from, "out", "out", "audio"); }

    private static double bound(Graph graph) {
        double max = 0;
        for (var loop : SignalGraph.feedbackLoops(graph.nodes(), graph.edges())) max = Math.max(max, loop.bound());
        return max;
    }
    private static boolean approx(double a, double b) { return Math.abs(a - b) < 1e-9; }
    private static void accepts(Graph graph, String message) {
        try { GraphCompiler.compile(graph); }
        catch (IllegalArgumentException e) { throw new AssertionError(message + ": " + e.getMessage()); }
    }
    private static void rejects(Graph graph, String expected, String message) {
        try {
            GraphCompiler.compile(graph);
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains(expected), message + ": " + e.getMessage());
            return;
        }
        throw new AssertionError(message + ": compiled");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
