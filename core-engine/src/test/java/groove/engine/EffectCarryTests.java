package groove.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Effect state carried across republishes and scheduled commits, and the paths that must not carry. */
final class EffectCarryTests {
    private static final int RATE = LiveRenderer.SAMPLE_RATE, BLOCK = 512;
    private static final double BPM = 120;
    private static final long SWITCH = seconds(3);
    private static final Object SESSION = "session-a";

    static void run() {
        transferRules();
        delayHistoryIsExact();
        settingsRamp();
        delayFadeHandsOver();
        modulatedFilterRamp();
        unchangedGraphContinuesExactly();
        predelayHistorySurvivesRepublishes();
        lateTempoChanges();
        for (boolean reverb : new boolean[] {true, false}) {
            knobEdit(reverb);
            scheduledCommit(reverb);
        }
        for (boolean reverb : new boolean[] {true, false}) {
            sameGraphRepublish(reverb);
            sameGraphCommit(reverb);
        }
        rapidEdits();
        independentRenderers();
        fallbackPaths();
        System.out.println("Effect carry checks passed.");
    }

    // === graphs ===

    /** A steady tone into a long tail: a 10 s reverb, or a 0.9-gain feedback delay. */
    private static Graph tail(boolean reverb, double toneGain) {
        List<Graph.Node> nodes = new ArrayList<>(List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.GAIN, toneGain, NodeParam.FREQUENCY, 220.0)),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())));
        List<Graph.Edge> edges = new ArrayList<>(List.of(Graph.edge("tone", "render")));
        if (reverb) {
            nodes.add(new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 10.0)));
            edges.add(Graph.edge("render", "rev"));
            edges.add(new Graph.Edge("rev", "out", "out", "audio"));
        } else {
            nodes.add(new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.9)));
            nodes.add(new Graph.Node("echo", NodeType.DELAY, Map.of(NodeParam.FRAMES, 12000.0)));
            edges.add(Graph.edge("render", "bus"));
            edges.add(Graph.edge("bus", "echo"));
            edges.add(Graph.edge("echo", "bus"));
            edges.add(new Graph.Edge("bus", "out", "out", "audio"));
        }
        return new Graph(3, nodes, edges);
    }

    private static SessionState start(Graph graph) { return new SessionState(1, 0, 0, BPM, true, graph); }

    /** A draft-style edit: a new revision anchored at the switch, at the same cycle position. */
    private static SessionState editAt(SessionState before, Graph graph, long at) {
        return new SessionState(before.revision() + 1, at, before.cycleAt(at), before.bpm(), true, graph);
    }

    private static LiveRenderer.Timeline timeline(SessionState state) {
        return new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(state.graph())), null);
    }

    // === rendering ===

    private static long seconds(double s) { return Math.round(s * 1e9); }

    /** Renders [from, to) in blocks, preparing lookahead each block; returns interleaved stereo. */
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

    /** RMS of [fromSeconds, toSeconds) within a rendering that started at {@code origin}. */
    private static double rms(float[] audio, long origin, double fromSeconds, double toSeconds, long start) {
        int a = (int) Math.round((seconds(fromSeconds) - (start - origin)) * RATE / 1e9);
        int b = (int) Math.round((seconds(toSeconds) - (start - origin)) * RATE / 1e9);
        double sum = 0;
        for (int i = a * 2; i < b * 2; i++) sum += audio[i] * (double) audio[i];
        return Math.sqrt(sum / Math.max(1, (b - a) * 2));
    }

    // === transfer rules ===

    private static SignalGraph signals(Graph graph) { return GraphCompiler.compile(graph).signals(); }

    private static int index(SignalGraph graph, String id) {
        for (int i = 0; i < graph.nodes.length; i++) if (graph.nodes[i].id().equals(id)) return i;
        throw new AssertionError("No node " + id);
    }

    private static boolean carries(SignalGraph from, SignalGraph to, String id) {
        return SignalRuntime.transferPlan(from, to).source[index(to, id)] >= 0;
    }

    /** tone into a filter and a reverb outside any loop, plus a bus/delay/lp loop. */
    private static Graph ruleGraph(Map<String, Double> lpParams, boolean extraLoopEdge, String reverbInput) {
        List<Graph.Node> nodes = new ArrayList<>(List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("pre", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, 2000.0)),
                new Graph.Node("rev", NodeType.REVERB, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.4)),
                new Graph.Node("echo", NodeType.DELAY, Map.of(NodeParam.FRAMES, 480.0)),
                new Graph.Node("lp", NodeType.FILTER, lpParams),
                new Graph.Node("sum", NodeType.MIX_BUS, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())));
        List<Graph.Edge> edges = new ArrayList<>(List.of(Graph.edge("tone", "render"), Graph.edge("render", "pre"),
                Graph.edge(reverbInput, "rev"), Graph.edge("pre", "bus"), Graph.edge("bus", "echo"),
                Graph.edge("echo", "lp"), Graph.edge("lp", "bus"), Graph.edge("bus", "sum"), Graph.edge("rev", "sum"),
                new Graph.Edge("sum", "out", "out", "audio")));
        if (extraLoopEdge) edges.add(Graph.edge("echo", "bus"));
        return new Graph(3, nodes, edges);
    }

    private static void transferRules() {
        SignalGraph base = signals(ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 3000.0), false, "pre"));
        for (String id : List.of("pre", "rev", "echo", "lp"))
            check(carries(base, base, id), "Unchanged " + id + " carries");
        SignalGraph knob = signals(ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 900.0, NodeParam.RESONANCE_Q, 2.0), false, "pre"));
        check(carries(base, knob, "lp") && carries(base, knob, "echo"), "Cutoff and Q changes keep the loop");
        SignalGraph mode = signals(ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 3000.0, NodeParam.MODE, 1.0), false, "pre"));
        check(!carries(base, mode, "lp") && !carries(base, mode, "echo"), "A mode change inside a loop resets the whole group");
        check(carries(base, mode, "rev") && carries(base, mode, "pre"), "Effects outside that loop still carry");
        SignalGraph rewired = signals(ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 3000.0), false, "render"));
        check(!carries(base, rewired, "rev"), "A changed input resets that effect");
        check(carries(base, rewired, "pre") && carries(base, rewired, "echo"), "Other effects are unaffected by that rewiring");
        SignalGraph looped = signals(ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 3000.0), true, "pre"));
        check(!carries(base, looped, "echo") && !carries(base, looped, "lp"), "A new edge inside a loop resets the group");
        check(carries(base, looped, "rev"), "A loop change leaves outside effects alone");

        Graph renamed = ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 3000.0), false, "pre");
        var renamedNodes = new ArrayList<>(renamed.nodes());
        renamedNodes.set(3, new Graph.Node("hall", NodeType.REVERB, Map.of()));
        var renamedEdges = new ArrayList<Graph.Edge>();
        for (var e : renamed.edges()) renamedEdges.add(new Graph.Edge(e.fromNode().equals("rev") ? "hall" : e.fromNode(), e.fromPort(),
                e.toNode().equals("rev") ? "hall" : e.toNode(), e.toPort()));
        SignalGraph renamedSignals = signals(new Graph(3, renamedNodes, renamedEdges));
        check(!carries(base, renamedSignals, "hall"), "A renamed effect starts empty");

        // Breaking an old loop must reset surviving effects, including those whose own inputs
        // did not change. The incoming graph no longer has a loop to visit during group checks.
        Graph closedLoop = ruleGraph(Map.of(NodeParam.CUTOFF_HZ, 3000.0), false, "pre");
        var brokenEdges = new ArrayList<>(closedLoop.edges());
        brokenEdges.removeIf(e -> e.fromNode().equals("lp") && e.toNode().equals("bus"));
        brokenEdges.add(Graph.edge("lp", "sum"));
        SignalGraph broken = signals(new Graph(3, closedLoop.nodes(), brokenEdges));
        check(!carries(base, broken, "echo") && !carries(base, broken, "lp"), "Breaking a loop resets all surviving effects");
        check(carries(base, broken, "pre") && carries(base, broken, "rev"), "Breaking a loop preserves unrelated effects");
        var remainingNodes = new ArrayList<>(closedLoop.nodes());
        remainingNodes.removeIf(n -> n.id().equals("lp"));
        brokenEdges.removeIf(e -> e.fromNode().equals("lp") || e.toNode().equals("lp"));
        brokenEdges.add(Graph.edge("echo", "sum"));
        SignalGraph removed = signals(new Graph(3, remainingNodes, brokenEdges));
        check(!carries(base, removed, "echo"), "Removing a loop member resets the surviving delay");
        check(carries(base, removed, "pre") && carries(base, removed, "rev"), "Removing a loop member preserves unrelated effects");

        // Reverb settings all carry; the tail keeps ringing under the new decay.
        Graph a = tail(true, 0.3), b = tail(true, 0.3);
        var bNodes = new ArrayList<>(b.nodes());
        bNodes.set(3, new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 2.0, NodeParam.PRE_DELAY_MS, 30.0, NodeParam.DAMPING_HZ, 3000.0)));
        check(carries(signals(a), signals(new Graph(3, bNodes, b.edges())), "rev"), "Reverb setting changes carry");
    }

    /** A delay line holds its last L inputs, so a length change keeps exactly the most recent ones. */
    private static void delayHistoryIsExact() {
        for (int newLength : new int[] {600, 1000, 1500}) {
            SignalRuntime before = runtime(delayOnly(1000));
            SignalRuntime after = runtime(delayOnly(newLength));
            double[] io = new double[2];
            int fed = 3000;
            // Kept under the ±8 clamp every delay write applies.
            for (int i = 0; i < fed; i++) { io[0] = io[1] = ramp(i); before.process(io, frameNanos(i)); }
            after.reset();
            after.continueFrom(before, SignalRuntime.transferPlan(signalsOf(before), signalsOf(after)));
            int longer = newLength - 1000, n = SignalRuntime.rampFrames;
            for (int k = 0; k < newLength; k++) {
                io[0] = io[1] = 0;
                after.process(io, frameNanos(fed + k));
                // Frame fed + k reads the input from newLength frames earlier, if the old line still held it.
                double expected = fed + k - newLength >= fed - 1000 ? ramp(fed + k - newLength) : 0;
                // A longer line keeps the old tap until the new one reaches history, then fades over the ramp.
                if (longer > 0 && k < longer + n) {
                    double old = fed + k - 1000 < fed ? ramp(fed + k - 1000) : 0, w = Math.max(0, k - longer + 1) / (double) n;
                    expected = old + (expected - old) * w;
                }
                check(io[0] == expected && io[1] == expected, String.format(Locale.ROOT,
                        "Delay %d -> %d frame %d: %.4f, expected %.4f", 1000, newLength, k, io[0], expected));
            }
        }
    }

    private static double ramp(int frame) { return (frame + 1) * 1e-3; }

    /** A changed bus gain moves linearly over the ramp; a re-edit mid-ramp starts where the last one got to. */
    private static void settingsRamp() {
        int n = SignalRuntime.rampFrames;
        SignalRuntime a = runtime(busOnly(0.2)), b = runtime(busOnly(0.9)), c = runtime(busOnly(0.5));
        double[] io = new double[2];
        for (int i = 0; i < 1000; i++) { io[0] = io[1] = 1; a.process(io, frameNanos(i)); }
        b.reset();
        b.continueFrom(a, SignalRuntime.transferPlan(signalsOf(a), signalsOf(b)));
        int half = n / 2;
        for (int k = 0; k < half; k++) {
            io[0] = io[1] = 1;
            b.process(io, frameNanos(1000 + k));
            check(io[0] == 0.2 + (0.9 - 0.2) * ((k + 1) / (double) n), "Bus gain ramps linearly at frame " + k + ": " + io[0]);
        }
        double reached = io[0];
        c.reset();
        c.continueFrom(b, SignalRuntime.transferPlan(signalsOf(b), signalsOf(c)));
        for (int k = 0; k < n + 10; k++) {
            io[0] = io[1] = 1;
            c.process(io, frameNanos(1000 + half + k));
            if (k == 0) check(Math.abs(io[0] - reached) < 1e-2, "A re-edit continues from the interrupted ramp: " + io[0] + " after " + reached);
        }
        check(io[0] == 0.5 && c.ramping() == 0, "The ramp ends exactly on the new gain: " + io[0]);
        SignalRuntime same = runtime(busOnly(0.5));
        same.reset();
        same.continueFrom(c, SignalRuntime.transferPlan(signalsOf(c), signalsOf(same)));
        check(same.ramping() == 0, "An unchanged gain does not ramp");
    }

    /** A switch during a lengthened delay's fade continues that fade, then fades on to its own length. */
    private static void delayFadeHandsOver() {
        int n = SignalRuntime.rampFrames;
        for (int third : new int[] {1500, 1700}) {
            SignalRuntime a = runtime(delayOnly(1000)), b = runtime(delayOnly(1500)), c = runtime(delayOnly(third));
            double[] x = new double[2], y = new double[2];
            int frame = 0;
            for (; frame < 3000; frame++) { x[0] = x[1] = ramp(frame); a.process(x, frameNanos(frame)); }
            b.reset();
            b.continueFrom(a, SignalRuntime.transferPlan(signalsOf(a), signalsOf(b)));
            // Switch again while b still reads the old tap
            for (int k = 0; k < 100; k++, frame++) { x[0] = x[1] = ramp(frame); b.process(x, frameNanos(frame)); }
            c.reset();
            c.continueFrom(b, SignalRuntime.transferPlan(signalsOf(b), signalsOf(c)));
            // b's own fade has 400 frames of wait and n of fade left, which c carries on exactly
            int shared = 400 + n;
            for (int k = 0; k < 3000; k++, frame++) {
                x[0] = x[1] = y[0] = y[1] = ramp(frame % 5000);
                b.process(x, frameNanos(frame));
                c.process(y, frameNanos(frame));
                if (k < shared || third == 1500)
                    check(x[0] == y[0], "Delay 1500 -> " + third + " continues the handed-over fade at frame " + k);
                check(y[0] > 0, "Delay 1500 -> " + third + " never drops to silence at frame " + k);
            }
            check(c.ramping() == 0, "Delay 1500 -> " + third + " finishes on its own length");
        }
    }

    /** A modulated cutoff follows its control across a switch; only a changed Q ramps. */
    private static void modulatedFilterRamp() {
        SignalRuntime a = runtime(modulatedFilter(2)), same = runtime(modulatedFilter(2)), q = runtime(modulatedFilter(6));
        double[] io = new double[2];
        for (int i = 0; i < 1000; i++) { io[0] = io[1] = Math.sin(0.03 * i); a.process(io, frameNanos(i)); }
        same.reset();
        same.continueFrom(a, SignalRuntime.transferPlan(signalsOf(a), signalsOf(same)));
        check(same.ramping() == 0, "An unchanged modulated filter does not ramp");
        q.reset();
        q.continueFrom(a, SignalRuntime.transferPlan(signalsOf(a), signalsOf(q)));
        check(q.ramping() == 1, "A modulated filter's changed Q still ramps");
    }

    private static Graph modulatedFilter(double q) {
        return new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()), new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("lp", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, 1000.0, NodeParam.RESONANCE_Q, q)),
                new Graph.Node("lfo", NodeType.LFO, Map.of(NodeParam.RATE, 40.0)),
                new Graph.Node("range", NodeType.ATTENUVERTER, Map.of(NodeParam.SCALE, 1500.0, NodeParam.OFFSET, 1800.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "lp"), Graph.edge("lfo", "range"),
                        new Graph.Edge("range", "out", "lp", "cutoff"), new Graph.Edge("lp", "out", "out", "audio")));
    }

    private static Graph busOnly(double gain) {
        return new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()), new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, gain)), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), new Graph.Edge("bus", "out", "out", "audio")));
    }

    private static Graph delayOnly(int frames) {
        return new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()), new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("echo", NodeType.DELAY, Map.of(NodeParam.FRAMES, (double) frames)), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "echo"), new Graph.Edge("echo", "out", "out", "audio")));
    }

    private static final java.util.IdentityHashMap<SignalRuntime, SignalGraph> GRAPHS = new java.util.IdentityHashMap<>();
    private static SignalRuntime runtime(Graph graph) {
        SignalGraph signals = signals(graph);
        SignalRuntime runtime = signals.runtime(start(graph));
        GRAPHS.put(runtime, signals);
        return runtime;
    }
    private static SignalGraph signalsOf(SignalRuntime runtime) { return GRAPHS.get(runtime); }
    private static long frameNanos(long frame) { return Math.round(frame * 1e9 / RATE); }

    /** Continuing an identical graph is bit-for-bit the same as never switching. */
    private static void unchangedGraphContinuesExactly() {
        for (boolean reverb : new boolean[] {true, false}) {
            SignalRuntime a = runtime(tail(reverb, 0.3)), b = runtime(tail(reverb, 0.3));
            double[] x = new double[2], y = new double[2];
            for (int i = 0; i < 48000; i++) { x[0] = x[1] = Math.sin(0.03 * i); a.process(x, frameNanos(i)); }
            b.reset();
            b.continueFrom(a, SignalRuntime.transferPlan(signalsOf(a), signalsOf(b)));
            for (int i = 48000; i < 96000; i++) {
                x[0] = x[1] = y[0] = y[1] = i < 60000 ? Math.sin(0.03 * i) : 0;
                a.process(x, frameNanos(i));
                b.process(y, frameNanos(i));
                check(x[0] == y[0] && x[1] == y[1], "Unchanged " + (reverb ? "reverb" : "delay") + " continues exactly at frame " + i);
            }
        }
    }

    /** Even a short or bypassed predelay must retain older history for subsequent knob changes. */
    private static void predelayHistorySurvivesRepublishes() {
        for (double interimPredelay : new double[] {10, 0}) {
            Reverb reference = new Reverb();
            reference.setParams(1.8, 6000, 12000, 10);
            double[] expected = new double[2], actual = new double[2];
            for (int frame = 0; frame < RATE; frame++)
                reference.process(frame == 40000 ? 1 : 0, frame, expected);
            Reverb carried = new Reverb();
            carried.setParams(1.8, 6000, 12000, interimPredelay);
            carried.copyStateFrom(reference);
            reference.setParams(1.8, 6000, 12000, interimPredelay);
            for (int frame = RATE; frame < RATE + 960; frame++) {
                reference.process(0, frame, expected);
                carried.process(0, frame, actual);
                check(expected[0] == actual[0] && expected[1] == actual[1], "Intermediate predelay republish is exact");
            }
            Reverb enlarged = new Reverb();
            enlarged.setParams(1.8, 6000, 12000, 500);
            enlarged.copyStateFrom(carried);
            reference.setParams(1.8, 6000, 12000, 500);
            double energy = 0;
            for (int frame = RATE + 960; frame < RATE * 2; frame++) {
                reference.process(0, frame, expected);
                enlarged.process(0, frame, actual);
                check(expected[0] == actual[0] && expected[1] == actual[1],
                        "Predelay increase after republish preserves older input at frame " + frame);
                energy += actual[0] * actual[0] + actual[1] * actual[1];
            }
            check(energy > 1e-6, "Predelay history comparison contains an audible tail");
        }
    }

    /** Network/compiler latency after a tempo edit is not a seek, for previews or late commits. */
    private static void lateTempoChanges() {
        for (boolean scheduled : new boolean[] {false, true}) {
            for (double bpm : new double[] {90, 140}) {
                for (boolean seek : new boolean[] {false, true}) {
                    SessionState before = start(tail(true, 0.3));
                    LiveRenderer renderer = new LiveRenderer();
                    var first = timeline(before);
                    renderer.publish(first, SESSION);
                    long arrival = SWITCH + seconds(0.1);
                    render(renderer, first, 0, arrival);
                    long replays = renderer.historyRecoveries(), leases = renderer.replayLeases();
                    SessionState after = new SessionState(2, SWITCH, before.cycleAt(SWITCH) + (seek ? 0.5 : 0),
                            bpm, true, tail(true, 0));
                    var next = scheduled
                            ? new LiveRenderer.Timeline(first.current(), timeline(after).current()) : timeline(after);
                    next.prepare(arrival);
                    renderer.publish(next, SESSION);
                    render(renderer, next, arrival, arrival + seconds(0.1));
                    // While a late pending seek replays, its unchanged current program can
                    // legitimately carry the outgoing sound for the scheduled crossfade.
                    long expectedTransfers = seek && !scheduled ? 0 : 1;
                    check(renderer.effectTransfers() == expectedTransfers, "Late tempo change carries unless its anchor seeks: scheduled=" + scheduled
                            + ", bpm=" + bpm + ", seek=" + seek + ", transfers=" + renderer.effectTransfers());
                    if (!seek) check(renderer.historyRecoveries() == replays && renderer.replayLeases() == leases,
                            "Late tempo change does not replay or request a lease");
                    else check(renderer.historyRecoveries() > replays, "Late tempo change with a seek still replays");
                }
            }
        }
    }

    // === renderer scenarios ===

    /** A knob edit that silences the source at the switch: everything after it is the old tail. */
    private static void knobEdit(boolean reverb) {
        String kind = reverb ? "reverb" : "feedback delay";
        SessionState before = start(tail(reverb, 0.3));
        SessionState after = editAt(before, tail(reverb, 0.0), SWITCH);
        double[] keyed = tailAfterSwitch(before, after, SESSION, SESSION);
        double[] unkeyed = tailAfterSwitch(before, after, null, null);
        check(keyed[1] > 0.1 * keyed[0], String.format(Locale.ROOT, "%s tail survives a knob edit: %.4f of %.4f", kind, keyed[1], keyed[0]));
        check(unkeyed[1] < 1e-3 * unkeyed[0], String.format(Locale.ROOT, "Without a session key the %s tail is cut, as before: %.6f", kind, unkeyed[1]));
        check(keyed[2] == 1 && keyed[3] == 0, kind + " edit carried once and replayed nothing: " + keyed[2] + " / " + keyed[3]);
        check(keyed[4] == 0, kind + " edit changed the note, so its voice starts fresh");
    }

    /** {steady RMS before, tail RMS 1.5..2.5 s after, transfers, replays started after the switch, voices carried}. */
    private static double[] tailAfterSwitch(SessionState before, SessionState after, Object keyBefore, Object keyAfter) {
        LiveRenderer renderer = new LiveRenderer();
        var first = timeline(before);
        renderer.publish(first, keyBefore);
        float[] lead = render(renderer, first, 0, SWITCH);
        long replays = renderer.historyRecoveries();
        var second = timeline(after);
        second.prepare(SWITCH);
        renderer.publish(second, keyAfter);
        float[] tail = render(renderer, second, SWITCH, SWITCH + seconds(3));
        return new double[] {rms(lead, 0, 2, 3, 0), rms(tail, 0, 4.5, 5.5, SWITCH),
                renderer.effectTransfers(), renderer.historyRecoveries() - replays, renderer.voiceCarries};
    }

    /** An unchanged scheduled commit continues the held note's voice too, matching no commit at all. */
    private static void sameGraphCommit(boolean reverb) {
        SessionState current = start(tail(reverb, 0.3));
        SessionState pending = new SessionState(2, SWITCH, current.cycleAt(SWITCH), BPM, true, current.graph());
        LiveRenderer reference = new LiveRenderer(), committed = new LiveRenderer();
        var once = timeline(current);
        reference.publish(once, SESSION);
        var both = new LiveRenderer.Timeline(new LiveRenderer.Program(current, GraphCompiler.compile(current.graph())),
                new LiveRenderer.Program(pending, GraphCompiler.compile(pending.graph())));
        committed.publish(both, SESSION);
        float[] ref = render(reference, once, 0, SWITCH + seconds(3)), got = render(committed, both, 0, SWITCH + seconds(3));
        String kind = reverb ? "Reverb" : "Delay";
        // The commit's own anchor rounds cycle positions slightly differently, so allow one float step per sample.
        int off = -1;
        for (int i = 0; i < ref.length && off < 0; i++)
            if (Math.abs(ref[i] - got[i]) > Math.ulp(Math.max(Math.abs(ref[i]), Math.abs(got[i])))) off = i / 2;
        check(off < 0, kind + " same-graph commit matches no commit, first off at frame " + off);
        check(committed.effectTransfers() == 1 && committed.voiceCarries == 1, kind + " same-graph commit carried its effects and voice");
    }

    /** The commit takes effect at the switch; its effects continue the outgoing program's. */
    private static void scheduledCommit(boolean reverb) {
        String kind = reverb ? "reverb" : "feedback delay";
        SessionState current = start(tail(reverb, 0.3));
        SessionState pending = new SessionState(2, SWITCH, current.cycleAt(SWITCH), BPM, true, tail(reverb, 0.0));
        LiveRenderer renderer = new LiveRenderer();
        var both = new LiveRenderer.Timeline(new LiveRenderer.Program(current, GraphCompiler.compile(current.graph())),
                new LiveRenderer.Program(pending, GraphCompiler.compile(pending.graph())));
        renderer.publish(both, SESSION);
        float[] lead = render(renderer, both, 0, SWITCH);
        long replays = renderer.historyRecoveries();
        float[] tail = render(renderer, both, SWITCH, SWITCH + seconds(3));
        double steady = rms(lead, 0, 2, 3, 0), late = rms(tail, 0, 4.5, 5.5, SWITCH);
        check(late > 0.1 * steady, String.format(Locale.ROOT, "%s tail survives a scheduled commit: %.4f of %.4f", kind, late, steady));
        check(renderer.effectTransfers() == 1 && renderer.historyRecoveries() == replays, kind + " commit carried without replaying");
    }

    /** Republishing the same graph and position matches a renderer that never republished. */
    private static void sameGraphRepublish(boolean reverb) {
        SessionState state = start(tail(reverb, 0.3));
        LiveRenderer reference = new LiveRenderer(), republished = new LiveRenderer();
        var once = timeline(state);
        reference.publish(once, SESSION);
        var first = timeline(state);
        republished.publish(first, SESSION);
        float[] refLead = render(reference, once, 0, SWITCH), lead = render(republished, first, 0, SWITCH);
        var again = timeline(state);
        again.prepare(SWITCH);
        republished.publish(again, SESSION);
        float[] ref = render(reference, once, SWITCH, SWITCH + seconds(3)), got = render(republished, again, SWITCH, SWITCH + seconds(3));
        double worst = 0;
        for (int i = 0; i < ref.length; i++) worst = Math.max(worst, Math.abs(ref[i] - got[i]));
        // The same republish without a session key: effects reset and replay only 1 s.
        LiveRenderer control = new LiveRenderer();
        var c1 = timeline(state);
        control.publish(c1, null);
        render(control, c1, 0, SWITCH);
        var c2 = timeline(state);
        c2.prepare(SWITCH);
        control.publish(c2, null);
        float[] old = render(control, c2, SWITCH, SWITCH + seconds(3));
        double oldWorst = 0;
        for (int i = 0; i < ref.length; i++) oldWorst = Math.max(oldWorst, Math.abs(ref[i] - old[i]));
        String kind = reverb ? "Reverb" : "Delay";
        check(java.util.Arrays.equals(refLead, lead), "Both renderers agree before the republish");
        // Effects and the held note's voice filter both continue, so nothing restarts mid-note.
        check(java.util.Arrays.equals(ref, got) && oldWorst > 1e-3, String.format(Locale.ROOT,
                "%s same-graph republish matches the uninterrupted reference: worst %.2e, old path %.2e", kind, worst, oldWorst));
        check(republished.effectTransfers() == 1 && republished.voiceCarries == 1, kind + " same-graph republish carried its effects and voice");
    }

    /** Twenty edits 20 ms apart, some published twice between renders, then silence: the tail survives. */
    private static void rapidEdits() {
        SessionState state = start(tail(false, 0.3));
        LiveRenderer renderer = new LiveRenderer();
        var timeline = timeline(state);
        renderer.publish(timeline, SESSION);
        float[] lead = render(renderer, timeline, 0, SWITCH);
        long at = SWITCH;
        for (int edit = 0; edit < 20; edit++) {
            state = editAt(state, tail(false, edit == 19 ? 0.0 : 0.3 - edit * 0.01), at);
            timeline = timeline(state);
            timeline.prepare(at);
            renderer.publish(timeline, SESSION);
            if (edit % 3 == 1 && edit < 19) {
                // Superseded before it ever renders.
                state = editAt(state, tail(false, 0.3 - edit * 0.01), at);
                timeline = timeline(state);
                timeline.prepare(at);
                renderer.publish(timeline, SESSION);
            }
            render(renderer, timeline, at, at + seconds(0.02));
            at += seconds(0.02);
        }
        long replays = renderer.historyRecoveries();
        float[] tail = render(renderer, timeline, at, at + seconds(3));
        double steady = rms(lead, 0, 2, 3, 0), late = rms(tail, 0, 1.5, 2.5, 0);
        check(late > 0.05 * steady, String.format(Locale.ROOT, "Tail survives 20 rapid edits: %.4f of %.4f", late, steady));
        check(renderer.effectTransfers() == 20 && renderer.historyRecoveries() == replays,
                "Every rendered edit carried: " + renderer.effectTransfers());
    }

    /** Three speakers on one replay budget switch at different times; none replays, all keep the tail. */
    private static void independentRenderers() {
        var budget = new ReplayBudget(2, 3);
        SessionState state = start(tail(true, 0.3));
        long[] switches = {SWITCH, SWITCH + seconds(0.3), SWITCH + seconds(0.7)};
        for (int r = 0; r < 3; r++) {
            LiveRenderer renderer = new LiveRenderer(budget);
            var first = timeline(state);
            renderer.publish(first, SESSION);
            float[] lead = render(renderer, first, 0, switches[r]);
            long leases = renderer.replayLeases();
            var second = timeline(editAt(state, tail(true, 0.0), switches[r]));
            second.prepare(switches[r]);
            renderer.publish(second, SESSION);
            float[] tail = render(renderer, second, switches[r], switches[r] + seconds(3));
            double steady = rms(lead, 0, 2, 3, 0), late = rms(tail, 0, 1.5, 2.5, 0);
            check(late > 0.1 * steady && renderer.replayLeases() == leases && renderer.effectTransfers() == 1,
                    String.format(Locale.ROOT, "Speaker %d keeps its tail without a lease: %.4f of %.4f", r, late, steady));
        }
    }

    /** A different session, a seek, a stop and restart, a resync and a fresh join all reset as before. */
    private static void fallbackPaths() {
        SessionState before = start(tail(true, 0.3));
        check(tailAfterSwitch(before, editAt(before, tail(true, 0.0), SWITCH), SESSION, "other-session")[2] == 0,
                "A different session never carries");
        SessionState seek = new SessionState(2, SWITCH, before.cycleAt(SWITCH) + 0.5, BPM, true, tail(true, 0.0));
        check(tailAfterSwitch(before, seek, SESSION, SESSION)[2] == 0, "A seek does not carry");

        // Stop, then restart: the restart has a gap, so it resets.
        LiveRenderer renderer = new LiveRenderer();
        var playing = timeline(before);
        renderer.publish(playing, SESSION);
        render(renderer, playing, 0, SWITCH);
        var stopped = timeline(new SessionState(2, SWITCH, before.cycleAt(SWITCH), BPM, false, tail(true, 0.3)));
        renderer.publish(stopped, SESSION);
        render(renderer, stopped, SWITCH, SWITCH + seconds(0.5));
        var restarted = timeline(new SessionState(3, SWITCH + seconds(0.5), before.cycleAt(SWITCH), BPM, true, tail(true, 0.3)));
        restarted.prepare(SWITCH + seconds(0.5));
        renderer.publish(restarted, SESSION);
        render(renderer, restarted, SWITCH + seconds(0.5), SWITCH + seconds(1));
        check(renderer.effectTransfers() == 0, "Stop and restart does not carry");

        // A resync right before the switch.
        LiveRenderer resynced = new LiveRenderer();
        var first = timeline(before);
        resynced.publish(first, SESSION);
        render(resynced, first, 0, SWITCH);
        resynced.resynchronize();
        var second = timeline(editAt(before, tail(true, 0.0), SWITCH));
        second.prepare(SWITCH);
        resynced.publish(second, SESSION);
        render(resynced, second, SWITCH, SWITCH + seconds(0.2));
        check(resynced.effectTransfers() == 0, "A resync does not carry");

        // A fresh join replays history as before.
        LiveRenderer fresh = new LiveRenderer();
        var joined = timeline(before);
        joined.prepare(SWITCH);
        fresh.publish(joined, SESSION);
        render(fresh, joined, SWITCH, SWITCH + seconds(1.5));
        check(fresh.effectTransfers() == 0 && fresh.historyRecoveries() == 1, "A fresh join replays history");
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
