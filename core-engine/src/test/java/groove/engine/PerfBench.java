package groove.engine;

import groove.engine.samples.*;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Stage 4 baseline performance benchmark harness.
 * Measures scenarios B1..B9 with warmup convergence, pooled block timings,
 * real-time ratio, publish allocations, and system metadata.
 */
public final class PerfBench {
    private static final int BLOCKS_PER_TRIAL = 2000;
    private static final int BLOCK_FRAMES = 512;
    private static final double BLOCK_BUDGET_MS = 10.67;
    private static final long MAX_WARMUP_NANOS = 120L * 1_000_000_000L; // 120 seconds cap

    private static final AssetRef STEREO_REF_192K = new AssetRef(
            "custom:perf_stereo_192k.wav",
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");

    private static SampleData createStereoSample192k() {
        int rate = 192000;
        int frames = rate * 2; // 2.0 seconds
        float[] pcm = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / rate;
            pcm[i * 2] = (float) (Math.sin(2 * Math.PI * 440 * t) * 0.7);
            pcm[i * 2 + 1] = (float) (Math.sin(2 * Math.PI * 660 * t) * 0.7);
        }
        return new SampleData(rate, 2, pcm);
    }

    private record Scenario(String id, String description, Graph graph, Map<AssetRef, SampleData> sampleBank) {
    }

    public static void main(String[] args) {
        String affinity = pinToPerformanceCores();
        printSystemInfo(affinity);

        AssetRef kickRef = FactorySamples.ref("factory:basic/kick.wav");
        SampleData kickData = WavDecoder.decode(FactorySamples.bytes("factory:basic/kick.wav"));
        AssetRef hatRef = FactorySamples.ref("factory:basic/hat.wav");
        SampleData hatData = WavDecoder.decode(FactorySamples.bytes("factory:basic/hat.wav"));
        SampleData stereoData192k = createStereoSample192k();

        Map<AssetRef, SampleData> sampleBank = Map.of(
                kickRef, kickData,
                hatRef, hatData,
                STEREO_REF_192K, stereoData192k);

        List<Scenario> scenarios = List.of(
                new Scenario("B1", "32 tone voices (saw, pulse)", b1Graph(), Map.of()),
                new Scenario("B2", "32 mono sample voices at 1x", b2Graph(kickRef), sampleBank),
                new Scenario("B3", "32 stereo sample voices at 15.996x", b3Graph(STEREO_REF_192K), sampleBank),
                new Scenario("B4", "32 stereo sample voices at 16.000x", b4Graph(STEREO_REF_192K), sampleBank),
                new Scenario("B5", "8 audio sources, filters, feedback delay", b5Graph(), Map.of()),
                new Scenario("B6", "B5 plus 2 reverbs", b6Graph(), Map.of()),
                new Scenario("B7", "32 sustained looped stereo voices at 4x", b7Graph(STEREO_REF_192K), sampleBank),
                new Scenario("B7b", "112 hat one-shots per cycle beside one loop", b7DenseGraph(STEREO_REF_192K, hatRef), sampleBank),
                new Scenario("B9", "Adversarial: 128 events, 8 sources, 2 triggers, 32 loops at 15.996x, 2 reverbs at 20 s",
                        b9Graph(STEREO_REF_192K), sampleBank));

        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Running scenarios B1..B9...");
        System.out.println("--------------------------------------------------------------------------------");

        List<Result> results = new ArrayList<>();
        List<String> gates = new ArrayList<>();
        String only = System.getProperty("perf.only", "").trim();
        for (Scenario scenario : scenarios) {
            if (!only.isEmpty() && !Arrays.asList(only.split(",")).contains(scenario.id())) continue;
            Result result = runScenario(scenario);
            results.add(result);
            printResult(result);
            if (scenario.id().equals("B9")) b9Gates(result, scenario, gates);
        }

        if (only.isEmpty() || Arrays.asList(only.split(",")).contains("B8")) {
            Graph b8 = b8Graph(STEREO_REF_192K);
            Result gated = null;
            for (int renderers : new int[] {1, 4, 8}) {
                Result result = runReplayScenario(renderers, b8, sampleBank);
                results.add(result);
                printResult(result);
                if (renderers == 8) gated = result;
            }
            Result queued = runQueuedScenario(b8, sampleBank);
            results.add(queued);
            printResult(queued);
            gates.add(String.format(Locale.ROOT, "B8 gate (8 renderers, pooled p99 <= %.2f ms per block round): %s at %.4f ms",
                    B8_P99_GATE_MS, gated.p99BlockMs() <= B8_P99_GATE_MS ? "PASS" : "FAIL", gated.p99BlockMs()));
            gates.add(String.format(Locale.ROOT, "B8 grant overhead: %.1f ns per release, grant and requeue with 7 waiters", grantOverheadNanos()));
        }
        printSummaryTable(results);
        gates.forEach(System.out::println);
    }

    /** Recorded 2026-09-17 on the reference machine when B9 was added; B9 did not exist at step 5b. */
    private static final long B9_PUBLISH_BASELINE_BYTES = 1_202_848;

