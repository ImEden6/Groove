package groove.engine;

import groove.engine.samples.*;
import java.util.Locale;
import java.util.Map;

/** Step 9 demo audio: both offline demos play every part, stay in range, and let their effects ring out. */
final class DemoTests {
    private static int checks;
    private static final int RATE = LiveRenderer.SAMPLE_RATE;
    private static final double MINUS_40_DB = Math.pow(10, -40 / 20.0), MINUS_50_DB = Math.pow(10, -50 / 20.0);

    static void run() {
        long startNanos = System.nanoTime();
        sampleDemo();
        signalDemo();
        inGameSampleDemo();
        System.out.printf("Demo audio checks passed (%d checks) in %.0f ms.%n", checks, (System.nanoTime() - startNanos) / 1e6);
    }

    private static void sampleDemo() {
        float[] audio = SampleDemo.render();
        check(audio.length == SampleDemo.TOTAL_FRAMES * 2, "Sample demo renders 16 s");
        inRange(audio, "Sample demo");
        int cycleFrames = RATE * 240 / SampleDemo.BPM;
        StringBuilder levels = new StringBuilder();
        for (int cycle = 0; cycle < SampleDemo.CYCLES; cycle++) {
            double rms = rms(audio, cycle * cycleFrames, (cycle + 1) * cycleFrames);
            levels.append(String.format(Locale.ROOT, " %.1f", 20 * Math.log10(rms)));
            // Cycle 3 plays the reversed bar
            check(rms > MINUS_40_DB, "Sample demo cycle " + cycle + " above -40 dBFS, was " + 20 * Math.log10(rms));
        }
        double tail = rms(audio, (int) (RATE * 14.0), (int) (RATE * 14.2));
        double end = rms(audio, (int) (RATE * 15.8), RATE * 16);
        check(tail > MINUS_50_DB, "Reverb tail at 14.0-14.2 s above -50 dBFS, was " + 20 * Math.log10(tail));
        check(end < MINUS_50_DB && end < tail, "Tail decays below -50 dBFS by 15.8-16.0 s, was " + 20 * Math.log10(end));
        System.out.printf(Locale.ROOT, "Sample demo: cycles%s dBFS, tail %.1f dBFS, end %.1f dBFS.%n",
                levels, 20 * Math.log10(tail), 20 * Math.log10(end));
    }

    private static void signalDemo() {
        float[] audio = SignalDemo.render(SignalDemo.reverbSources(), SignalDemo.TOTAL_FRAMES);
        inRange(audio, "Signal demo");
        for (int second = 0; second < SignalDemo.TOTAL_FRAMES / RATE; second++) {
            double rms = rms(audio, second * RATE, (second + 1) * RATE);
            check(rms > MINUS_40_DB, "Signal demo second " + second + " above -40 dBFS, was " + 20 * Math.log10(rms));
        }
    }

    /** The graph /groove sample-demo loads compiles through the loop-gain check and plays. */
    private static void inGameSampleDemo() {
        Graph graph = FactorySamples.reverbDemo();
        var bank = new java.util.HashMap<AssetRef, SampleData>();
        for (String id : FactorySamples.ids()) bank.put(FactorySamples.ref(id), WavDecoder.decode(FactorySamples.bytes(id)));
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(
                new SessionState(1, 0, 0, 120, true, graph), GraphCompiler.compile(graph), Map.copyOf(bank)), null);
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(timeline);
        float[] audio = new float[RATE * 4 * 2], block = new float[1024];
        for (int at = 0; at < RATE * 4; at += 512) {
            long now = Math.round(at * 1e9 / RATE);
            timeline.prepare(now);
            renderer.render(block, 512, now);
            System.arraycopy(block, 0, audio, at * 2, 1024);
        }
        inRange(audio, "In-game sample demo");
        check(rms(audio, 0, RATE * 4) > MINUS_40_DB && renderer.scheduleMisses() == 0, "In-game sample demo plays its kit");
    }

    private static void inRange(float[] audio, String name) {
        double peak = 0;
        boolean finite = true;
        for (float value : audio) { finite &= Float.isFinite(value); peak = Math.max(peak, Math.abs(value)); }
        check(finite, name + " samples are finite");
        check(peak <= 1.0, name + " peak within 1.0, was " + peak);
    }

    private static double rms(float[] audio, int fromFrame, int toFrame) {
        double sum = 0;
        for (int i = fromFrame * 2; i < toFrame * 2; i++) sum += audio[i] * audio[i];
        return Math.sqrt(sum / ((toFrame - fromFrame) * 2));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
