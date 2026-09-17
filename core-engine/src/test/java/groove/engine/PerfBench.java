package groove.engine;

import groove.engine.samples.*;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Stage 4 baseline performance benchmark harness.
 * Measures scenarios B1..B7 with warmup convergence, pooled block timings,
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
                new Scenario("B7b", "112 hat one-shots per cycle beside one loop", b7DenseGraph(STEREO_REF_192K, hatRef), sampleBank));

        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Running scenarios B1..B7...");
        System.out.println("--------------------------------------------------------------------------------");

        List<Result> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            Result result = runScenario(scenario);
            results.add(result);
            printResult(result);
        }

        printSummaryTable(results);
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
            if (n >= 3) {
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
            "'{0:X}' -f $mask");

    /** Hybrid CPUs move busy threads onto efficiency cores after a few seconds, which halves throughput mid-run. */
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
                if (p.waitFor() != 0 || !output.matches("[0-9A-F]+") || output.equals("0"))
                    return "not pinned (" + output.replaceAll("\\s+", " ") + ")";
                return "0x" + output + (override.isEmpty() ? " (performance cores)" : " (perf.affinity)");
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
