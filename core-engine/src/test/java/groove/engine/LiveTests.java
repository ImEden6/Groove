package groove.engine;

import java.util.*;

public final class LiveTests {
    private static int checks;
    public static void main(String[] args) {
        Graph demo = Graph.demo();
        check(GraphCompiler.compile(demo).size() == 9, "Demo graph event count");
        invalid(() -> GraphCompiler.compile(new Graph(4, demo.nodes(), demo.edges())));
        invalid(() -> GraphCompiler.compile(new Graph(1, List.of(), List.of())));
        var extraNodes = new ArrayList<>(demo.nodes()); extraNodes.add(demo.nodes().getFirst());
        invalid(() -> GraphCompiler.compile(new Graph(1, extraNodes, demo.edges())));
        var extraEdges = new ArrayList<>(demo.edges()); extraEdges.add(Graph.edge("missing", "mix"));
        invalid(() -> GraphCompiler.compile(new Graph(1, demo.nodes(), extraEdges)));
        invalid(() -> GraphCompiler.compile(new Graph(1, demo.nodes(), List.of())));
        invalid(() -> GraphCompiler.compile(new Graph(1,
                List.of(node("a", NodeType.FAST, Map.of()), node("b", NodeType.FAST, Map.of()), node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("a", "b"), Graph.edge("b", "a"), Graph.edge("a", "out")))));
        invalid(() -> GraphCompiler.compile(simple(Map.of(NodeParam.FREQUENCY, Double.NaN))));
        invalid(() -> GraphCompiler.compile(simple(Map.of("garbage", 1.0))));
        invalid(() -> GraphCompiler.compile(simple(Map.of(NodeParam.GAIN, 3.0))));
        invalid(() -> GraphCompiler.compile(simple(Map.of(NodeParam.CUTOFF_HZ, 30000.0))));
        check(GraphCompiler.compile(simple(Map.of(NodeParam.CUTOFF_HZ, 200.0))).size() == 1, "cutoffHz is a valid tone parameter");
        check(GraphCompiler.compile(simple(Map.of(NodeParam.CUTOFF_HZ, 20.0))).size() == 1, "cutoffHz accepts lower bound (20 Hz)");
        check(GraphCompiler.compile(simple(Map.of(NodeParam.CUTOFF_HZ, 20000.0))).size() == 1, "cutoffHz accepts upper bound (20000 Hz)");
        invalid(() -> GraphCompiler.compile(simple(Map.of(NodeParam.CUTOFF_HZ, 19.999))));
        invalid(() -> GraphCompiler.compile(simple(Map.of(NodeParam.CUTOFF_HZ, 20000.001))));
        invalid(() -> GraphCompiler.compile(new Graph(1,
                List.of(node("tone", NodeType.TONE, Map.of()), node("a", NodeType.FAST, Map.of(NodeParam.FACTOR, 16.0)),
                        node("b", NodeType.FAST, Map.of(NodeParam.FACTOR, 16.0)), node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "a"), Graph.edge("a", "b"), Graph.edge("b", "out")))));
        Graph stacked = new Graph(1, List.of(node("tone", NodeType.TONE, Map.of()), node("other", NodeType.TONE, Map.of()),
                node("mix", NodeType.STACK, Map.of()), node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "mix"), Graph.edge("other", "mix"), Graph.edge("mix", "out")));
        check(GraphCompiler.compile(stacked).size() == 2, "Identical simultaneous notes remain distinct");

        long epoch = 8_000_000_000L;
        SessionTimeline timeline = new SessionTimeline(demo, 120, epoch);
        var start = timeline.schedule(demo, 120, true, 0, epoch);
        check(!start.at(epoch + 999_999_999L).playing(), "Start does not apply early");
        check(start.at(epoch + 1_000_000_000L).playing(), "Start applies exactly at boundary");
        invalid(() -> timeline.schedule(demo, 120, true, 0, epoch));
        invalid(() -> timeline.schedule(demo, 120, true, 1, epoch));
        long later = epoch + 1_100_000_000L;
        var tempo = timeline.schedule(demo, 180, true, 1, later);
        SessionState change = tempo.pending();
        check(change.effectiveNanos() == epoch + 3_000_000_000L, "Change on next safe cycle");
        check(Math.abs(tempo.current().cycleAt(change.effectiveNanos()) - change.anchorCycle()) < 1e-12, "Tempo preserves phase");
        check(tempo.at(change.effectiveNanos() - 1).bpm() == 120, "Old tempo before boundary");
        check(tempo.at(change.effectiveNanos()).bpm() == 180, "New tempo at boundary");
        var stop = timeline.schedule(demo, 180, false, 2, change.effectiveNanos() + 1);
        check(stop.pending().cycleAt(stop.pending().effectiveNanos() + 999_000_000L) == stop.pending().anchorCycle(), "Stop freezes transport");

        ClockSync clock = new ClockSync();
        check(clock.observe(1_000_000_000L, 1_020_000_000L, 6_010_000_000L), "Clock sample accepted");
        check(clock.serverTime(2_000_000_000L) == 7_000_000_000L, "Offset removes half RTT");
        check(!clock.observe(0, 3_000_000_000L, 123), "Reject extreme RTT");
        check(!clock.observe(2, 1, 123), "Reject inverted time");
        check(!clock.observe(0, 500_000_000L, 123), "Reject jitter spike");

        SessionState playing = new SessionState(1, epoch, 0, 120, true, demo);
        var compiled = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(playing, null));
        float[] all = new float[9600], split = new float[9600];
        LiveRenderer first = new LiveRenderer(); first.publish(compiled); first.render(all, 4800, epoch);
        LiveRenderer second = new LiveRenderer(); second.publish(compiled);
        float[] block = new float[274];
        for (int offset = 0; offset < 4800; offset += 137) {
            int count = Math.min(137, 4800 - offset);
            second.render(block, count, epoch + Math.round(offset * 1e9 / 48000));
            System.arraycopy(block, 0, split, offset * 2, count * 2);
        }
        double error = 0, energy = 0;
        for (int i = 0; i < all.length; i++) {
            check(Float.isFinite(all[i]) && Math.abs(all[i]) <= 1, "Finite bounded output");
            error = Math.max(error, Math.abs(all[i] - split[i])); energy += all[i] * all[i];
        }
        check(error < .00001, "Chunk boundaries agree within timestamp rounding");
        check(energy > 1, "Live output is audible");
        LiveRenderer late = new LiveRenderer(); late.publish(compiled);
        compiled.prepare(epoch + 5_123_000_000L);
        late.render(block, 137, epoch + 5_123_000_000L);
        check(late.resyncs() == 0, "Late join starts directly at current phase");
        compiled.prepare(epoch + 50_000_000_000L);
        late.render(block, 137, epoch + 50_000_000_000L);
        check(late.resyncs() == 1, "Long stall explicitly resynchronizes");
        var stopped = new SessionState(2, epoch + 2_000_000_000L, 1, 120, false, demo);
        var transition = LiveRenderer.Timeline.compile(new SessionTimeline.Snapshot(playing, stopped));
        LiveRenderer stoppedRenderer = new LiveRenderer(); stoppedRenderer.publish(transition);
        stoppedRenderer.render(block, 137, stopped.effectiveNanos() + 10_000_000L);
        check(Arrays.equals(block, new float[274]), "Stop is silent after fade");
        ClockSync clientA = new ClockSync(), clientB = new ClockSync();
        clientA.observe(1_000_000_000L, 1_020_000_000L, epoch + 10_000_000L);
        clientB.observe(20_000_000_000L, 20_120_000_000L, epoch + 60_000_000L);
        long aTarget = clientA.serverTime(2_000_000_000L);
        long bTarget = clientB.serverTime(21_000_000_000L);
        check(aTarget == bTarget, "Different client clocks and symmetric latencies align");
        LiveRenderer a = new LiveRenderer(), b = new LiveRenderer();
        a.publish(compiled); b.publish(compiled);
        float[] aAudio = new float[2048], bAudio = new float[2048];
        compiled.prepare(aTarget);
        a.render(aAudio, 1024, aTarget); b.render(bAudio, 1024, bTarget);
        check(Arrays.equals(aAudio, bAudio), "Aligned clients synthesize identical audio");
        // A newly joined client catches up without replaying the first cycle.
        long joinedAt = epoch + 123_456_000_000L;
        compiled.prepare(joinedAt);
        a.render(aAudio, 1024, joinedAt); // long-running client recovers after a stall
        LiveRenderer joining = new LiveRenderer(); joining.publish(compiled);
        joining.render(bAudio, 1024, joinedAt);
        check(Arrays.equals(aAudio, bAudio), "Late join and stall recovery use the same phase");
        System.out.println("Passed " + checks + " live/backend checks.");
    }
    private static Graph.Node node(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }
    private static Graph simple(Map<String, Double> params) {
        return new Graph(1, List.of(node("tone", NodeType.TONE, params), node("out", NodeType.OUTPUT, Map.of())), List.of(Graph.edge("tone", "out")));
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
}
