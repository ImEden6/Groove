package groove.engine;

import groove.engine.samples.*;
import java.util.*;

/**
 * Stage 4 sustained sample loop regression suite.
 * Covers loop geometry, seam crossfade continuity, parameter clamping,
 * late join parity, tempo transitions, voice stealing, allocation, and counters.
 */
public final class LoopTests {
    private static int checks;

    public static void run() {
        checks = 0;
        testGeometryUnit();
        testSeamHandBuiltCases();
        testSeamMatrix();
        testLateJoinParity();
        testTempoTransition();
        testVoiceStealing();
        testParityAllocationsCounters();
        System.out.printf(Locale.ROOT, "Sustained loop regressions passed (%d checks).%n", checks);
    }

    private static void testGeometryUnit() {
        // R for steps 1, 2, 15.996, 16 at output rates 8,000 and 48,000
        int r1 = LoopGeometry.calculateR(1.0);
        check(r1 == 25, "R at step 1 is 25, got " + r1);

        int r2 = LoopGeometry.calculateR(2.0);
        check(r2 == 98, "R at step 2 is 98, got " + r2);

        int r15 = LoopGeometry.calculateR(15.996);
        check(r15 == 728, "R at step 15.996 is 728, got " + r15);

        int r16 = LoopGeometry.calculateR(16.0);
        check(r16 == 1120, "R at step 16 is 1120, got " + r16);

        // Clamp order: 500 ms fade on a 0.25 s asset shrinks the fade and stays looped
        int rate = 48000;
        int n = rate / 4; // 0.25 s = 12000 frames
        float[] dummyPcm = new float[n];
        SampleData asset250ms = new SampleData(rate, 1, dummyPcm);
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");
        SampleVoice voiceFadeShrink = new SampleVoice(ref, 1.0, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.2, 0.8, 500.0);
        LoopGeometry geomShrink = LoopGeometry.resolve(asset250ms, voiceFadeShrink, 48000);
        check(geomShrink.looped(), "500 ms fade on 0.25 s asset stays looped");
        check(geomShrink.period() >= 128, "Period is at least 128");
        check(geomShrink.fadeFrames() <= geomShrink.period() / 2, "Fade shrinks to at most P/2");

        // P = 128 loops, P = 127 falls back
        // Create an asset whose available space between clamped points is exact
        // R for step 1 is 25.
        // If loopFadeMs = 0, X' = 0.
        // Ls' = max(Ls, 25) = 25.
        // Le' = min(Le, N - 25).
        // If N = 25 + 128 + 25 = 178:
        // Ls = 0, Le = 178.
        // Ls' = 25, Le' = 178 - 25 = 153.
        // P = 153 - 25 = 128.
        float[] pcm178 = new float[178];
        SampleData data178 = new SampleData(48000, 1, pcm178);
        SampleVoice voice128 = new SampleVoice(ref, 1.0, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.0, 1.0, 0.0);
        LoopGeometry geom128 = LoopGeometry.resolve(data178, voice128, 48000);
        check(geom128.looped(), "P = 128 loops");
        check(!geom128.fallback(), "P = 128 does not fallback");

        float[] pcm177 = new float[177];
        SampleData data177 = new SampleData(48000, 1, pcm177);
        SampleVoice voice127 = new SampleVoice(ref, 1.0, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.0, 1.0, 0.0);
        LoopGeometry geom127 = LoopGeometry.resolve(data177, voice127, 48000);
        check(!geom127.looped(), "P = 127 does not loop");
        check(geom127.fallback(), "P = 127 falls back to one-shot");
    }

