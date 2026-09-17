package groove.engine;

import java.util.*;
import groove.engine.samples.*;

/** Scheduler contracts exercised independently of Minecraft's worker cadence. */
final class SchedulerTests {
    static void run() {
        fractional(1.5); fractional(.25);
        Graph mixed = new Graph(2, List.of(new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 53.0, NodeParam.GAIN, .1)),
                new Graph.Node("a", NodeType.FAST, Map.of(NodeParam.FACTOR, 1.5)),
                new Graph.Node("b", NodeType.FAST, Map.of(NodeParam.FACTOR, .25)),
                new Graph.Node("mix", NodeType.STACK, Map.of()), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "a"), Graph.edge("tone", "b"), Graph.edge("a", "mix"), Graph.edge("b", "mix"), Graph.edge("mix", "out")));
        matchesOffline(mixed, "mixed fractional periods");
        Tone tone = new Tone(Tone.Wave.SINE, 220, .1, 0, 1000);
        int[] queries = {0};
        Pattern counted = arc -> { queries[0]++; return Pattern.tone(tone).fast(1.5).query(arc); };
        LookaheadScheduler scheduler = new LookaheadScheduler(counted, 0, 120);
        scheduler.prepare(0); int initial = queries[0];
        var first = scheduler.window(); scheduler.prepare(.5);
        check(first == scheduler.window() && queries[0] == initial, "Same-cycle preparation reuses snapshot");
        scheduler.prepare(1);
        check(queries[0] == initial + 1, "Rolling ring queries only the new cycle");
        check(scheduler.window().contains(.999), "Worker rollover retains coverage for an in-flight audio callback");
        scheduler.prepare(1000); check(scheduler.window().contains(1000), "Seek fills directly without replay");
        scheduler.prepare(-5); check(scheduler.window().contains(-5), "Backward seek fills directly");
        Pattern duplicates = Pattern.stack(Pattern.tone(tone), Pattern.tone(tone));
        LookaheadScheduler stacked = new LookaheadScheduler(duplicates, 0, 120); stacked.prepare(0);
        long voices = 0;
        for (int i = 0; i < stacked.window().size(); i++) if (stacked.window().entry(i).event().whole().start() == 0) voices++;
        check(voices == 2, "Identical simultaneous voices survive deduplication");
        allocationAndMiss(counted, queries);
        sampleHistory();
        denseOneShotPruning();
        perSourceHistoryIsolation();
        System.out.println("Rolling scheduler regressions passed.");
    }
    private static void fractional(double factor) {
        Graph graph = new Graph(1, List.of(new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 53.0, NodeParam.GAIN, .2)),
                new Graph.Node("speed", NodeType.FAST, Map.of(NodeParam.FACTOR, factor)), new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "speed"), Graph.edge("speed", "out")));
        matchesOffline(graph, "factor " + factor);
    }
    private static void matchesOffline(Graph graph, String description) {
        LoopPlan plan = GraphCompiler.compile(graph);
        var state = new SessionState(1, 0, 0, 120, true, graph);
        var program = new LiveRenderer.Program(state, plan);
        LiveRenderer live = new LiveRenderer(); live.publish(new LiveRenderer.Timeline(program, null));
        int frames = 48000 * 12;
        float[] actual = new float[frames * 2], expected = new float[frames * 2], chunk = new float[1024];
        Renderer offline = new Renderer(Score.compile(plan.pattern(), new Transport(48000, 120, 4), 6), 32);
        offline.render(expected, 0, frames);
        for (int start = 0; start < frames; start += 512) {
            long now = Math.round(start * 1e9 / 48000);
            program.prepare(now);
            int count = Math.min(512, frames - start);
            live.render(chunk, count, now); System.arraycopy(chunk, 0, actual, start * 2, count * 2);
        }
        double error = 0;
        for (int i = 1000; i < actual.length; i++) error = Math.max(error, Math.abs(actual[i] - expected[i]));
        check(error < .001, "Dynamic playback matches continuous offline pattern at " + description + ", error=" + error);
        check(live.scheduleMisses() == 0, "Regular preparation covers fractional playback");
        // A tempo revision at cycle one must retain a slow note whose whole arc started at zero.
        var changed = new LiveRenderer.Program(new SessionState(2, 2_000_000_000L, 1, 150, true, graph), plan);
        LiveRenderer resumed = new LiveRenderer(); resumed.publish(new LiveRenderer.Timeline(changed, null));
        resumed.render(chunk, 512, 2_050_000_000L);
        double energy = 0; for (float value : chunk) energy += value * value;
        check(energy > .01, "Tempo revision retains multi-cycle tone continuation");
    }
    private static void allocationAndMiss(Pattern pattern, int[] queries) {
        var state = new SessionState(1, 0, 0, 120, true, Graph.demo());
        var program = new LiveRenderer.Program(state, new LoopPlan(pattern.query(new Arc(0, 1)), pattern));
        LiveRenderer renderer = new LiveRenderer(); renderer.publish(new LiveRenderer.Timeline(program, null));
        float[] block = new float[256];
        for (int i = 0; i < 1000; i++) renderer.render(block, 128, Math.round(i * 128 * 1e9 / 48000));
        int beforeQueries = queries[0];
        var bean = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported()) {
            bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId(), before = bean.getThreadAllocatedBytes(thread);
            for (int i = 1000; i < 2000; i++) renderer.render(block, 128, Math.round(i * 128 * 1e9 / 48000));
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            check(allocated == 0, "Warmed audio renderer allocates zero bytes: " + allocated);
        }
        check(queries[0] == beforeQueries, "Rendering never queries the pattern");
        renderer.render(block, 128, 40_000_000_000L);
        check(Arrays.equals(block, new float[256]) && renderer.scheduleMisses() > 0, "Missed window is silent, never a stale loop");
        check(queries[0] == beforeQueries, "Miss recovery does not query on audio thread");
        program.prepare(40_000_000_000L);
        renderer.resynchronize(); renderer.render(block, 128, 40_000_000_000L);
        check(!Arrays.equals(block, new float[256]), "Worker refill restores playback after seek");
    }
    private static void sampleHistory() {
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");
        SampleVoice voice = new SampleVoice(ref, .25, .2, 0);
        Pattern pattern = Pattern.sample(voice);
        LookaheadScheduler scheduler = new LookaheadScheduler(pattern, 40, 300); scheduler.prepare(100);
        boolean old = false;
        for (int i = 0; i < scheduler.window().size(); i++) if (scheduler.window().entry(i).event().whole().start() == 51) old = true;
        check(old, "Maximum-length pitched sample onsets remain in history");
        float[] pcm = new float[48000 * 3]; Arrays.fill(pcm, .5f);
        var data = new SampleData(48000, 1, pcm);
        var state = new SessionState(1, 0, 0, 120, true, Graph.demo());
        var program = new LiveRenderer.Program(state, new LoopPlan(pattern.query(new Arc(0, 1)), pattern), Map.of(ref, data));
        program.prepare(10_500_000_000L);
        LiveRenderer a = new LiveRenderer(), b = new LiveRenderer();
        var timeline = new LiveRenderer.Timeline(program, null); a.publish(timeline); b.publish(timeline);
        float[] x = new float[1024], y = new float[1024];
        a.render(x, 512, 10_500_000_000L); b.render(y, 512, 10_500_000_000L);
        check(Arrays.equals(x, y) && x[1000] > .1, "Late joining clients reconstruct overlapping sample tails identically");
    }

    private static void denseOneShotPruning() {
        AssetRef kickRef = FactorySamples.ref("factory:basic/kick.wav");
        SampleVoice voice = new SampleVoice(kickRef, 1.0, 0.8, 0);
        List<Event> cycleEvents = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double start = i / 16.0;
            cycleEvents.add(new Event(new Arc(start, start + 0.05), new Arc(start, start + 0.05), null, voice));
        }
        Pattern pattern = arc -> {
            long startCycle = (long) Math.floor(arc.start());
            long endCycle = (long) Math.ceil(arc.end());
            List<Event> result = new ArrayList<>();
            for (long c = startCycle; c < endCycle; c++) {
                for (Event e : cycleEvents) {
                    Arc whole = new Arc(c + e.whole().start(), c + e.whole().end());
                    Arc part = whole.intersect(arc);
                    if (part != null) result.add(new Event(whole, part, null, voice));
                }
            }
            return result;
        };
        double sampleDuration = 0.1; // 0.05 cycles at 120 bpm
        LookaheadScheduler scheduler = new LookaheadScheduler(pattern, 40, 120, e -> sampleDuration);
        for (int c = 0; c <= 60; c++) scheduler.prepare(c);
        var window = scheduler.window();
        // At cycle 60, base = 60, base - 1 = 59.
        // Lookahead extends to base + 4 = 64.
        // With pruning, only events that sound past cycle 59 remain.
        check(window.size() <= 16 * 6, "Pruning retains only still-sounding one-shot entries, got " + window.size());
        for (int i = 0; i < window.size(); i++) {
            var entry = window.entry(i);
            double endCycle = entry.event().whole().start() + entry.durationSeconds() * 120.0 / 240.0;
            check(endCycle > 59.0, "Pruned all events that finished before base - 1");
        }
    }

    private static void perSourceHistoryIsolation() {
        AssetRef kickRef = FactorySamples.ref("factory:basic/kick.wav");
        Graph graph = new Graph(3, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, 0.8), kickRef),
                new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 440.0, NodeParam.GAIN, 0.2)),
                new Graph.Node("render0", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("render1", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("mix", NodeType.MIX_BUS, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("sample", "render0"),
                Graph.edge("tone", "render1"),
                Graph.edge("render0", "mix"),
                Graph.edge("render1", "mix"),
                new Graph.Edge("mix", "out", "out", "audio")
        ));
        float[] pcm10s = new float[48000 * 10];
        SampleData data10s = new SampleData(48000, 1, pcm10s);
        SessionState state = new SessionState(1, 0, 0, 120, true, graph);
        LoopPlan plan = GraphCompiler.compile(graph);
        LiveRenderer.Program program = new LiveRenderer.Program(state, plan, Map.of(kickRef, data10s));
        check(program.plan().signals().sourcePlan(1).sampleVoices().isEmpty(), "Tone source has no sample voices");
        check(!program.plan().signals().sourcePlan(0).sampleVoices().isEmpty(), "Sample source has sample voices");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
