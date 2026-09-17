package groove.engine;

import groove.engine.samples.*;
import java.util.*;

/** Step 8 P4: the cached voice selection starts and steals exactly the voices the per-frame scan does. */
final class SelectionCacheTests {
    private static int checks;
    private static final AssetRef LONG = new AssetRef("custom:selection_long.wav",
            "1111111111111111111111111111111111111111111111111111111111111111");
    private static final SampleData LONG_DATA = longSample();

    static void run() {
        long startNanos = System.nanoTime();
        var bank = Map.of(LONG, LONG_DATA);
        long seed = 0x5E1EC7L;
        for (int round = 0; round < 2; round++, seed++) {
            differential("hand-built plan", planTimeline(new Random(seed), bank), false, seed);
            differential("compiled pattern", graphTimeline(patternGraph(new Random(seed)), bank), false, seed);
            differential("delay graph with leases", graphTimeline(signalGraph(new Random(seed)), bank), true, seed);
        }
        sparseWindowSwap();
        System.out.printf("Selection cache differential checks passed (%d checks) in %.0f ms.%n", checks, (System.nanoTime() - startNanos) / 1e6);
    }

    /** Drives a cached and a per-frame renderer through the same random renders, resyncs, seeks and republishes. */
    private static void differential(String name, LiveRenderer.Timeline timeline, boolean leases, long seed) {
        Random random = new Random(seed * 31 + name.hashCode());
        int count = leases ? 2 : 1;
        ReplayBudget cachedBudget = leases ? new ReplayBudget(1, count) : ReplayBudget.unlimited();
        ReplayBudget plainBudget = leases ? new ReplayBudget(1, count) : ReplayBudget.unlimited();
        var cached = new LiveRenderer[count];
        var plain = new LiveRenderer[count];
        for (int r = 0; r < count; r++) {
            cached[r] = new LiveRenderer(cachedBudget, true); cached[r].publish(timeline);
            plain[r] = new LiveRenderer(plainBudget, false); plain[r].publish(timeline);
        }
        float[] a = new float[1400], b = new float[1400];
        long now = 3_000_000_000L;
        for (int step = 0; step < 120; step++) {
            int op = random.nextInt(12), who = random.nextInt(count);
            if (op == 0) { cached[who].resynchronize(); plain[who].resynchronize(); }
            else if (op == 1) now = Math.max(0, now + Math.round((random.nextDouble() * 5 - 2) * 1e9));
            else if (op == 2) { cached[who].publish(timeline); plain[who].publish(timeline); }
            int block = 1 + random.nextInt(400);
            timeline.prepare(now);
            for (int r = 0; r < count; r++) {
                cached[r].render(a, block, now);
                plain[r].render(b, block, now);
                for (int i = 0; i < block * 2; i++)
                    if (Float.floatToRawIntBits(a[i]) != Float.floatToRawIntBits(b[i]))
                        throw new AssertionError(name + " seed " + seed + ": output differs at step " + step + ", sample " + i);
                check(cached[r].voiceStarts == plain[r].voiceStarts && cached[r].voiceSteals == plain[r].voiceSteals
                        && cached[r].scheduleMisses() == plain[r].scheduleMisses() && cached[r].historyFrames() == plain[r].historyFrames(),
                        name + " seed " + seed + ": voice starts, steals, misses and replay match at step " + step);
            }
            now += Math.round(block * 1e9 / LiveRenderer.SAMPLE_RATE);
        }
        long starts = 0, steals = 0, cachedSelections = 0, plainSelections = 0;
        for (int r = 0; r < count; r++) {
            starts += cached[r].voiceStarts; steals += cached[r].voiceSteals;
            cachedSelections += cached[r].selections; plainSelections += plain[r].selections;
        }
        check(starts > 0 && steals > 0, name + " seed " + seed + " exercised starts and steals: " + starts + ", " + steals);
        check(cachedSelections * 4 < plainSelections, name + " seed " + seed + ": cache skipped most selections, "
                + cachedSelections + " of " + plainSelections);
        if (leases) check(cachedBudget.grants() > 0 && cachedBudget.grants() == plainBudget.grants(), name + " seed " + seed + " granted leases alike");
    }