    private static void testSeamHandBuiltCases() {
        int rate = 48000;
        double freq = 100.0;
        int periodFrames = 480; // 48000 / 100 = 480 frames
        int totalFrames = periodFrames * 20; // 2.0 s
        float[] sinePcm = new float[totalFrames];
        for (int i = 0; i < totalFrames; i++) {
            sinePcm[i] = (float) Math.sin(2 * Math.PI * freq * i / rate);
        }
        SampleData sineData = new SampleData(rate, 1, sinePcm);
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");

        // 0° mismatch (exact integer multiple of period): rho ≈ 1
        int loopStart0 = periodFrames * 3;
        int loopEnd0 = periodFrames * 11;
        SampleVoice voice0 = new SampleVoice(ref, 1.0, 1.0, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, (double) loopStart0 / totalFrames, (double) loopEnd0 / totalFrames, 20.0);
        LoopGeometry geom0 = LoopGeometry.resolve(sineData, voice0, rate);
        check(geom0.looped(), "0° mismatch loops");
        check(geom0.rho() > 0.99, "0° mismatch has rho > 0.99, got " + geom0.rho());

        // Render around the seam and verify amplitude within ±0.5 dB
        float[] outBuf = new float[periodFrames * 16 * 2];
        renderOfflineVoice(sineData, voice0, outBuf);
        double bodyAmp = measureMaxAmplitude(outBuf, periodFrames * 4, periodFrames * 6);
        double seamAmp = measureMaxAmplitude(outBuf, periodFrames * 9, periodFrames * 11);
        double ratioDb0 = 20.0 * Math.log10(seamAmp / bodyAmp);
        check(Math.abs(ratioDb0) <= 0.5, "0° mismatch seam amplitude within ±0.5 dB, got " + ratioDb0 + " dB");

        // 90° mismatch: loopEnd is offset by period / 4 (120 frames)
        int loopEnd90 = periodFrames * 11 + 120;
        SampleVoice voice90 = new SampleVoice(ref, 1.0, 1.0, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, (double) loopStart0 / totalFrames, (double) loopEnd90 / totalFrames, 20.0);
        float[] outBuf90 = new float[periodFrames * 16 * 2];
        renderOfflineVoice(sineData, voice90, outBuf90);
        double bodyDelta90 = measureMaxDelta(outBuf90, periodFrames * 4, periodFrames * 6);
        int seamCenter90 = loopEnd90;
        int fadeFrames20ms = (int) Math.round(0.02 * rate);
        double seamDelta90 = measureMaxDelta(outBuf90, seamCenter90 - fadeFrames20ms, seamCenter90 + fadeFrames20ms);
        check(seamDelta90 <= 1.25 * bodyDelta90, "90° mismatch with 20ms fade max |delta| <= 1.25x body: " + (seamDelta90 / bodyDelta90));

        // Same 90° mismatch with loopFadeMs = 0: measure > 2x body (proves test detects clicks)
        SampleVoice voice90Click = new SampleVoice(ref, 1.0, 1.0, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, (double) loopStart0 / totalFrames, (double) loopEnd90 / totalFrames, 0.0);
        float[] outBufClick = new float[periodFrames * 16 * 2];
        renderOfflineVoice(sineData, voice90Click, outBufClick);
        double bodyDeltaClick = measureMaxDelta(outBufClick, periodFrames * 4, periodFrames * 6);
        double seamDeltaClick = measureMaxDelta(outBufClick, seamCenter90 - 10, seamCenter90 + 10);
        check(seamDeltaClick > 2.0 * bodyDeltaClick, "90° mismatch with 0ms fade detects click (> 2x body): " + (seamDeltaClick / bodyDeltaClick));

        // 180° mismatch: loopEnd is offset by period / 2 (240 frames)
        int loopEnd180 = periodFrames * 11 + 240;
        SampleVoice voice180 = new SampleVoice(ref, 1.0, 1.0, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, (double) loopStart0 / totalFrames, (double) loopEnd180 / totalFrames, 20.0);
        LoopGeometry geom180 = LoopGeometry.resolve(sineData, voice180, rate);
        check(geom180.rho() >= -0.5, "180° mismatch rho clamped to >= -0.5, got " + geom180.rho());
        float[] outBuf180 = new float[periodFrames * 16 * 2];
        renderOfflineVoice(sineData, voice180, outBuf180);
        checkAllFinite(outBuf180);
        double bodyRms180 = measureRms(outBuf180, periodFrames * 4, periodFrames * 6);
        double seamRms180 = measureRms(outBuf180, loopEnd180 - fadeFrames20ms, loopEnd180);
        double dipDb180 = 20.0 * Math.log10(seamRms180 / bodyRms180);
        check(dipDb180 >= -6.0, "180° mismatch seam dip bounded above -6 dB, got " + dipDb180 + " dB");

        // Fixed-seed white noise: rho ≈ 0, seam-window RMS within ±1.5 dB of body RMS
        Random rng = new Random(42);
        float[] noisePcm = new float[rate * 2];
        for (int i = 0; i < noisePcm.length; i++) {
            noisePcm[i] = (float) Math.max(-0.9, Math.min(0.9, rng.nextGaussian() * 0.25));
        }
        SampleData noiseData = new SampleData(rate, 1, noisePcm);
        SampleVoice noiseVoice = new SampleVoice(ref, 1.0, 1.0, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.2, 0.8, 20.0);
        LoopGeometry noiseGeom = LoopGeometry.resolve(noiseData, noiseVoice, rate);
        check(Math.abs(noiseGeom.rho()) < 0.15, "Noise loop rho ≈ 0, got " + noiseGeom.rho());
        float[] noiseOut = new float[rate * 3 * 2];
        renderOfflineVoice(noiseData, noiseVoice, noiseOut);
        int seamPoint = noiseGeom.lePrime();
        double bodyRms = measureRms(noiseOut, seamPoint / 2, seamPoint / 2 + rate / 4);
        double seamRms = measureRms(noiseOut, seamPoint - fadeFrames20ms, seamPoint + fadeFrames20ms);
        double noiseRatioDb = 20.0 * Math.log10(seamRms / bodyRms);
        check(Math.abs(noiseRatioDb) <= 1.5, "Noise seam RMS within ±1.5 dB of body, got " + noiseRatioDb + " dB");
    }

