package groove.engine;

import groove.engine.samples.*;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Stage 3 float64 golden render regression suite (Step 1c).
 * Renders 0.5 s (24,000 frames) of 48 kHz stereo audio per scenario.
 * Gate in check: max absolute difference <= 1e-7.
 * With -Dgroove.goldenExact=true: bit-for-bit equality.
 */
public final class GoldenTests {
    private static int checks;
    private static final int FRAMES = 24000;
    private static final int SAMPLES = FRAMES * 2;

    private static final AssetRef STEREO_REF_48K = new AssetRef(
            "custom:golden_stereo_48k.wav",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    );
    private static final AssetRef STEREO_REF_192K = new AssetRef(
            "custom:golden_stereo_192k.wav",
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    );

    private static final SampleData STEREO_DATA_48K = createStereoSample(48000);
    private static final SampleData STEREO_DATA_192K = createStereoSample(192000);

    private static SampleData createStereoSample(int rate) {
        int frames = rate; // 1.0 second
        float[] pcm = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = (double) i / rate;
            double left = (Math.sin(2 * Math.PI * 440 * t) * 0.6 + Math.sin(2 * Math.PI * 880 * t) * 0.3)
                    * Math.exp(-t * 2.5);
            double right = (Math.sin(2 * Math.PI * 660 * t) * 0.6 + Math.sin(2 * Math.PI * 1320 * t) * 0.3)
                    * Math.exp(-t * 3.0);
            pcm[i * 2] = (float) left;
            pcm[i * 2 + 1] = (float) right;
        }
        return new SampleData(rate, 2, pcm);
    }

    private static Path goldenDir() {
        Path direct = Path.of("core-engine/src/test/resources/golden/stage3");
        if (Files.exists(Path.of("core-engine/src/test/resources"))) return direct;
        Path local = Path.of("src/test/resources/golden/stage3");
        if (Files.exists(Path.of("src/test/resources"))) return local;
        return direct;
    }

    private static byte[] readGoldenBytes(String name) throws Exception {
        InputStream stream = GoldenTests.class.getResourceAsStream("/golden/stage3/" + name + ".f64");
        if (stream != null) {
            try (stream) {
                return stream.readAllBytes();
            }
        }
        Path file = goldenDir().resolve(name + ".f64");
        if (Files.exists(file)) return Files.readAllBytes(file);
        return null;
    }

    public static void run() {
        try {
            runScenarios();
        } catch (Exception e) {
            throw new AssertionError("Golden render test failed", e);
        }
    }

    private static void runScenarios() throws Exception {
        boolean capture = Boolean.getBoolean("groove.captureGoldens");
        boolean exact = Boolean.getBoolean("groove.goldenExact");
        Path dir = goldenDir();

        AssetRef kickRef = FactorySamples.ref("factory:basic/kick.wav");
        AssetRef snareRef = FactorySamples.ref("factory:basic/snare.wav");
        AssetRef hatRef = FactorySamples.ref("factory:basic/hat.wav");

        Map<AssetRef, SampleData> sampleBank = Map.of(
                kickRef, WavDecoder.decode(FactorySamples.bytes("factory:basic/kick.wav")),
                snareRef, WavDecoder.decode(FactorySamples.bytes("factory:basic/snare.wav")),
                hatRef, WavDecoder.decode(FactorySamples.bytes("factory:basic/hat.wav")),
                STEREO_REF_48K, STEREO_DATA_48K,
                STEREO_REF_192K, STEREO_DATA_192K
        );

        // 1. saw tone
        Graph sawGraph = new Graph(1, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of(
                        NodeParam.WAVE, 1.0,
                        NodeParam.FREQUENCY, 220.0,
                        NodeParam.GAIN, 0.5
                )),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("tone", "out")));

        // 2. pulse tone
        Graph pulseGraph = new Graph(1, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of(
                        NodeParam.WAVE, 2.0,
                        NodeParam.PULSE_WIDTH, 0.3,
                        NodeParam.FREQUENCY, 220.0,
                        NodeParam.GAIN, 0.5
                )),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("tone", "out")));

        // 3. mono sample at 1x
        Graph mono1xGraph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 1.0,
                        NodeParam.GAIN, 0.8
                ), kickRef),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        // 4. stereo sample at 1x
        Graph stereo1xGraph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 1.0,
                        NodeParam.GAIN, 0.8
                ), STEREO_REF_48K),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        // 5. stereo sample at 15.996x (pitch 3.999 * 192k/48k = 15.996x)
        Graph stereo15996Graph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 3.999,
                        NodeParam.GAIN, 0.8
                ), STEREO_REF_192K),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        // 6. stereo sample at 16x (pitch 4.0 * 192k/48k = 16.000x)
        Graph stereo16xGraph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 4.0,
                        NodeParam.GAIN, 0.8
                ), STEREO_REF_192K),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        // 7. sample reverse
        Graph reverseGraph = new Graph(3, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 1.0,
                        NodeParam.GAIN, 0.8,
                        NodeParam.REVERSE, 1.0
                ), kickRef),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        // 8. sample slice
        Graph sliceGraph = new Graph(3, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 1.0,
                        NodeParam.GAIN, 0.8
                ), kickRef),
                new Graph.Node("slice", NodeType.SAMPLE_SLICE, Map.of(
                        NodeParam.SLICES, 4.0,
                        NodeParam.INDEX, 1.0,
                        NodeParam.REVERSE, 0.0
                )),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "slice"), Graph.edge("slice", "out")));

        // 9. synced-delay signal graph
        Graph syncedDelayGraph = SignalDemo.multipleSources();

        // 10. Phase 3 fixture: signal_delay
        Graph fixtureSignalDelay = SignalDemo.graph();

        // 11. Phase 3 fixture: sample_slices
        Graph fixtureSampleSlices = new Graph(3, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE,
                        Map.of("startFrame", 64.0, "endFrame", 1024.0, "reverse", 1.0), kickRef),
                new Graph.Node("slice", NodeType.SAMPLE_SLICE, Map.of("slices", 4.0, "index", 2.0, "reverse", 1.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("sample", "slice"), Graph.edge("slice", "out")));

        // 12. Phase 3 fixture: pattern_v2
        Graph fixturePatternV2 = FactorySamples.demo();

        Map<String, Graph> scenarios = new java.util.LinkedHashMap<>();
        scenarios.put("saw_tone", sawGraph);
        scenarios.put("pulse_tone", pulseGraph);
        scenarios.put("mono_sample_1x", mono1xGraph);
        scenarios.put("stereo_sample_1x", stereo1xGraph);
        scenarios.put("stereo_sample_15_996x", stereo15996Graph);
        scenarios.put("stereo_sample_16x", stereo16xGraph);
        scenarios.put("sample_reverse", reverseGraph);
        scenarios.put("sample_slice", sliceGraph);
        scenarios.put("synced_delay", syncedDelayGraph);
        scenarios.put("fixture_signal_delay", fixtureSignalDelay);
        scenarios.put("fixture_sample_slices", fixtureSampleSlices);
        scenarios.put("fixture_pattern_v2", fixturePatternV2);

        for (var entry : scenarios.entrySet()) {
            String name = entry.getKey();
            Graph graph = entry.getValue();

            float[] output = new float[SAMPLES];
            SessionState state = new SessionState(1, 0, 0, 120, true, graph);
            LoopPlan plan = GraphCompiler.compile(graph);
            LiveRenderer.Program program = new LiveRenderer.Program(state, plan, sampleBank);
            LiveRenderer renderer = new LiveRenderer();
            renderer.publish(new LiveRenderer.Timeline(program, null));
            renderer.render(output, FRAMES, 0L);
            float[] budgeted = new float[SAMPLES];
            LiveRenderer budgetedRenderer = new LiveRenderer(ReplayBudget.unlimited());
            budgetedRenderer.publish(new LiveRenderer.Timeline(program, null));
            budgetedRenderer.render(budgeted, FRAMES, 0L);
            check(java.util.Arrays.equals(output, budgeted), "Scenario " + name + " renders identically through the unlimited budget");

            double energy = 0;
            for (float v : output) energy += v * v;
            check(energy > 1e-4, "Scenario " + name + " output is audible and non-silent");

            byte[] existingBytes = readGoldenBytes(name);
            if (capture || existingBytes == null) {
                // Recapture reports how far the new render moved from the old golden, without gating it
                if (existingBytes != null && existingBytes.length == SAMPLES * Double.BYTES) {
                    ByteBuffer previous = ByteBuffer.wrap(existingBytes).order(ByteOrder.LITTLE_ENDIAN);
                    double moved = 0;
                    for (int i = 0; i < SAMPLES; i++) moved = Math.max(moved, Math.abs(output[i] - previous.getDouble()));
                    System.out.printf("Recaptured golden %s: max abs change %.3e%n", name, moved);
                }
                Files.createDirectories(dir);
                Path target = dir.resolve(name + ".f64");
                ByteBuffer buf = ByteBuffer.allocate(SAMPLES * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (float v : output) buf.putDouble(v);
                Files.write(target, buf.array());
                existingBytes = buf.array();
            }

            ByteBuffer buf = ByteBuffer.wrap(existingBytes).order(ByteOrder.LITTLE_ENDIAN);
            check(existingBytes.length == SAMPLES * Double.BYTES, "Golden file " + name + ".f64 has expected size");

            double maxDiff = 0;
            for (int i = 0; i < SAMPLES; i++) {
                double actual = output[i];
                double golden = buf.getDouble();
                if (exact) {
                    if (Double.doubleToRawLongBits(actual) != Double.doubleToRawLongBits(golden)) {
                        throw new AssertionError(String.format(
                                "Scenario %s bit-for-bit mismatch at sample %d: actual=%s, golden=%s",
                                name, i, actual, golden));
                    }
                } else {
                    double diff = Math.abs(actual - golden);
                    maxDiff = Math.max(maxDiff, diff);
                    if (diff > 1e-7) {
                        throw new AssertionError(String.format(
                                "Scenario %s max absolute diff exceeded at sample %d: %e > 1e-7 (actual=%s, golden=%s)",
                                name, i, diff, actual, golden));
                    }
                }
            }
            checks++;
        }
        System.out.printf("Stage 3 golden render regressions passed (12 scenarios, %d checks, max abs diff <= 1e-7).%n", checks);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