    /**
     * One short note every 16 cycles, far past the 4-cycle lookahead: a window prepared during the silence holds
     * no future onset, so only the window swap tells the cache that a note is coming.
     */
    private static void sparseWindowSwap() {
        var nodes = List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 330.0, NodeParam.GAIN, .2)),
                new Graph.Node("pulse", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 1.0)),
                new Graph.Node("slow", NodeType.FAST, Map.of(NodeParam.FACTOR, .25)),
                new Graph.Node("slower", NodeType.FAST, Map.of(NodeParam.FACTOR, .25)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        Graph graph = new Graph(2, nodes, List.of(Graph.edge("tone", "pulse"), Graph.edge("pulse", "slow"),
                Graph.edge("slow", "slower"), Graph.edge("slower", "out")));
        var state = new SessionState(1, 0, 0, 300, true, graph);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(graph), Map.of()), null);
        var cached = new LiveRenderer(ReplayBudget.unlimited(), true);
        var plain = new LiveRenderer(ReplayBudget.unlimited(), false);
        cached.publish(timeline); plain.publish(timeline);
        float[] a = new float[1024], b = new float[1024];
        long silentAfterNote = 0;
        // 40 cycles at 300 BPM crosses two notes
        for (int block = 0; block < 3750; block++) {
            long now = Math.round(block * 512 * 1e9 / LiveRenderer.SAMPLE_RATE);
            timeline.prepare(now);
            cached.render(a, 512, now); plain.render(b, 512, now);
            if (!Arrays.equals(a, b)) throw new AssertionError("Sparse pattern output differs at block " + block);
            if (cached.voiceStarts > 0 && a[1022] == 0) silentAfterNote++;
        }
        check(cached.voiceStarts >= 2 && cached.voiceStarts == plain.voiceStarts && silentAfterNote > 0,
                "Sparse notes start through window swaps: " + cached.voiceStarts + " starts");
    }

    /** Scheduler-free path: hand-built events, including 40 overlapping 1.5 s one-shots. */
    private static LiveRenderer.Timeline planTimeline(Random random, Map<AssetRef, SampleData> bank) {
        var events = new ArrayList<Event>();
        for (int i = 0; i < 40; i++) {
            double start = random.nextDouble() * .9;
            events.add(new Event(new Arc(start, start + .05), new Arc(start, start + .05), null,
                    new SampleVoice(LONG, .8 + random.nextDouble() * .4, .02, 0)));
        }
        for (int i = 0; i < 6; i++) {
            double start = random.nextDouble() * .8, end = start + random.nextDouble() * .2 + .01;
            events.add(new Event(new Arc(start, end), new Arc(start, end), new Tone(Tone.Wave.SAW, 110 + i * 30, .03, 0, 20000), null));
        }
        var state = new SessionState(1, 0, 0, 120, true, Graph.demo());
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, new LoopPlan(events), bank), null);
        timeline.prepare(0);
        return timeline;
    }

    /** Scheduler path: fast long one-shots (more than 32 sounding), a sustained loop and tones. */
    private static Graph patternGraph(Random random) {
        var nodes = new ArrayList<Graph.Node>();
        var edges = new ArrayList<Graph.Edge>();
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("mix", "out"));
        // Three streams of 1.5 s one-shots at up to 16 per 2 s cycle keep more than 32 sounding
        for (int i = 0; i < 3; i++) {
            nodes.add(new Graph.Node("shot" + i, NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, .02, NodeParam.PITCH_RATIO, .9 + i * .1), LONG));
            nodes.add(new Graph.Node("dense" + i, NodeType.FAST, Map.of(NodeParam.FACTOR, 13.0 + random.nextInt(4))));
            edges.add(Graph.edge("shot" + i, "dense" + i));
            edges.add(Graph.edge("dense" + i, "mix"));
        }
        nodes.add(new Graph.Node("loop", NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, .05, NodeParam.LOOP, 1.0,
                NodeParam.LOOP_START, .2, NodeParam.LOOP_END, .8, NodeParam.LOOP_FADE_MS, 10.0), LONG));
        edges.add(Graph.edge("loop", "mix"));
        nodes.add(new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 220.0, NodeParam.GAIN, .05)));
        nodes.add(new Graph.Node("rhythm", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 3.0 + random.nextInt(9))));
        edges.add(Graph.edge("tone", "rhythm"));
        edges.add(Graph.edge("rhythm", "mix"));
        return new Graph(2, nodes, edges);
    }

    /** The pattern graph as an audio source through feedback delay, so joins replay history under leases. */
    private static Graph signalGraph(Random random) {
        Graph pattern = patternGraph(random);
        var nodes = new ArrayList<Graph.Node>();
        var edges = new ArrayList<Graph.Edge>();
        for (var node : pattern.nodes()) if (!node.id().equals("out")) nodes.add(node);
        for (var edge : pattern.edges()) if (!edge.toNode().equals("out")) edges.add(edge);
        nodes.add(new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()));
        nodes.add(new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .5)));
        nodes.add(new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 6000.0)));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("mix", "render"));
        edges.add(Graph.edge("render", "bus"));
        edges.add(Graph.edge("bus", "delay"));
        edges.add(Graph.edge("delay", "bus"));
        edges.add(new Graph.Edge("bus", "out", "out", "audio"));
        return new Graph(3, nodes, edges);
    }

    private static LiveRenderer.Timeline graphTimeline(Graph graph, Map<AssetRef, SampleData> bank) {
        var state = new SessionState(1, 0, 0, 120, true, graph);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(graph), bank), null);
        timeline.prepare(0);
        return timeline;
    }

    private static SampleData longSample() {
        float[] pcm = new float[72000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (.5 * Math.sin(i * .03));
        return new SampleData(48000, 1, pcm);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