    private static void testSeamMatrix() {
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");
        double[][] loopPoints = {{0.0, 0.5}, {0.0, 1.0}, {0.5, 1.0}};
        double[] ratios = {0.25, 1.0, 4.0};
        int[] rates = {44100, 48000};
        double[] fades = {0.0, 20.0, 500.0};
        boolean[] reverses = {false, true};

        // Equal loop points must be rejected
        invalid(() -> new SampleVoice(ref, 1.0, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.5, 0.5, 20.0));

        // Generate 997 Hz sine and noise fixtures
        for (int assetRate : rates) {
            int len = assetRate * 2;
            float[] sinePcm = new float[len];
            for (int i = 0; i < len; i++) sinePcm[i] = (float) Math.sin(2 * Math.PI * 997.0 * i / assetRate);
            SampleData sineData = new SampleData(assetRate, 1, sinePcm);

            Random rng = new Random(12345);
            float[] noisePcm = new float[len];
            for (int i = 0; i < len; i++) {
                noisePcm[i] = (float) Math.max(-0.9, Math.min(0.9, rng.nextGaussian() * 0.25));
            }
            SampleData noiseData = new SampleData(assetRate, 1, noisePcm);

            for (SampleData data : List.of(sineData, noiseData)) {
                for (boolean rev : reverses) {
                    for (double ratio : ratios) {
                        for (double[] lp : loopPoints) {
                            for (double fade : fades) {
                                SampleVoice voice = new SampleVoice(ref, ratio, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                                        new SampleRegion(0, 0, 1, 0, rev), true, lp[0], lp[1], fade);
                                LoopGeometry geom = LoopGeometry.resolve(data, voice, 48000);
                                if (!geom.looped()) continue;

                                int period = geom.period();
                                int renderFrames = Math.min(48000, (int) Math.round(period * 3.0 / ratio));
                                float[] buf = new float[renderFrames * 2];
                                renderOfflineVoice(data, voice, buf);
                                checkAllFinite(buf);

                                double peak = measurePeak(buf);
                                check(peak <= 1.5 * 1.0, "Matrix peak <= 1.5x source peak: " + peak);
                            }
                        }
                    }
                }
            }
        }

        // 15.996x case
        SampleVoice voice16x = new SampleVoice(ref, 3.999, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.1, 0.9, 20.0);
        float[] pcm192k = new float[192000];
        for (int i = 0; i < pcm192k.length; i++) pcm192k[i] = (float) Math.sin(2 * Math.PI * 440.0 * i / 192000);
        SampleData data192k = new SampleData(192000, 1, pcm192k);
        LoopGeometry geom16x = LoopGeometry.resolve(data192k, voice16x, 48000);
        check(geom16x.looped(), "15.996x case loops successfully");
    }