    /** B9 criteria: one renderer's pooled p99 fits the block, and steady publish bytes stay within 10% of the baseline. */
    private static void b9Gates(Result result, Scenario scenario, List<String> gates) {
        gates.add(String.format(Locale.ROOT, "B9 gate (pooled p99 <= %.2f ms per block): %s at %.4f ms",
                BLOCK_BUDGET_MS, result.p99BlockMs() <= BLOCK_BUDGET_MS ? "PASS" : "FAIL", result.p99BlockMs()));
        long bytes = steadyPublishBytes(scenario);
        if (B9_PUBLISH_BASELINE_BYTES < 0) {
            gates.add(String.format(Locale.ROOT, "B9 publish: %,d bytes per publish, no baseline recorded yet", bytes));
            return;
        }
        long limit = B9_PUBLISH_BASELINE_BYTES + B9_PUBLISH_BASELINE_BYTES / 10;
        gates.add(String.format(Locale.ROOT, "B9 publish gate (<= %,d bytes, baseline %,d + 10%%): %s at %,d bytes",
                limit, B9_PUBLISH_BASELINE_BYTES, bytes <= limit ? "PASS" : "FAIL", bytes));
    }

    /** The smallest of several publishes after a warm-up, so one-time class and JIT allocations don't count. */
    private static long steadyPublishBytes(Scenario scenario) {
        var bean = AllocHelper.bean();
        if (bean == null) return -1;
        var state = new SessionState(1, 0, 0, 120, true, scenario.graph());
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(scenario.graph()), scenario.sampleBank()), null);
        LiveRenderer renderer = new LiveRenderer();
        long id = Thread.currentThread().threadId(), smallest = Long.MAX_VALUE;
        for (int i = 0; i < 12; i++) {
            long before = bean.getThreadAllocatedBytes(id);
            renderer.publish(timeline);
            long bytes = bean.getThreadAllocatedBytes(id) - before;
            if (i >= 2) smallest = Math.min(smallest, bytes);
        }
        return smallest;
    }

    private static final double B8_P99_GATE_MS = BLOCK_BUDGET_MS / 2;
    private static final long B8_JOIN_NANOS = 3_000_000_000L;

    /**
     * B8: renderers on one budget join the B6+B7 graph together after a scheduled commit took effect,
     * so every pending program replays; blocks are timed as one round of all renderers, as the sound
     * thread pays it, only while replay is in progress.
     */
    private static Result runReplayScenario(int renderers, Graph graph, Map<AssetRef, SampleData> bank) {
        String id = "B8x" + renderers;
        int minRoundsPerTrial = Math.max(250, BLOCKS_PER_TRIAL / renderers);
        int leaseRounds = (renderers + ReplayBudget.DEFAULT_LEASES - 1) / ReplayBudget.DEFAULT_LEASES;
        int stormRounds = leaseRounds * ((LiveRenderer.FULL_RECOVERY_FRAMES + BLOCK_FRAMES - 1) / BLOCK_FRAMES + 1) + 2;
        long stepNanos = Math.round(BLOCK_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE);
        // Renderers render whole blocks in turn, so a lease freed mid-block can serve a later renderer's
        // whole block in the same round; each recovery outlasts a block, so a lease changes hands at most once
        long workBound = 2L * ReplayBudget.DEFAULT_LEASES * LiveRenderer.REPLAY_PER_FRAME * BLOCK_FRAMES;
        LoopPlan plan = GraphCompiler.compile(graph);
        float[] blockBuf = new float[BLOCK_FRAMES * 2];

        var bean = AllocHelper.bean();
        LiveRenderer probe = new LiveRenderer(new ReplayBudget(1));
        LiveRenderer.Timeline probeTimeline = b8Timeline(graph, plan, bank);
        long allocBefore = bean != null ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;
        probe.publish(probeTimeline);
        long allocAfter = bean != null ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;

        long startNanos = System.nanoTime();
        List<double[]> trialBlockTimes = new ArrayList<>();
        List<Double> trialMedians = new ArrayList<>();
        boolean settled = false;
        long gcCountBefore = getGcCount(), gcTimeBefore = getGcTimeMs();
        long storms = 0, grants = 0, maxRoundWork = 0, rounds = 0;

        while (true) {
            // Whole storms only, so later grants in a storm are timed as often as the first
            double[] blockTimes = new double[minRoundsPerTrial + stormRounds + 64];
            int filled = 0;
            while (filled < minRoundsPerTrial) {
                // Each storm starts fresh: a new budget, timeline and renderers, all outside the timed rounds
                var budget = new ReplayBudget(renderers);
                var timeline = b8Timeline(graph, plan, bank);
                var group = new LiveRenderer[renderers];
                for (int r = 0; r < renderers; r++) { group[r] = new LiveRenderer(budget); group[r].publish(timeline); }
                long target = B8_JOIN_NANOS;
                boolean busy = true;
                while (busy) {
                    if (filled == blockTimes.length) throw new IllegalStateException(id + " storm ran past " + stormRounds + " rounds");
                    timeline.prepare(target);
                    long workBefore = 0;
                    for (var renderer : group) workBefore += renderer.historyFrames();
                    long t0 = System.nanoTime();
                    for (var renderer : group) renderer.render(blockBuf, BLOCK_FRAMES, target);
                    long elapsed = System.nanoTime() - t0;
                    long work = -workBefore;
                    busy = false;
                    for (var renderer : group) {
                        work += renderer.historyFrames();
                        busy |= renderer.replayInProgress();
                        if (renderer.scheduleMisses() > 0)
                            throw new IllegalStateException(id + " missed " + renderer.scheduleMisses() + " schedule windows");
                    }
                    rounds++;
                    if (work > workBound) throw new IllegalStateException(id + " replayed " + work + " frames in one block round, bound " + workBound);
                    maxRoundWork = Math.max(maxRoundWork, work);
                    blockTimes[filled++] = elapsed / 1_000_000.0;
                    target += stepNanos;
                }
                storms++;
                grants += budget.grants();
            }

            blockTimes = Arrays.copyOf(blockTimes, filled);
            double[] sorted = blockTimes.clone();
            Arrays.sort(sorted);
            trialBlockTimes.add(blockTimes);
            trialMedians.add(sorted[filled / 2]);
            int n = trialMedians.size();
            if (n >= 4) {
                double m1 = trialMedians.get(n - 3), m2 = trialMedians.get(n - 2), m3 = trialMedians.get(n - 1);
                double min = Math.min(m1, Math.min(m2, m3)), max = Math.max(m1, Math.max(m2, m3));
                if (min > 0 && (max - min) / min <= 0.05) { settled = true; break; }
            }
            if (System.nanoTime() - startNanos >= MAX_WARMUP_NANOS) break;
        }
        System.out.printf(Locale.ROOT, "  %s: %d storms, %d grants, max replay work %d frames per block round (bound 2k: %d) over %d rounds%n",
                id, storms, grants, maxRoundWork, workBound, rounds);
        return pooledResult(id, renderers + " renderers replaying B6 + " + b8Loops(graph) + " loops on one budget, per block round",
                settled, trialBlockTimes, trialMedians.size(), Math.max(0, allocAfter - allocBefore),
                getGcCount() - gcCountBefore, getGcTimeMs() - gcTimeBefore);
    }

    /** Queued-capture cost: 8 renderers whose requests all wait behind leases that are never released. */
    private static Result runQueuedScenario(Graph graph, Map<AssetRef, SampleData> bank) {
        LoopPlan plan = GraphCompiler.compile(graph);
        var budget = new ReplayBudget(8);
        for (int i = 0; i < budget.leases(); i++) budget.request(new ReplayBudget.Lease());
        var timeline = b8Timeline(graph, plan, bank);
        var group = new LiveRenderer[8];
        for (int r = 0; r < group.length; r++) { group[r] = new LiveRenderer(budget); group[r].publish(timeline); }
        float[] blockBuf = new float[BLOCK_FRAMES * 2];
        long stepNanos = Math.round(BLOCK_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE);
        long target = B8_JOIN_NANOS;
        long gcCountBefore = getGcCount(), gcTimeBefore = getGcTimeMs();
        List<double[]> trials = new ArrayList<>();
        for (int trial = 0; trial < 4; trial++) {
            double[] times = new double[BLOCKS_PER_TRIAL];
            for (int b = 0; b < BLOCKS_PER_TRIAL; b++) {
                timeline.prepare(target);
                long t0 = System.nanoTime();
                for (var renderer : group) renderer.render(blockBuf, BLOCK_FRAMES, target);
                times[b] = (System.nanoTime() - t0) / 1_000_000.0;
                target += stepNanos;
            }
            trials.add(times);
        }
        for (var renderer : group)
            if (renderer.historyFrames() != 0 || renderer.scheduleMisses() != 0 || renderer.replayQueuedFrames() == 0)
                throw new IllegalStateException("B8q renderers must wait without replaying or missing windows");
        // The first trial warms the JIT and is dropped by pooling the last three
        return pooledResult("B8q", "8 renderers waiting for a lease, per block round", true, trials, trials.size(), 0,
                getGcCount() - gcCountBefore, getGcTimeMs() - gcTimeBefore);
    }

    /** Average cost of one lease handoff: release the holder, grant the queue head, requeue the old holder. */
    private static double grantOverheadNanos() {
        var budget = new ReplayBudget(1, 8);
        var leases = new ReplayBudget.Lease[8];
        for (int i = 0; i < leases.length; i++) { leases[i] = new ReplayBudget.Lease(); budget.request(leases[i]); }
        int holder = 0, cycles = 2_000_000;
        for (int warm = 0; warm < 2; warm++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < cycles; i++) {
                budget.release(leases[holder]);
                budget.request(leases[holder]);
                holder = (holder + 1) % leases.length;
            }
            if (warm == 1) return (System.nanoTime() - t0) / (double) cycles;
        }
        throw new AssertionError();
    }

    private static Result pooledResult(String id, String description, boolean settled, List<double[]> trials, int trialsRun,
                                       long publishAlloc, long gcCollections, long gcTimeMs) {
        int trialsToPool = Math.min(3, trials.size());
        double[] pooled = new double[0];
        for (int i = trials.size() - trialsToPool; i < trials.size(); i++) {
            double[] trial = trials.get(i);
            int at = pooled.length;
            pooled = Arrays.copyOf(pooled, at + trial.length);
            System.arraycopy(trial, 0, pooled, at, trial.length);
        }
        Arrays.sort(pooled);
        double median = pooled[pooled.length / 2];
        return new Result(id, description, settled ? "settled" : "unsettled", trialsRun, publishAlloc, median,
                pooled[(int) (pooled.length * 0.99)], pooled[pooled.length - 1], median / BLOCK_BUDGET_MS, gcCollections, gcTimeMs);
    }

    private static long b8Loops(Graph graph) {
        return graph.nodes().stream().filter(node -> node.id().startsWith("loop") && node.type() == NodeType.GENERATOR_SAMPLE).count();
    }

    private static LiveRenderer.Timeline b8Timeline(Graph graph, LoopPlan plan, Map<AssetRef, SampleData> bank) {
        var current = new SessionState(1, 0, 0, 120, true, graph);
        // The commit took effect a second before the join, so pending has a full second of history
        var pending = new SessionState(2, B8_JOIN_NANOS - 1_000_000_000L, current.cycleAt(B8_JOIN_NANOS - 1_000_000_000L), 120, true, graph);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(current, plan, bank), new LiveRenderer.Program(pending, plan, bank));
        timeline.prepare(B8_JOIN_NANOS);
        return timeline;
    }

    private record Result(
            String id,
            String description,
            String status,
            int trialsRun,
            long publishAllocBytes,
            double medianBlockMs,
            double p99BlockMs,
            double maxBlockMs,
            double realTimeRatio,
            long gcCollections,
            long gcTimeMs) {
    }

    private static Result runScenario(Scenario scenario) {
        SessionState state = new SessionState(1, 0, 0, 120, true, scenario.graph());
        LoopPlan plan = GraphCompiler.compile(scenario.graph());
        LiveRenderer.Program program = new LiveRenderer.Program(state, plan, scenario.sampleBank());
        LiveRenderer.Timeline timeline = new LiveRenderer.Timeline(program, null);

        // Measure publish allocation
        var bean = AllocHelper.bean();
        LiveRenderer benchRenderer = new LiveRenderer();
        long allocBefore = bean != null ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;
        benchRenderer.publish(timeline);
        long allocAfter = bean != null ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;
        long publishAlloc = Math.max(0, allocAfter - allocBefore);

        // Warm up until 3 consecutive trial medians are within 5%, capped at 120 s
        long startNanos = System.nanoTime();
        List<double[]> trialBlockTimes = new ArrayList<>();
        List<Double> trialMedians = new ArrayList<>();
        boolean settled = false;

        float[] blockBuf = new float[BLOCK_FRAMES * 2];
        long stepNanos = Math.round(BLOCK_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE);

        long gcCountBefore = getGcCount();
        long gcTimeBefore = getGcTimeMs();

        while (true) {
            double[] blockTimes = new double[BLOCKS_PER_TRIAL];
            long targetNanos = 0;
            benchRenderer.resynchronize();

            for (int b = 0; b < BLOCKS_PER_TRIAL; b++) {
                // Worker-thread work, so it stays outside the timed render
                timeline.prepare(targetNanos);
                long t0 = System.nanoTime();
                benchRenderer.render(blockBuf, BLOCK_FRAMES, targetNanos);
                long elapsed = System.nanoTime() - t0;
                blockTimes[b] = elapsed / 1_000_000.0;
                targetNanos += stepNanos;
            }

            double[] sorted = blockTimes.clone();
            Arrays.sort(sorted);
            double trialMedian = sorted[BLOCKS_PER_TRIAL / 2];
            if (benchRenderer.scheduleMisses() > 0)
                throw new IllegalStateException(scenario.id() + " missed " + benchRenderer.scheduleMisses() + " schedule windows");

            trialBlockTimes.add(blockTimes);
            trialMedians.add(trialMedian);

            int n = trialMedians.size();
            // Trial 0 runs while the JIT is still compiling, so it is never checked or pooled
            if (n >= 4) {
                double m1 = trialMedians.get(n - 3);
                double m2 = trialMedians.get(n - 2);
                double m3 = trialMedians.get(n - 1);
                double min = Math.min(m1, Math.min(m2, m3));
                double max = Math.max(m1, Math.max(m2, m3));
                if (min > 0 && (max - min) / min <= 0.05) {
                    settled = true;
                    break;
                }
            }

            if (System.nanoTime() - startNanos >= MAX_WARMUP_NANOS) {
                break;
            }
        }

        long gcCollections = getGcCount() - gcCountBefore;
        long gcTimeMs = getGcTimeMs() - gcTimeBefore;

        // Pool the last 3 trials (or all if fewer than 3)
        int trialsToPool = Math.min(3, trialBlockTimes.size());
        int pooledCount = trialsToPool * BLOCKS_PER_TRIAL;
        double[] pooled = new double[pooledCount];
        int startIdx = trialBlockTimes.size() - trialsToPool;
        int dst = 0;
        for (int i = startIdx; i < trialBlockTimes.size(); i++) {
            double[] trial = trialBlockTimes.get(i);
            System.arraycopy(trial, 0, pooled, dst, BLOCKS_PER_TRIAL);
            dst += BLOCKS_PER_TRIAL;
        }

        Arrays.sort(pooled);
        double median = pooled[pooledCount / 2];
        double p99 = pooled[(int) (pooledCount * 0.99)];
        double max = pooled[pooledCount - 1];
        double rtRatio = median / BLOCK_BUDGET_MS;

        return new Result(
                scenario.id(),
                scenario.description(),
                settled ? "settled" : "unsettled",
                trialMedians.size(),
                publishAlloc,
                median,
                p99,
                max,
                rtRatio,
                gcCollections,
                gcTimeMs);
    }

    private static void printResult(Result r) {
        System.out.printf("[%s] %s%n", r.id(), r.description());
        System.out.printf("  Status:          %s (%d trials)%n", r.status(), r.trialsRun());
        System.out.printf("  Publish alloc:   %,d bytes%n", r.publishAllocBytes());
        System.out.printf("  Median block:    %.4f ms (budget: %.2f ms)%n", r.medianBlockMs(), BLOCK_BUDGET_MS);
        System.out.printf("  Real-time ratio: %.4fx (%.1fx faster than real-time)%n", r.realTimeRatio(),
                1.0 / r.realTimeRatio());
        System.out.printf("  p99 block:       %.4f ms%n", r.p99BlockMs());
        System.out.printf("  Max block:       %.4f ms%n", r.maxBlockMs());
        System.out.printf("  GC Pauses:       %d collections, %d ms%n%n", r.gcCollections(), r.gcTimeMs());
    }

    private static void printSummaryTable(List<Result> results) {
        System.out.println("================================================================================");
        System.out.println("SUMMARY BASELINE TABLE (for docs/ENGINE-UPGRADE-STAGES.md):");
        System.out.println("================================================================================");
        System.out.println(
                "| Id | Scenario | Median (ms) | p99 (ms) | Max (ms) | RT Ratio | Publish Alloc (B) | Status |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- |");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "| %s | %s | %.4f | %.4f | %.4f | %.4f | %,d | %s |%n",
                    r.id(), r.description(), r.medianBlockMs(), r.p99BlockMs(), r.maxBlockMs(),
                    r.realTimeRatio(), r.publishAllocBytes(), r.status());
        }
        System.out.println("================================================================================");
    }

    private static void printSystemInfo(String affinity) {
        System.out.println("================================================================================");
        System.out.println("Groove Engine Performance Benchmark (perfBench)");
        System.out.println("================================================================================");
        System.out.println("System Information:");
        System.out.printf("  JDK:            %s (%s)%n", System.getProperty("java.runtime.version"),
                System.getProperty("java.vm.vendor"));
        System.out.printf("  OS:             %s %s (%s)%n", System.getProperty("os.name"),
                System.getProperty("os.version"), System.getProperty("os.arch"));
        System.out.printf("  CPU Model:      %s%n", getCpuModel());
        System.out.printf("  Logical Cores:  %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("  Power Plan:     %s%n", getWindowsPowerPlan());
        System.out.printf("  CPU Affinity:   %s%n", affinity);
        System.out.println("================================================================================");
    }

    // Finds logical processors in the highest efficiency class, then applies them to this process
    private static final String AFFINITY_SCRIPT = String.join("\n",
            "param([long]$ProcessId, [string]$Override)",
            "Add-Type -TypeDefinition @'",
            "using System;",
            "using System.Runtime.InteropServices;",
            "public static class GrooveCpuSets {",
            "    [DllImport(\"kernel32.dll\")]",
            "    static extern bool GetSystemCpuSetInformation(IntPtr info, uint length, out uint returned, IntPtr process, uint flags);",
            "    [DllImport(\"kernel32.dll\")]",
            "    static extern IntPtr OpenProcess(uint access, bool inherit, uint id);",
            "    [DllImport(\"kernel32.dll\")]",
            "    static extern bool SetProcessInformation(IntPtr process, int infoClass, int[] info, uint size);",
            "    [DllImport(\"kernel32.dll\")]",
            "    static extern bool CloseHandle(IntPtr handle);",
            "    public static bool DisablePowerThrottling(uint id) {",
            "        IntPtr handle = OpenProcess(0x2000, false, id);",
            "        if (handle == IntPtr.Zero) return false;",
            "        try { return SetProcessInformation(handle, 4, new[] { 1, 1, 0 }, 12); }",
            "        finally { CloseHandle(handle); }",
            "    }",
            "    public static long PerformanceMask() {",
            "        uint needed;",
            "        GetSystemCpuSetInformation(IntPtr.Zero, 0, out needed, IntPtr.Zero, 0);",
            "        IntPtr buf = Marshal.AllocHGlobal((int) needed);",
            "        try {",
            "            if (!GetSystemCpuSetInformation(buf, needed, out needed, IntPtr.Zero, 0)) return 0;",
            "            int best = -1; long mask = 0;",
            "            for (int offset = 0; offset < needed; offset += Marshal.ReadInt32(buf, offset)) {",
            "                int logical = Marshal.ReadByte(buf, offset + 14), efficiency = Marshal.ReadByte(buf, offset + 18);",
            "                if (logical >= 64) continue;",
            "                if (efficiency > best) { best = efficiency; mask = 0; }",
            "                if (efficiency == best) mask |= 1L << logical;",
            "            }",
            "            return mask;",
            "        } finally { Marshal.FreeHGlobal(buf); }",
            "    }",
            "}",
            "'@",
            "$mask = if ($Override) { [Convert]::ToInt64($Override, 16) } else { [GrooveCpuSets]::PerformanceMask() }",
            "if ($mask -ne 0) { (Get-Process -Id $ProcessId).ProcessorAffinity = [IntPtr]$mask }",
            "$fullSpeed = [GrooveCpuSets]::DisablePowerThrottling([uint32]$ProcessId)",
            "'{0:X} {1}' -f $mask, $fullSpeed");

    /** Hybrid CPUs move busy threads onto efficiency cores after a few seconds, and Windows throttles
     *  windowless children of the Gradle daemon; either halves throughput. */
    private static String pinToPerformanceCores() {
        String override = System.getProperty("perf.affinity", "").trim();
        if (override.equalsIgnoreCase("none")) return "not pinned (perf.affinity=none)";
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
            return "not pinned (Windows only)";
        try {
            Path script = Files.createTempFile("groove-affinity", ".ps1");
            try {
                Files.writeString(script, AFFINITY_SCRIPT);
                List<String> command = new ArrayList<>(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString(), "-ProcessId", Long.toString(ProcessHandle.current().pid())));
                if (!override.isEmpty()) command.addAll(List.of("-Override", override));
                Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                if (p.waitFor() != 0 || !output.matches("[0-9A-F]+ (True|False)") || output.startsWith("0 "))
                    return "not pinned (" + output.replaceAll("\\s+", " ") + ")";
                String[] parts = output.split(" ");
                return "0x" + parts[0] + (override.isEmpty() ? " (performance cores)" : " (perf.affinity)")
                        + (parts[1].equals("True") ? ", power throttling off" : ", power throttling unchanged");
            } finally {
                Files.deleteIfExists(script);
            }
        } catch (Exception e) {
            return "not pinned (" + e.getMessage() + ")";
        }
    }

    private static String getCpuModel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String id = System.getenv("PROCESSOR_IDENTIFIER");
            return id != null && !id.isBlank() ? id : "unknown";
        }
        if (os.contains("linux")) {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "unknown";
    }

    private static String getWindowsPowerPlan() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win"))
            return "N/A";
        try {
            Process p = new ProcessBuilder("powercfg", "/getactivescheme").start();
            try (var scanner = new Scanner(p.getInputStream())) {
                if (scanner.hasNextLine()) {
                    return scanner.nextLine().trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static long getGcCount() {
        long count = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, gc.getCollectionCount());
        }
        return count;
    }

    private static long getGcTimeMs() {
        long time = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            time += Math.max(0, gc.getCollectionTime());
        }
        return time;
    }

    // B1: 32 tone voices (saw, pulse)
    private static Graph b1Graph() {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("stack1", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("stack2", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("stack1", "mix"));
        edges.add(Graph.edge("stack2", "mix"));
        edges.add(Graph.edge("mix", "out"));

        for (int i = 0; i < 32; i++) {
            String id = "tone" + i;
            double wave = i % 2 == 0 ? 1.0 : 2.0;
            nodes.add(new Graph.Node(id, NodeType.TONE, Map.of(
                    NodeParam.WAVE, wave,
                    NodeParam.FREQUENCY, 110.0 + i * 20.0,
                    NodeParam.GAIN, 0.25 / 32,
                    NodeParam.PULSE_WIDTH, 0.3)));
            String parent = i < 16 ? "stack1" : "stack2";
            edges.add(Graph.edge(id, parent));
        }
        return new Graph(1, nodes, edges);
    }

    // B2: 32 mono sample voices at 1x
    private static Graph b2Graph(AssetRef kickRef) {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("stack1", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("stack2", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("stack1", "mix"));
        edges.add(Graph.edge("stack2", "mix"));
        edges.add(Graph.edge("mix", "out"));

        for (int i = 0; i < 32; i++) {
            String id = "sample" + i;
            nodes.add(new Graph.Node(id, NodeType.GENERATOR_SAMPLE, Map.of(
                    NodeParam.PITCH_RATIO, 1.0,
                    NodeParam.GAIN, 0.8 / 32), kickRef));
            String parent = i < 16 ? "stack1" : "stack2";
            edges.add(Graph.edge(id, parent));
        }
        return new Graph(2, nodes, edges);
    }

    // B3: 32 stereo sample voices at 15.996x
    private static Graph b3Graph(AssetRef stereoRef192k) {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("stack1", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("stack2", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("stack1", "mix"));
        edges.add(Graph.edge("stack2", "mix"));
        edges.add(Graph.edge("mix", "out"));

        for (int i = 0; i < 32; i++) {
            String id = "sample" + i;
            nodes.add(new Graph.Node(id, NodeType.GENERATOR_SAMPLE, Map.of(
                    NodeParam.PITCH_RATIO, 3.999,
                    NodeParam.GAIN, 0.8 / 32), stereoRef192k));
            String parent = i < 16 ? "stack1" : "stack2";
            edges.add(Graph.edge(id, parent));
        }
        return new Graph(2, nodes, edges);
    }

    // B4: 32 stereo sample voices at 16.000x
    private static Graph b4Graph(AssetRef stereoRef192k) {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("stack1", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("stack2", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("stack1", "mix"));
        edges.add(Graph.edge("stack2", "mix"));
        edges.add(Graph.edge("mix", "out"));

        for (int i = 0; i < 32; i++) {
            String id = "sample" + i;
            nodes.add(new Graph.Node(id, NodeType.GENERATOR_SAMPLE, Map.of(
                    NodeParam.PITCH_RATIO, 4.0,
                    NodeParam.GAIN, 0.8 / 32), stereoRef192k));
            String parent = i < 16 ? "stack1" : "stack2";
            edges.add(Graph.edge(id, parent));
        }
        return new Graph(2, nodes, edges);
    }

    // B5: 8 audio sources, filters, feedback delay
    private static Graph b5Graph() {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.65)));
        nodes.add(new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.SYNC, 1.0, NodeParam.DIVISION, 2.0)));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("mix", "delay"));
        edges.add(Graph.edge("delay", "mix"));
        edges.add(new Graph.Edge("mix", "out", "out", "audio"));

        for (int i = 0; i < 8; i++) {
            String toneId = "tone" + i;
            String rhythmId = "rhythm" + i;
            String renderId = "render" + i;
            String filterId = "filter" + i;
            nodes.add(new Graph.Node(toneId, NodeType.TONE, Map.of(
                    NodeParam.FREQUENCY, 110.0 + i * 40.0,
                    NodeParam.GAIN, 0.15 / 8,
                    NodeParam.WAVE, 1.0)));
            nodes.add(new Graph.Node(rhythmId, NodeType.EUCLID, Map.of(
                    NodeParam.STEPS, 8.0,
                    NodeParam.PULSES, (double) ((i % 5) + 2))));
            nodes.add(new Graph.Node(renderId, NodeType.AUDIO_RENDER, Map.of()));
            nodes.add(new Graph.Node(filterId, NodeType.FILTER, Map.of(
                    NodeParam.CUTOFF_HZ, 500.0 + i * 200.0,
                    NodeParam.RESONANCE_Q, 1.5)));
            edges.add(Graph.edge(toneId, rhythmId));
            edges.add(Graph.edge(rhythmId, renderId));
            edges.add(Graph.edge(renderId, filterId));
            edges.add(Graph.edge(filterId, "mix"));
        }
        return new Graph(3, nodes, edges);
    }

    // B6: B5 with a short and a long reverb in parallel after the feedback mix
    private static Graph b6Graph() {
        Graph b5 = b5Graph();
        List<Graph.Node> nodes = new ArrayList<>(b5.nodes());
        List<Graph.Edge> edges = new ArrayList<>(b5.edges());
        edges.remove(new Graph.Edge("mix", "out", "out", "audio"));
        nodes.add(new Graph.Node("room", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 0.6, NodeParam.DAMPING_HZ, 8000.0)));
        nodes.add(new Graph.Node("hall", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 2.5, NodeParam.PRE_DELAY_MS, 20.0)));
        nodes.add(new Graph.Node("master", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)));
        edges.add(Graph.edge("mix", "room"));
        edges.add(Graph.edge("mix", "hall"));
        edges.add(Graph.edge("mix", "master"));
        edges.add(Graph.edge("room", "master"));
        edges.add(Graph.edge("hall", "master"));
        edges.add(new Graph.Edge("master", "out", "out", "audio"));
        return new Graph(3, nodes, edges);
    }

    // B8: B6 with its last audio source replaced by B7's sustained loops, as many as MAX_NODES allows
    private static Graph b8Graph(AssetRef stereoRef192k) {
        Graph b6 = b6Graph();
        List<Graph.Node> nodes = new ArrayList<>();
        for (var node : b6.nodes()) if (!node.id().equals("tone7") && !node.id().equals("rhythm7")) nodes.add(node);
        List<Graph.Edge> edges = new ArrayList<>();
        for (var edge : b6.edges()) if (!edge.fromNode().equals("tone7") && !edge.fromNode().equals("rhythm7")) edges.add(edge);
        // A stack takes at most 16 inputs, so the loops split across two like B7
        nodes.add(new Graph.Node("loops", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("loopsA", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("loopsB", NodeType.STACK, Map.of()));
        edges.add(Graph.edge("loops", "render7"));
        edges.add(Graph.edge("loopsA", "loops"));
        edges.add(Graph.edge("loopsB", "loops"));
        int loops = GraphCompiler.MAX_NODES - nodes.size();
        for (int i = 0; i < loops; i++) {
            String id = "loop" + i;
            nodes.add(new Graph.Node(id, NodeType.GENERATOR_SAMPLE, loopParams(1.0 + i * 0.01, 0.8 / loops), stereoRef192k));
            edges.add(Graph.edge(id, i < 16 ? "loopsA" : "loopsB"));
        }
        return new Graph(3, nodes, edges);
    }

    // B9: the compiler's limits at once. 128 events per cycle (32 loops + 7 audio sources at 10 + 2 triggers
    // at 13), 8 audio sources, 2 trigger sources, 32 looped stereo voices at 15.996x, 2 reverbs at 20 s.
    // Sources share one tone and the triggers share one pattern so the graph fits in 64 nodes.
    private static Graph b9Graph(AssetRef stereoRef192k) {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("loopsA", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("loopsB", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("loops", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("render0", NodeType.AUDIO_RENDER, Map.of()));
        edges.add(Graph.edge("loopsA", "loops"));
        edges.add(Graph.edge("loopsB", "loops"));
        edges.add(Graph.edge("loops", "render0"));
        for (int i = 0; i < 32; i++) {
            String id = "loop" + i;
            nodes.add(new Graph.Node(id, NodeType.GENERATOR_SAMPLE, loopParams(3.999, 0.4 / 32), stereoRef192k));
            edges.add(Graph.edge(id, i < 16 ? "loopsA" : "loopsB"));
        }
        nodes.add(new Graph.Node("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)));
        edges.add(Graph.edge("render0", "mix"));
        nodes.add(new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.WAVE, 1.0, NodeParam.FREQUENCY, 220.0, NodeParam.GAIN, 0.2 / 7)));
        for (int i = 1; i < 8; i++) {
            String fast = "dense" + i, render = "render" + i;
            nodes.add(new Graph.Node(fast, NodeType.FAST, Map.of(NodeParam.FACTOR, 10.0)));
            nodes.add(new Graph.Node(render, NodeType.AUDIO_RENDER, Map.of()));
            edges.add(Graph.edge("tone", fast));
            edges.add(Graph.edge(fast, render));
            edges.add(Graph.edge(render, "mix"));
        }
        nodes.add(new Graph.Node("triggerPattern", NodeType.FAST, Map.of(NodeParam.FACTOR, 13.0)));
        edges.add(Graph.edge("tone", "triggerPattern"));
        nodes.add(new Graph.Node("master", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)));
        for (int t = 0; t < 2; t++) {
            String trig = "trig" + t, env = "env" + t;
            nodes.add(new Graph.Node(trig, NodeType.TRIGGER_RENDER, Map.of()));
            nodes.add(new Graph.Node(env, NodeType.ENVELOPE, Map.of()));
            edges.add(Graph.edge("triggerPattern", trig));
            edges.add(new Graph.Edge(trig, "out", env, "trigger"));
            edges.add(new Graph.Edge(env, "out", t == 0 ? "mix" : "master", "gain"));
        }
        nodes.add(new Graph.Node("filter", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, 3000.0, NodeParam.RESONANCE_Q, 1.5)));
        nodes.add(new Graph.Node("room", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 20.0)));
        nodes.add(new Graph.Node("hall", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 20.0, NodeParam.PRE_DELAY_MS, 20.0)));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("mix", "filter"));
        edges.add(Graph.edge("filter", "room"));
        edges.add(Graph.edge("filter", "hall"));
        edges.add(Graph.edge("filter", "master"));
        edges.add(Graph.edge("room", "master"));
        edges.add(Graph.edge("hall", "master"));
        edges.add(new Graph.Edge("master", "out", "out", "audio"));
        Graph graph = new Graph(3, nodes, edges);
        // Fail loudly if a compiler change leaves B9 short of the limits it exists to probe
        LoopPlan plan = GraphCompiler.compile(graph);
        if (plan.size() != GraphCompiler.MAX_EVENTS || plan.signals().sourceCount() != SignalGraph.MAX_AUDIO_SOURCES
                || plan.signals().triggerCount() != 2)
            throw new IllegalStateException("B9 is no longer at the compiler limits: " + plan.size() + " events, "
                    + plan.signals().sourceCount() + " sources, " + plan.signals().triggerCount() + " triggers");
        return graph;
    }

    // B7: 32 sustained looped stereo voices at 4x
    private static Graph b7Graph(AssetRef stereoRef192k) {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("stack1", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("stack2", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("stack1", "mix"));
        edges.add(Graph.edge("stack2", "mix"));
        edges.add(Graph.edge("mix", "out"));

        for (int i = 0; i < 32; i++) {
            String id = "loop" + i;
            nodes.add(new Graph.Node(id, NodeType.GENERATOR_SAMPLE, loopParams(1.0 + i * 0.01, 0.8 / 32), stereoRef192k));
            edges.add(Graph.edge(id, i < 16 ? "stack1" : "stack2"));
        }
        return new Graph(2, nodes, edges);
    }

    // B7b: 7 hats at 16 per cycle beside one sustained loop
    private static Graph b7DenseGraph(AssetRef stereoRef192k, AssetRef hatRef) {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        nodes.add(new Graph.Node("mix", NodeType.STACK, Map.of()));
        nodes.add(new Graph.Node("out", NodeType.OUTPUT, Map.of()));
        edges.add(Graph.edge("mix", "out"));
        nodes.add(new Graph.Node("loop", NodeType.GENERATOR_SAMPLE, loopParams(1.0, 0.3), stereoRef192k));
        edges.add(Graph.edge("loop", "mix"));
        for (int i = 0; i < 7; i++) {
            String hat = "hat" + i, fast = "fast" + i;
            nodes.add(new Graph.Node(hat, NodeType.GENERATOR_SAMPLE, Map.of(
                    NodeParam.PITCH_RATIO, 1.0 + i * 0.1,
                    NodeParam.GAIN, 0.4 / 7), hatRef));
            nodes.add(new Graph.Node(fast, NodeType.FAST, Map.of(NodeParam.FACTOR, 16.0)));
            edges.add(Graph.edge(hat, fast));
            edges.add(Graph.edge(fast, "mix"));
        }
        return new Graph(2, nodes, edges);
    }

    private static Map<String, Double> loopParams(double pitchRatio, double gain) {
        return Map.of(
                NodeParam.PITCH_RATIO, pitchRatio,
                NodeParam.GAIN, gain,
                NodeParam.LOOP, 1.0,
                NodeParam.LOOP_START, 0.25,
                NodeParam.LOOP_END, 0.9,
                NodeParam.LOOP_FADE_MS, 20.0);
    }
}
