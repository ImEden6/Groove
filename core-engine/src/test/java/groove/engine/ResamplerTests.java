package groove.engine;

import groove.engine.samples.SampleData;
import java.util.Locale;

/** End-to-end PCM measurements, including octave prefilters and table interpolation. */
final class ResamplerTests {
    private static volatile double sink;
    static void run() {
        double worstRejection = Double.POSITIVE_INFINITY, worstPassError = 0;
        for (double step : new double[]{1.01, 1.5, 1.999, 2, 2.01, 3, 3.999, 4, 8, 15.999, 16}) {
            double nyquist = .5 / step;
            for (int band = 0; band < 9; band++) {
                double frequency = nyquist + (.499 - nyquist) * band / 8;
                SampleData tone = tone(frequency);
                double energy = 0;
                for (int i = 0; i < 512; i++) {
                    double value = tone.at(2048.37 + i * step, 0, step);
                    energy += value * value;
                }
                double rejection = -10 * Math.log10(energy / 512 * 2);
                worstRejection = Math.min(worstRejection, rejection);
                check(rejection >= 50, "Stopband rejection " + rejection + " dB at step=" + step + ", frequency=" + frequency);
            }
        }
        for (double step : new double[]{1.0/24, .25, .5, .75, 1, 1.01, 1.5, 1.999, 2, 2.01, 4, 8, 16}) {
            double frequency = .4 / Math.max(1, step);
            SampleData tone = tone(frequency);
            double error = 0, reference = 0;
            for (int i = 0; i < 512; i++) {
                double position = 2048.37 + i * step;
                double expected = Math.sin(2 * Math.PI * frequency * position + .37);
                double difference = tone.at(position, 0, step) - expected;
                error += difference * difference; reference += expected * expected;
            }
            double relative = Math.sqrt(error / reference);
            worstPassError = Math.max(worstPassError, relative);
            // Includes passband gain/phase error and interpolation images for upsampling.
            check(relative < .003, "Passband RMS error at step=" + step + ": " + relative);
        }
        SampleData fixture = tone(.1);
        check(fixture.bytes() == 16384L * 4 * 31 / 16, "Cache accounting includes all prefiltered PCM levels");
        check(fixture.at(500, 0, 1) == fixture.at(500, 0), "Unconverted PCM remains bit exact");
        System.out.printf(Locale.ROOT, "Resampler sweep: minimum %.2f dB stopband rejection; maximum %.5f%% passband RMS error.%n",
                worstRejection, worstPassError * 100);
        benchmark(fixture);
        voiceBenchmark();
    }
    private static SampleData tone(double frequency) {
        float[] pcm = new float[16384];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float)Math.sin(2 * Math.PI * frequency * i + .37);
        return new SampleData(48000, 1, pcm);
    }
    private static void benchmark(SampleData data) {
        for (int warm = 0; warm < 4; warm++) measure(data, 50000, false);
        long linear = Long.MAX_VALUE, sinc = Long.MAX_VALUE;
        for (int repeat = 0; repeat < 3; repeat++) {
            linear = Math.min(linear, measure(data, 250000, true));
            sinc = Math.min(sinc, measure(data, 250000, false));
        }
        System.out.printf(Locale.ROOT, "Resampler local microbenchmark: linear %.1f ns/sample, bandlimited %.1f ns/sample (16x conversion).%n",
                linear / 250000.0, sinc / 250000.0);
    }
    private static void voiceBenchmark() {
        float[] pcm = new float[192000 * 2];
        for (int i = 0; i < 192000; i++) pcm[i*2] = pcm[i*2+1] = (float)(.1 * Math.sin(i * .01));
        SampleData data = new SampleData(192000, 2, pcm);
        var ref = groove.engine.samples.FactorySamples.ref("factory:basic/kick.wav");
        for (double pitch : new double[]{4, 3.999}) {
        Pattern[] patterns = new Pattern[32];
        java.util.Arrays.fill(patterns, Pattern.sample(new groove.engine.samples.SampleVoice(ref, pitch, .05, 0)));
        var plan = new LoopPlan(Pattern.stack(patterns).query(new Arc(0, 1)));
        var state = new SessionState(1, 0, 0, 120, true, Graph.demo());
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, plan, java.util.Map.of(ref, data)), null);
        float[] out = new float[8192]; long best = Long.MAX_VALUE;
        for (int trial = 0; trial < 5; trial++) {
            LiveRenderer renderer = new LiveRenderer(); renderer.publish(timeline);
            long start = System.nanoTime(); renderer.render(out, 4096, 0);
            long elapsed = System.nanoTime() - start;
            if (trial >= 2) best = Math.min(best, elapsed);
        }
        System.out.printf(Locale.ROOT, "32 stereo voices at %.3fx: %.2f ms to render 85.33 ms of audio (local best warmed trial).%n", pitch * 4, best / 1e6);
        }
    }
    private static long measure(SampleData data, int samples, boolean linear) {
        long start = System.nanoTime(); double total = 0;
        for (int i = 0; i < samples; i++) {
            double position = 2048.37 + (i % 512) * 16;
            total += linear ? data.at(position, 0) : data.at(position, 0, 16);
        }
        sink = total; return System.nanoTime() - start;
    }
    private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
}