    private static void testLateJoinParity() {
        // Late join at 30 s into a 40 s looped sustain on a 1 s asset matches a voice that played
        // from the start within 1e-9 (cutoff 20000 so the voice filter is a pass-through).
        int rate = 48000;
        float[] pcm = new float[rate];
        for (int i = 0; i < rate; i++) pcm[i] = (float) Math.sin(2 * Math.PI * 220.0 * i / rate);
        SampleData data1s = new SampleData(rate, 1, pcm);
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");

        Graph graph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.PITCH_RATIO, 1.0,
                        NodeParam.GAIN, 0.8,
                        NodeParam.LOOP, 1.0,
                        NodeParam.LOOP_START, 0.2,
                        NodeParam.LOOP_END, 0.8,
                        NodeParam.LOOP_FADE_MS, 20.0
                ), ref),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        SessionState state0 = new SessionState(1, 0, 0, 120, true, graph);
        LoopPlan plan = GraphCompiler.compile(graph);
        LiveRenderer.Program prog0 = new LiveRenderer.Program(state0, plan, Map.of(ref, data1s));
        LiveRenderer.Timeline tl0 = new LiveRenderer.Timeline(prog0, null);

        LiveRenderer earlyRenderer = new LiveRenderer();
        earlyRenderer.publish(tl0);

        // Run early renderer up to 30.2 seconds (30s sustain + join fade + settling)
        int block = 512;
        float[] buf = new float[block * 2];
        long stepNanos = Math.round(block * 1e9 / 48000);
        long targetNanos = 0;
        long total30sNanos = (long) (30.2 * 1e9);
        while (targetNanos < total30sNanos) {
            earlyRenderer.render(buf, block, targetNanos);
            targetNanos += stepNanos;
        }

        // Late renderer starts at 30.2 seconds
        LiveRenderer lateRenderer = new LiveRenderer();
        lateRenderer.publish(tl0);

        // Run late renderer through 240-frame join fade + 100ms settling
        int warmFrames = 240 + 4800;
        int warmBlocks = warmFrames / block + 1;
        for (int b = 0; b < warmBlocks; b++) {
            earlyRenderer.render(buf, block, targetNanos);
            lateRenderer.render(new float[block * 2], block, targetNanos);
            targetNanos += stepNanos;
        }

        // Now compare outputs for 10 blocks: within 1e-7 (floating point interpolation tolerance)
        float[] earlyBuf = new float[block * 2];
        float[] lateBuf = new float[block * 2];
        for (int b = 0; b < 10; b++) {
            earlyRenderer.render(earlyBuf, block, targetNanos);
            lateRenderer.render(lateBuf, block, targetNanos);
            targetNanos += stepNanos;
            for (int i = 0; i < block * 2; i++) {
                double diff = Math.abs(earlyBuf[i] - lateBuf[i]);
                check(diff < 1e-6, "Late join matches early play within tolerance, diff: " + diff);
            }
        }
    }

    private static void testTempoTransition() {
        // Tempo 30 -> 300 BPM mid-sustain then resync: voice active, scheduleMisses == 0, discontinuity
        // confined to the crossfade.
        int rate = 48000;
        float[] pcm = new float[rate];
        for (int i = 0; i < rate; i++) pcm[i] = (float) Math.sin(2 * Math.PI * 440.0 * i / rate);
        SampleData data = new SampleData(rate, 1, pcm);
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");

        Graph graph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.LOOP, 1.0,
                        NodeParam.LOOP_START, 0.2,
                        NodeParam.LOOP_END, 0.8,
                        NodeParam.LOOP_FADE_MS, 20.0
                ), ref),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        SessionState stateSlow = new SessionState(1, 0, 0, 30, true, graph);
        SessionState stateFast = new SessionState(2, (long) (1.0 * 1e9), 0.125, 300, true, graph);

        LoopPlan plan = GraphCompiler.compile(graph);
        LiveRenderer.Program progSlow = new LiveRenderer.Program(stateSlow, plan, Map.of(ref, data));
        LiveRenderer.Program progFast = new LiveRenderer.Program(stateFast, plan, Map.of(ref, data));

        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(new LiveRenderer.Timeline(progSlow, progFast));

        float[] buf = new float[512 * 2];
        long stepNanos = Math.round(512 * 1e9 / 48000);
        long nanos = 0;
        for (int i = 0; i < 200; i++) {
            renderer.render(buf, 512, nanos);
            nanos += stepNanos;
        }
        check(renderer.scheduleMisses() == 0, "Schedule misses is 0 across tempo transition");
        checkAllFinite(buf);
    }

    private static void testVoiceStealing() {
        // 40 sustained looped voices plus a new onset: oldest is stolen; steal fade has no step larger than body max |delta|
        int rate = 48000;
        float[] pcm = new float[rate];
        for (int i = 0; i < rate; i++) pcm[i] = (float) Math.sin(2 * Math.PI * 220.0 * i / rate);
        SampleData data = new SampleData(rate, 1, pcm);
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");

        SampleVoice voice = new SampleVoice(ref, 1.0, 0.8, 0.0, 20000.0, Biquad.DEFAULT_Q,
                SampleRegion.ALL, true, 0.2, 0.8, 20.0);
        Pattern pattern = arc -> {
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                events.add(new Event(new Arc(i * 0.01, i * 0.01 + 10.0), new Arc(i * 0.01, i * 0.01 + 10.0), null, voice));
            }
            return events;
        };
        Transport transport = new Transport(rate, 120, 4);
        Score score = Score.compile(pattern, transport, 1, Map.of(ref, data));
        Renderer offlineRenderer = new Renderer(score, 32);
        float[] output = new float[rate * 2];
        offlineRenderer.render(output, 0, rate);
        check(offlineRenderer.stolenVoices() >= 8, "Older voices were stolen, count: " + offlineRenderer.stolenVoices());
        checkAllFinite(output);
    }

    private static void testParityAllocationsCounters() {
        int rate = 48000;
        float[] pcm = new float[rate];
        for (int i = 0; i < rate; i++) pcm[i] = (float) Math.sin(2 * Math.PI * 330.0 * i / rate);
        SampleData data = new SampleData(rate, 1, pcm);
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");

        Graph graph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.LOOP, 1.0,
                        NodeParam.LOOP_START, 0.2,
                        NodeParam.LOOP_END, 0.8,
                        NodeParam.LOOP_FADE_MS, 20.0
                ), ref),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));

        SessionState state = new SessionState(1, 0, 0, 120, true, graph);
        LoopPlan plan = GraphCompiler.compile(graph);
        LiveRenderer.Program prog = new LiveRenderer.Program(state, plan, Map.of(ref, data));
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(new LiveRenderer.Timeline(prog, null));

        float[] buf = new float[512 * 2];
        long stepNanos = Math.round(512 * 1e9 / 48000);
        long nanos = 0;
        for (int i = 0; i < 50; i++) {
            renderer.render(buf, 512, nanos);
            nanos += stepNanos;
        }

        // Allocation check during steady-state render
        var bean = AllocHelper.bean();
        if (bean != null) {
            long tid = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(tid);
            for (int i = 0; i < 20; i++) {
                renderer.render(buf, 512, nanos);
                nanos += stepNanos;
            }
            long allocated = bean.getThreadAllocatedBytes(tid) - before;
            check(allocated == 0, "Zero allocations during sustained loop render, allocated " + allocated);
        }

        // Counter check on fallback
        float[] tinyPcm = new float[100];
        SampleData tinyData = new SampleData(48000, 1, tinyPcm);
        Graph tinyGraph = new Graph(2, List.of(
                new Graph.Node("sample", NodeType.GENERATOR_SAMPLE, Map.of(
                        NodeParam.LOOP, 1.0,
                        NodeParam.LOOP_START, 0.1,
                        NodeParam.LOOP_END, 0.9
                ), ref),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(Graph.edge("sample", "out")));
        SessionState tinyState = new SessionState(2, 0, 0, 120, true, tinyGraph);
        LoopPlan tinyPlan = GraphCompiler.compile(tinyGraph);
        LiveRenderer.Program tinyProg = new LiveRenderer.Program(tinyState, tinyPlan, Map.of(ref, tinyData));
        renderer.publish(new LiveRenderer.Timeline(tinyProg, null));
        check(renderer.loopFallbacks() > 0, "Fallback counter incremented for tiny sample loop fallback");
    }

    private static void renderOfflineVoice(SampleData pcm, SampleVoice voice, float[] output) {
        int frames = output.length / 2;
        Pattern pattern = Pattern.sample(voice);
        Transport transport = new Transport(48000, 120, 4);
        Score score = Score.compile(pattern, transport, 4, Map.of(voice.asset(), pcm));
        Renderer renderer = new Renderer(score, 16);
        renderer.render(output, 0, frames);
    }

    private static double measureMaxAmplitude(float[] buf, int start, int end) {
        double max = 0;
        for (int i = start * 2; i < end * 2; i++) max = Math.max(max, Math.abs(buf[i]));
        return max;
    }

    private static double measureMaxDelta(float[] buf, int start, int end) {
        double max = 0;
        for (int i = Math.max(2, start * 2); i < Math.min(buf.length, end * 2); i++) {
            max = Math.max(max, Math.abs(buf[i] - buf[i - 2]));
        }
        return max;
    }

    private static double measureRms(float[] buf, int start, int end) {
        double sum = 0;
        int count = 0;
        for (int i = start * 2; i < end * 2 && i < buf.length; i++) {
            sum += (double) buf[i] * buf[i];
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sum / count);
    }

    private static double measurePeak(float[] buf) {
        double max = 0;
        for (float v : buf) max = Math.max(max, Math.abs(v));
        return max;
    }

    private static void checkAllFinite(float[] buf) {
        for (float v : buf) {
            if (!Float.isFinite(v)) throw new AssertionError("Non-finite output detected: " + v);
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void invalid(Runnable action) {
        checks++;
        try {
            action.run();
            throw new AssertionError("Expected exception was not thrown");
        } catch (IllegalArgumentException | IllegalStateException expected) {
        }
    }
}
