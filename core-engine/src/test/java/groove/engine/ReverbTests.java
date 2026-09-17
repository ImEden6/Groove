package groove.engine;

import java.util.*;

final class ReverbTests {
    private static int checks;

    static void run() {
        long start = System.nanoTime();
        paramBounds();
        graphLimit();
        loopGainValidation();
        resetMidTail();
        preDelayTiming();
        zeroAllocation();
        noiseSoak(2);
        nanInfRecovery();
        headroom();
        blockIndependence();
        stereoWidth();
        t60Decay(List.of(1.0, 2.5));
        peakGainAndModulatedStability(List.of(0.1, 0.5, 1.0, 2.5));
        feedbackConvergence(2.5);
        nodeTypeExhaustiveness();
        liveParity();
        lateJoin();
        System.out.printf("Reverb tests passed %d checks in %.2f ms.%n", checks, (System.nanoTime() - start) / 1e6);
    }

    private static void paramBounds() {
        // Valid bounds: decaySeconds [0.1, 20.0], dampingHz [200.0, 20000.0], bandwidthHz [200.0, 20000.0], preDelayMs [0.0, 500.0]
        checkCompiles(reverbGraph(Map.of(NodeParam.DECAY_SECONDS, 0.1)));
        checkCompiles(reverbGraph(Map.of(NodeParam.DECAY_SECONDS, 20.0)));
        checkCompiles(reverbGraph(Map.of(NodeParam.DAMPING_HZ, 200.0)));
        checkCompiles(reverbGraph(Map.of(NodeParam.DAMPING_HZ, 20000.0)));
        checkCompiles(reverbGraph(Map.of(NodeParam.BANDWIDTH_HZ, 200.0)));
        checkCompiles(reverbGraph(Map.of(NodeParam.BANDWIDTH_HZ, 20000.0)));
        checkCompiles(reverbGraph(Map.of(NodeParam.PRE_DELAY_MS, 0.0)));
        checkCompiles(reverbGraph(Map.of(NodeParam.PRE_DELAY_MS, 500.0)));

        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.DECAY_SECONDS, 0.09))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.DECAY_SECONDS, 20.1))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.DAMPING_HZ, 199.0))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.DAMPING_HZ, 20001.0))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.BANDWIDTH_HZ, 199.0))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.BANDWIDTH_HZ, 20001.0))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.PRE_DELAY_MS, -1.0))));
        invalid(() -> GraphCompiler.compile(reverbGraph(Map.of(NodeParam.PRE_DELAY_MS, 501.0))));
    }

    private static void graphLimit() {
        // 2 reverbs accepted, 3 reverbs rejected
        Graph twoReverbs = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("rev1", NodeType.REVERB, Map.of()),
                new Graph.Node("rev2", NodeType.REVERB, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "rev1"),
                Graph.edge("rev1", "rev2"),
                new Graph.Edge("rev2", "out", "out", "audio")
        ));
        checkCompiles(twoReverbs);

        Graph threeReverbs = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("rev1", NodeType.REVERB, Map.of()),
                new Graph.Node("rev2", NodeType.REVERB, Map.of()),
                new Graph.Node("rev3", NodeType.REVERB, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "rev1"),
                Graph.edge("rev1", "rev2"),
                Graph.edge("rev2", "rev3"),
                new Graph.Edge("rev3", "out", "out", "audio")
        ));
        try {
            GraphCompiler.compile(threeReverbs);
            throw new AssertionError("Expected 3 reverbs to be rejected");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("At most 2 reverbs per graph"), "Reverb limit error message: " + e.getMessage());
        }
    }

    private static void loopGainValidation() {
        // 1. Rejected: mix_bus(delay, reverb(delay)) -> delay at gain 1
        Graph rejectedDryWet = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 1000.0)),
                new Graph.Node("rev", NodeType.REVERB, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "bus"),
                Graph.edge("delay", "rev"),
                Graph.edge("delay", "bus"),
                Graph.edge("rev", "bus"),
                Graph.edge("bus", "delay"),
                new Graph.Edge("bus", "out", "out", "audio")
        ));
        try {
            GraphCompiler.compile(rejectedDryWet);
            throw new AssertionError("Expected dry+wet reverb feedback loop to be rejected");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().startsWith("Feedback loop through reverb can exceed unity gain"),
                    "Expected loop gain error but got: " + e.getMessage());
            check(e.getMessage().contains("bound 1.95"), "Expected bound 1.95 in message: " + e.getMessage());
        }

        // 2. Rejected: Q = 20 low-pass plus reverb in one delay loop
        Graph rejectedResonant = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 1000.0)),
                new Graph.Node("filter", NodeType.FILTER, Map.of(NodeParam.MODE, 0.0, NodeParam.CUTOFF_HZ, 1000.0, NodeParam.RESONANCE_Q, 20.0)),
                new Graph.Node("rev", NodeType.REVERB, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "bus"),
                Graph.edge("delay", "filter"),
                Graph.edge("filter", "rev"),
                Graph.edge("rev", "bus"),
                Graph.edge("bus", "delay"),
                new Graph.Edge("bus", "out", "out", "audio")
        ));
        try {
            GraphCompiler.compile(rejectedResonant);
            throw new AssertionError("Expected resonant filter + reverb feedback loop to be rejected");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().startsWith("Feedback loop through reverb can exceed unity gain"),
                    "Expected loop gain error but got: " + e.getMessage());
        }

        // 3. Accepted: delay -> reverb -> mix_bus(gain 0.5) -> delay
        Graph acceptedAttenuated = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 1000.0)),
                new Graph.Node("rev", NodeType.REVERB, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "bus"),
                Graph.edge("delay", "rev"),
                Graph.edge("rev", "bus"),
                Graph.edge("bus", "delay"),
                new Graph.Edge("bus", "out", "out", "audio")
        ));
        checkCompiles(acceptedAttenuated);

        // 4. Existing unity-gain delay feedback without reverb still compiles
        Graph unityDelayLoop = new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, 1000.0)),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "bus"),
                Graph.edge("delay", "bus"),
                Graph.edge("bus", "delay"),
                new Graph.Edge("bus", "out", "out", "audio")
        ));
        checkCompiles(unityDelayLoop);
    }

    private static void resetMidTail() {
        Reverb rev = new Reverb();
        rev.setParams(1.8, 6000.0, 12000.0, 0.0);
        double[] out = new double[2];
        // First tap lands about 3,000 frames in
        rev.process(1.0, 0, out);
        for (int i = 1; i < 10000; i++) rev.process(0.0, i, out);
        check(Math.abs(out[0]) > 1e-12 || Math.abs(out[1]) > 1e-12, "Reverb tail active before reset");

        rev.reset();
        // Next 1 s (48000 frames) with zero input must be strictly zero
        for (int i = 0; i < 48000; i++) {
            rev.process(0.0, i, out);
            check(out[0] == 0.0 && out[1] == 0.0, "Output after reset must be exactly zero at frame " + i);
        }
    }

    private static void preDelayTiming() {
        Reverb rev0 = new Reverb();
        rev0.setParams(1.8, 20000.0, 20000.0, 0.0);
        double[] out0 = new double[2];
        int onset0 = -1;
        for (int i = 0; i < 2000; i++) {
            rev0.process(i == 0 ? 1.0 : 0.0, i, out0);
            if (onset0 == -1 && (Math.abs(out0[0]) > 1e-12 || Math.abs(out0[1]) > 1e-12)) {
                onset0 = i;
                break;
            }
        }
        check(onset0 >= 0, "Reverb must produce output for preDelay 0");

        Reverb rev500 = new Reverb();
        rev500.setParams(1.8, 20000.0, 20000.0, 500.0);
        double[] out500 = new double[2];
        int onset500 = -1;
        for (int i = 0; i < 24000 + onset0 + 10; i++) {
            rev500.process(i == 0 ? 1.0 : 0.0, i, out500);
            if (onset500 == -1 && (Math.abs(out500[0]) > 1e-12 || Math.abs(out500[1]) > 1e-12)) {
                onset500 = i;
                break;
            }
        }
        check(onset500 == onset0 + 24000, "Pre-delay 500ms must delay onset by exactly 24,000 frames: onset0=" + onset0 + " onset500=" + onset500);
    }

    private static void zeroAllocation() {
        var counter = AllocHelper.bean();
        if (counter == null) return;
        Reverb rev = new Reverb();
        double[] out = new double[2];
        for (int i = 0; i < 10000; i++) rev.process(0.5, i, out);
        long id = Thread.currentThread().threadId();
        long before = counter.getThreadAllocatedBytes(id);
        for (int i = 0; i < 20000; i++) rev.process(0.5, i, out);
        long bytes = counter.getThreadAllocatedBytes(id) - before;
        check(bytes == 0, "Reverb.process allocated " + bytes + " bytes");

        before = counter.getThreadAllocatedBytes(id);
        rev.reset();
        bytes = counter.getThreadAllocatedBytes(id) - before;
        check(bytes == 0, "Reverb.reset allocated " + bytes + " bytes");
    }

    static void noiseSoak(int seconds) {
        Reverb rev = new Reverb();
        rev.setParams(2.5, 6000.0, 12000.0, 50.0);
        double[] out = new double[2];
        Random rng = new Random(42);
        for (int i = 0; i < seconds * 48000; i++) {
            double noise = rng.nextDouble() * 2.0 - 1.0;
            rev.process(noise, i, out);
            check(Double.isFinite(out[0]) && Double.isFinite(out[1]), "Noise output must be finite");
            check(Math.abs(out[0]) <= 8.0 && Math.abs(out[1]) <= 8.0, "Noise output in bounds");
        }
    }

    private static void nanInfRecovery() {
        Reverb rev = new Reverb();
        rev.setParams(1.0, 6000.0, 12000.0, 0.0);
        double[] out = new double[2];
        rev.process(Double.NaN, 0, out);
        check(Double.isFinite(out[0]) && Double.isFinite(out[1]), "NaN input handled");
        rev.process(Double.POSITIVE_INFINITY, 1, out);
        check(Double.isFinite(out[0]) && Double.isFinite(out[1]), "+Inf input handled");
        rev.process(Double.NEGATIVE_INFINITY, 2, out);
        check(Double.isFinite(out[0]) && Double.isFinite(out[1]), "-Inf input handled");

        // Run normal input and verify recovery
        for (int i = 3; i < 3 * 48000; i++) {
            rev.process(0.0, i, out);
            check(Double.isFinite(out[0]) && Double.isFinite(out[1]), "Finite output during recovery");
        }
        check(Math.abs(out[0]) < 1e-6 && Math.abs(out[1]) < 1e-6, "Recovered to silence after tail");
    }

    private static void headroom() {
        // Full-scale sine at lowest tank resonance (~1.38 Hz or 200 Hz), decaySeconds = 2.5
        Reverb rev = new Reverb();
        rev.setParams(2.5, 20000.0, 20000.0, 0.0);
        double[] out = new double[2];
        double omega = 2.0 * Math.PI * 200.0 / 48000.0;
        double maxOut = 0.0;
        for (int i = 0; i < 96000; i++) {
            rev.process(Math.sin(omega * i), i, out);
            maxOut = Math.max(maxOut, Math.max(Math.abs(out[0]), Math.abs(out[1])));
        }
        check(maxOut < 8.0, "Steady state output bounded away from guard");
    }

    private static void blockIndependence() {
        // Chunk sizes 1, 64, 512 give identical output
        int totalFrames = 4096;
        double[] refL = new double[totalFrames], refR = new double[totalFrames];
        Reverb rev1 = new Reverb();
        rev1.setParams(1.5, 5000.0, 10000.0, 20.0);
        double[] out = new double[2];
        for (int i = 0; i < totalFrames; i++) {
            double in = Math.sin(0.05 * i);
            rev1.process(in, i, out);
            refL[i] = out[0]; refR[i] = out[1];
        }

        for (int chunkSize : List.of(64, 512)) {
            Reverb revChunk = new Reverb();
            revChunk.setParams(1.5, 5000.0, 10000.0, 20.0);
            for (int block = 0; block < totalFrames / chunkSize; block++) {
                for (int f = 0; f < chunkSize; f++) {
                    int i = block * chunkSize + f;
                    double in = Math.sin(0.05 * i);
                    revChunk.process(in, i, out);
                    check(Math.abs(out[0] - refL[i]) < 1e-12 && Math.abs(out[1] - refR[i]) < 1e-12,
                            "Chunk size " + chunkSize + " mismatch at frame " + i);
                }
            }
        }
    }

    private static void stereoWidth() {
        // Zero-lag |rho| between L and R of the IR from 50 ms to T60 at decaySeconds = 2.5, < 0.5 with mod on and off
        for (boolean mod : List.of(false, true)) {
            Reverb rev = new Reverb();
            rev.setParams(2.5, 20000.0, 20000.0, 0.0);
            rev.setModulation(mod);
            int startFrame = (int) (0.050 * 48000); // 50 ms
            int endFrame = (int) (2.5 * 48000); // T60
            double sumLR = 0.0, sumLL = 0.0, sumRR = 0.0;
            double[] out = new double[2];
            for (int i = 0; i < endFrame; i++) {
                double in = i == 0 ? 1.0 : 0.0;
                rev.process(in, i, out);
                if (i >= startFrame) {
                    sumLR += out[0] * out[1];
                    sumLL += out[0] * out[0];
                    sumRR += out[1] * out[1];
                }
            }
            double rho = sumLR / Math.sqrt(sumLL * sumRR);
            check(Math.abs(rho) < 0.5, "Stereo width |rho| < 0.5 with mod=" + mod + ": " + rho);
        }
    }

    /** Schroeder fit from -5 to -35 dB, T60 = 2*T30, gated at 20%; returns the measured T60s. */
    static List<Double> t60Decay(List<Double> decays) {
        List<Double> measured = new ArrayList<>();
        for (double decay : decays) {
            Reverb rev = new Reverb();
            rev.setParams(decay, 20000.0, 20000.0, 0.0);
            rev.setModulation(false);
            int nFrames = (int) (48000 * (2.0 * decay + 1.0));
            double[] y = new double[nFrames];
            double[] out = new double[2];
            for (int i = 0; i < nFrames; i++) {
                double in = i == 0 ? 1.0 : 0.0;
                rev.process(in, i, out);
                y[i] = 0.5 * (out[0] + out[1]);
            }
            double[] energy = new double[nFrames];
            double sum = 0.0;
            for (int i = nFrames - 1; i >= 0; i--) {
                sum += y[i] * y[i];
                energy[i] = sum;
            }
            double e0 = energy[0];
            double sumT = 0, sumDb = 0, sumTT = 0, sumTDb = 0;
            int count = 0;
            for (int i = 0; i < nFrames; i++) {
                double db = 10.0 * Math.log10(energy[i] / e0 + 1e-30);
                if (db <= -5.0 && db >= -35.0) {
                    double t = (double) i / 48000.0;
                    sumT += t; sumDb += db;
                    sumTT += t * t; sumTDb += t * db;
                    count++;
                }
            }
            check(count > 100, "Sufficient regression samples for T60 fit");
            double slope = (count * sumTDb - sumT * sumDb) / (count * sumTT - sumT * sumT);
            double t30 = -30.0 / slope;
            double t60 = 2.0 * t30;
            measured.add(t60);
            double error = Math.abs(t60 - decay) / decay;
            check(error <= 0.20, String.format(Locale.ROOT,
                    "T60 decay fit for %.1fs: measured %.3fs (slope %.2f dB/s, error %.1f%%)",
                    decay, t60, slope, error * 100));
        }
        return measured;
    }

    /** Peak -1 dB within 0.2 dB with modulation off; at 2.5 s the modulated peaks stay at or below -0.5 dB. */
    static void peakGainAndModulatedStability(List<Double> decays) {
        List<Double> peakFreqs25 = new ArrayList<>();

        for (double decay : decays) {
            Reverb rev = new Reverb();
            rev.setParams(decay, 20000.0, 20000.0, 0.0);
            rev.setModulation(false);
            int nFrames = (int) Math.max(48000 * 2, decay * 48000 * 4);
            double[] irL = new double[nFrames], irR = new double[nFrames];
            double[] out = new double[2];
            for (int i = 0; i < nFrames; i++) {
                double in = i == 0 ? 1.0 : 0.0;
                rev.process(in, i, out);
                irL[i] = out[0]; irR[i] = out[1];
            }

            int nFft = 1;
            while (nFft < nFrames * 8) nFft <<= 1;
            double[] realL = new double[nFft], imagL = new double[nFft];
            double[] realR = new double[nFft], imagR = new double[nFft];
            for (int i = 0; i < nFrames; i++) {
                // IR has decayed below -120 dB, so an unwindowed padded FFT is the exact response
                realL[i] = irL[i];
                realR[i] = irR[i];
            }
            fft(realL, imagL);
            fft(realR, imagR);

            int nBins = nFft / 2;
            double[] mag = new double[nBins];
            for (int i = 0; i < nBins; i++) {
                double mL = Math.sqrt(realL[i] * realL[i] + imagL[i] * imagL[i]);
                double mR = Math.sqrt(realR[i] * realR[i] + imagR[i] * imagR[i]);
                mag[i] = Math.max(mL, mR);
            }

            // Top 10 local maxima, so neighbouring bins of one peak are not counted twice
            List<Integer> maxima = new ArrayList<>();
            for (int i = 1; i < nBins - 1; i++)
                if (mag[i] > mag[i - 1] && mag[i] >= mag[i + 1]) maxima.add(i);
            maxima.sort((a, b) -> Double.compare(mag[b], mag[a]));
            Integer[] indices = maxima.toArray(new Integer[0]);

            double maxPeakGain = 0.0;
            double binHz = 48000.0 / nFft;
            List<Double> topFreqs = new ArrayList<>();
            for (int rank = 0; rank < 10; rank++) {
                int bin = indices[rank];
                double centerFreq = bin * binHz;
                topFreqs.add(centerFreq);
                // Refine with stepped steady-state sines at +-0.5 bin spacing
                for (double offset : new double[]{-0.5, 0.0, 0.5}) {
                    double f = centerFreq + offset * binHz;
                    if (f <= 20.0 || f >= 20000.0) continue;
                    double ss = measureSteadyStateSine(decay, f, false);
                    maxPeakGain = Math.max(maxPeakGain, ss);
                }
            }

            double maxPeakDb = 20.0 * Math.log10(maxPeakGain);
            check(Math.abs(maxPeakDb - (-1.0)) <= 0.2, String.format(Locale.ROOT,
                    "Peak gain for decay %.1fs: measured %.2f dB (target -1.0 +- 0.2 dB)", decay, maxPeakDb));

            if (decay == 2.5) peakFreqs25.addAll(topFreqs);
        }

        // Modulated stability for decay = 2.5 s at the 10 peak frequencies
        for (double f : peakFreqs25) {
            double ssMod = measureSteadyStateSine(2.5, f, true);
            double ssModDb = 20.0 * Math.log10(ssMod);
            check(ssModDb <= -0.5, String.format(Locale.ROOT,
                    "Modulated steady-state gain at %.1f Hz: %.2f dB (target <= -0.5 dB)", f, ssModDb));
        }
    }

    private static double measureSteadyStateSine(double decay, double freqHz, boolean modulation) {
        Reverb rev = new Reverb();
        rev.setParams(decay, 20000.0, 20000.0, 0.0);
        rev.setModulation(modulation);
        double omega = 2.0 * Math.PI * freqHz / 48000.0;
        int durationFrames = (int) Math.max(48000 * 2, decay * 48000 * 3);
        int measureStart = durationFrames - 48000;
        double maxMag = 0.0;
        double[] out = new double[2];
        for (int i = 0; i < durationFrames; i++) {
            double in = Math.sin(omega * i);
            rev.process(in, i, out);
            if (i >= measureStart) {
                maxMag = Math.max(maxMag, Math.max(Math.abs(out[0]), Math.abs(out[1])));
            }
        }
        return maxMag;
    }

    /** delay, reverb, mix_bus(0.5) loop with a short, a 0.5 s and a tank-length delay. */
    static void feedbackConvergence(double decay) {
        for (int delayFrames : List.of(64, 24000, 34819)) {
            Graph g = new Graph(3, List.of(
                    new Graph.Node("tone", NodeType.TONE, Map.of()),
                    new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                    new Graph.Node("delay", NodeType.DELAY, Map.of(NodeParam.FRAMES, (double) delayFrames)),
                    new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, decay)),
                    new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.5)),
                    new Graph.Node("out", NodeType.OUTPUT, Map.of())
            ), List.of(
                    Graph.edge("tone", "render"),
                    Graph.edge("render", "bus"),
                    Graph.edge("delay", "rev"),
                    Graph.edge("rev", "bus"),
                    Graph.edge("bus", "delay"),
                    new Graph.Edge("bus", "out", "out", "audio")
            ));
            LoopPlan plan = GraphCompiler.compile(g);
            SessionState st = new SessionState(0L, 0L, 0.0, 120.0, true, g);
            SignalRuntime rt = plan.signals().runtime(st);
            double[] stereo = new double[2];

            // 1 second of input
            double peakDuringInput = 0.0;
            for (int i = 0; i < 48000; i++) {
                stereo[0] = Math.sin(0.1 * i); stereo[1] = Math.sin(0.1 * i);
                rt.process(stereo, i * 20833L);
                peakDuringInput = Math.max(peakDuringInput, Math.max(Math.abs(stereo[0]), Math.abs(stereo[1])));
            }

            // After input stops, verify energy strictly falls in successive 1s windows and reverbGuardHits == 0
            double prevEnergy = Double.MAX_VALUE;
            double lastEnergy = 0.0;
            for (int window = 2, last = 2 + (int) Math.ceil(1.6 * decay); window <= last; window++) {
                double windowEnergy = 0.0;
                for (int i = 0; i < 48000; i++) {
                    stereo[0] = 0.0; stereo[1] = 0.0;
                    long nanos = (window * 48000L + i) * 20833L;
                    rt.process(stereo, nanos);
                    windowEnergy += stereo[0] * stereo[0] + stereo[1] * stereo[1];
                }
                check(windowEnergy < prevEnergy, "Feedback energy strictly falls from 2s on for delay " + delayFrames);
                prevEnergy = windowEnergy;
                lastEnergy = windowEnergy;
            }
            check(rt.reverbGuardHits() == 0, "No reverbGuardHits during feedback loop");
            double relativeDb = 10.0 * Math.log10(lastEnergy / (peakDuringInput * peakDuringInput * 48000.0) + 1e-30);
            check(relativeDb < -60.0, "Falls below -60 dB: " + relativeDb + " dB");
        }
    }

    private static void nodeTypeExhaustiveness() {
        for (NodeType type : NodeType.values()) {
            check(type.outputPorts() != null && type.inputPorts() != null, "Ports defined for " + type);
            if (!type.isSignalNode() || type == NodeType.OUTPUT) continue;
            // Default params must validate and compile for every signal node
            Graph.Node node = new Graph.Node("n", type, Map.of());
            List<Graph.Edge> edges = new ArrayList<>(List.of(Graph.edge("tone", "render")));
            boolean audioIn = type.inputPorts().stream().anyMatch(port -> port.type() == PortType.AUDIO);
            boolean audioOut = type.outputPorts().stream().anyMatch(port -> port.type() == PortType.AUDIO);
            if (!audioIn || !audioOut) continue;
            edges.add(new Graph.Edge("render", "out", "n", type.inputPorts().stream().filter(port -> port.type() == PortType.AUDIO).findFirst().orElseThrow().name()));
            edges.add(new Graph.Edge("n", "out", "out", "audio"));
            Graph g = new Graph(3, List.of(new Graph.Node("tone", NodeType.TONE, Map.of()),
                    new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()), node,
                    new Graph.Node("out", NodeType.OUTPUT, Map.of())), edges);
            LoopPlan plan = GraphCompiler.compile(g);
            SignalRuntime rt = plan.signals().runtime(new SessionState(0L, 0L, 0.0, 120.0, true, g));
            double[] stereo = {0.5, 0.5};
            rt.process(stereo, 0L);
            check(Double.isFinite(stereo[0]) && Double.isFinite(stereo[1]), "Runtime routes " + type);
            check(SignalGraph.isLoopGainValid(g.nodes(), g.edges()), "Loop-gain check handles " + type);
        }
    }

    private static Graph reverbSong(double decay) {
        return new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("pulse", NodeType.EUCLID, Map.of("steps", 16.0, "pulses", 1.0)),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, decay)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "pulse"), Graph.edge("pulse", "render"), Graph.edge("render", "rev"),
                new Graph.Edge("rev", "out", "out", "audio")
        ));
    }

    private static float[] renderLive(Graph graph, int frames, int chunk) {
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(
                new SessionState(1, 0, 0, 120, true, graph), GraphCompiler.compile(graph)), null);
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(timeline);
        float[] output = new float[frames * 2];
        for (int at = 0; at < frames; at += chunk) {
            int size = Math.min(chunk, frames - at);
            float[] buffer = new float[size * 2];
            long now = Math.round(at * 1e9 / 48000);
            timeline.prepare(now);
            renderer.render(buffer, size, now);
            System.arraycopy(buffer, 0, output, at * 2, size * 2);
        }
        return output;
    }

    private static void liveParity() {
        Graph g = reverbSong(1.8);
        float[] big = renderLive(g, 48000, 4096);
        float[] small = renderLive(g, 48000, 64);
        float[] again = renderLive(g, 48000, 64);
        double energy = 0, maxDiff = 0;
        for (int i = 0; i < big.length; i++) {
            energy += big[i] * big[i];
            maxDiff = Math.max(maxDiff, Math.abs(big[i] - small[i]));
        }
        check(energy > 1e-3, "Reverb song renders audio");
        check(maxDiff < 1e-6, "Reverb callback size parity: " + maxDiff);
        check(Arrays.equals(small, again), "Identical renderers give identical reverb output");
    }

    private static void lateJoin() {
        Graph g = reverbSong(0.5);
        // Replay of 1 s history takes about 16,000 frames to catch the clock, then fade and 100 ms
        int joinAt = 98400, compareFrom = joinAt + 16000 + 240 + 4800, end = compareFrom + 9600;
        float[] reference = renderLive(g, end + 64, 64);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(
                new SessionState(1, 0, 0, 120, true, g), GraphCompiler.compile(g)), null);
        LiveRenderer joined = new LiveRenderer();
        joined.publish(timeline);
        float[] block = new float[128];
        double maxDiff = 0, energy = 0;
        for (int at = joinAt; at < end; at += 64) {
            long now = Math.round(at * 1e9 / 48000);
            timeline.prepare(now);
            joined.render(block, 64, now);
            if (at >= compareFrom) for (int i = 0; i < 128; i++) {
                energy += block[i] * block[i];
                maxDiff = Math.max(maxDiff, Math.abs(block[i] - reference[at * 2 + i]));
            }
        }
        check(energy > 1e-6, "Late joiner hears the reverb: " + energy);
        check(maxDiff < 1e-3, "Late join matches continuous reverb within 1e-3: " + maxDiff + " energy " + energy);
        check(joined.historyRecoveries() == 1, "Reverb-only graph triggers history recovery: " + joined.historyRecoveries());
        check(joined.reverbGuardHits() == 0, "No reverb guard hits on late join");
    }

    private static Graph reverbGraph(Map<String, Double> params) {
        return new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of()),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("rev", NodeType.REVERB, params),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())
        ), List.of(
                Graph.edge("tone", "render"),
                Graph.edge("render", "rev"),
                new Graph.Edge("rev", "out", "out", "audio")
        ));
    }

    private static void checkCompiles(Graph g) {
        LoopPlan plan = GraphCompiler.compile(g);
        check(plan != null && plan.signals() != null, "Valid graph compiled");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid argument rejection");
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wlenR = Math.cos(ang), wlenI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wR = 1.0, wI = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double uR = real[i + j], uI = imag[i + j];
                    double vR = real[i + j + len / 2] * wR - imag[i + j + len / 2] * wI;
                    double vI = real[i + j + len / 2] * wI + imag[i + j + len / 2] * wR;
                    real[i + j] = uR + vR;
                    imag[i + j] = uI + vI;
                    real[i + j + len / 2] = uR - vR;
                    imag[i + j + len / 2] = uI - vI;
                    double nwR = wR * wlenR - wI * wlenI;
                    wI = wR * wlenI + wI * wlenR;
                    wR = nwR;
                }
            }
        }
    }
}
