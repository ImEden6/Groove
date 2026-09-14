package groove.engine;

import java.util.Arrays;
import java.util.List;

/** Dependency-free regression suite; failures throw regardless of JVM assertion settings. */
public final class EngineTests {
    private static int checks;
    private static final Tone TONE = new Tone(Tone.Wave.SINE, 220, .3, 0, 20000);

    public static void main(String[] args) {
        MusicalPatternTests.run();
        PortTests.run();
        SignalTests.run();
        DspTests.run();
        ResamplerTests.run();
        SchedulerTests.run();
        invalid(() -> new Arc(Double.NaN, 1));
        invalid(() -> new Arc(2, 1));
        invalid(() -> Pattern.tone(TONE).fast(Double.POSITIVE_INFINITY));
        invalid(() -> Pattern.tone(TONE).euclid(8, 9, 0));
        check(new Arc(0, 1).intersect(new Arc(1, 2)) == null, "Half-open intersection");
        check(Pattern.tone(TONE).query(new Arc(.5, .5)).isEmpty(), "Empty query");
        Event clipped = Pattern.tone(TONE).query(new Arc(.25, .75)).getFirst();
        check(clipped.whole().equals(new Arc(0, 1)), "Whole survives clipping");
        check(clipped.part().equals(new Arc(.25, .75)), "Query clips part");
        check(Pattern.tone(TONE).query(new Arc(-1, 1)).size() == 2, "Negative cycles");
        check(Pattern.tone(TONE).fast(2).query(new Arc(0, 1)).size() == 2, "Speed doubles events");
        for (int steps = 1; steps <= 32; steps++) {
            for (int pulses = 0; pulses <= steps; pulses++) {
                Pattern pattern = Pattern.tone(TONE).euclid(steps, pulses, -3);
                check(pattern.query(new Arc(0, 1)).size() == pulses, "Euclid pulse count");
            }
        }
        check(Pattern.tone(TONE).euclid(8, 1, 2).query(new Arc(0, 1)).getFirst().whole().start() == .25,
                "Positive rotation delays hit");
        Pattern nested = Pattern.tone(TONE).euclid(8, 3, -1).fast(2);
        List<Event> full = nested.query(new Arc(0, 2));
        List<Event> split = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) split.addAll(nested.query(new Arc(i / 16.0, (i + 1) / 16.0)));
        check(split.stream().map(Event::whole).distinct().count() == full.size(), "Partitioned query retains notes");
        double fullDuration = full.stream().mapToDouble(e -> e.part().end() - e.part().start()).sum();
        double splitDuration = split.stream().mapToDouble(e -> e.part().end() - e.part().start()).sum();
        check(Math.abs(fullDuration - splitDuration) < 1e-12, "Partitioned query retains coverage");

        Transport transport = new Transport(48000, 120, 4);
        check(transport.frameAt(1) == 96000, "Tempo/cycle conversion");
        check(transport.frameAt(1_000_000) == 96_000_000_000L, "Long-running transport");
        Score score = Score.compile(nested, transport, 1);
        check(score.size() == 6, "Scheduled onset count");
        float[] whole = new float[192000];
        new Renderer(score, 16).render(whole, 0, 96000);
        float[] chunks = new float[whole.length];
        Renderer chunked = new Renderer(score, 16);
        for (int offset = 0; offset < 96000; offset += 137)
            chunked.render(chunks, offset, Math.min(137, 96000 - offset));
        check(Arrays.equals(whole, chunks), "Audio independent of render chunk boundaries");
        double energy = 0;
        for (float sample : whole) {
            if (!Float.isFinite(sample) || Math.abs(sample) > 1) throw new AssertionError("Invalid output sample");
            energy += sample * sample;
        }
        check(energy > 1, "Non-silent audio");
        float[] tail = new float[1024];
        chunked.render(tail, 0, 512);
        check(Arrays.equals(tail, new float[1024]), "Finished voices go silent");
        Score crowded = Score.compile(Pattern.stack(Pattern.tone(TONE), Pattern.tone(TONE)), transport, 1);
        Renderer limited = new Renderer(crowded, 1);
        limited.render(tail, 0, 512);
        check(limited.stolenVoices() == 1, "Voice limit enforced");
        Score left = Score.compile(Pattern.tone(new Tone(Tone.Wave.SAW, 440, .3, -1, 20000)), transport, 1);
        new Renderer(left, 4).render(tail, 0, 512);
        for (int i = 1; i < tail.length; i += 2) check(tail[i] == 0, "Hard-left pan");
        invalid(() -> Score.compile(Pattern.tone(new Tone(Tone.Wave.SINE, 24000, .2, 0, 20000)), transport, 1));

        // --- Biquad low-pass filter ---
        Biquad passThrough = new Biquad();
        passThrough.setLowPass(24000, 48000); // >= Nyquist ceiling: must bypass, not blow up
        check(passThrough.process(0.5) == 0.5, "Cutoff at/above Nyquist bypasses unchanged");
        check(passThrough.process(-0.25) == -0.25, "Bypass is a true pass-through, not just first-sample");
        Biquad settling = new Biquad();
        settling.setLowPass(500, 48000);
        double last = 0;
        for (int i = 0; i < 1000; i++) {
            last = settling.process(i == 0 ? 1 : 0); // impulse response
            if (!Double.isFinite(last)) throw new AssertionError("Biquad diverged on impulse response");
        }
        check(Math.abs(last) < 1e-6, "Biquad impulse response decays to (near) zero");
        settling.reset();
        check(settling.process(0) == 0, "Reset clears filter history");

        invalid(() -> new Tone(Tone.Wave.SINE, 220, .3, 0, 0));
        invalid(() -> new Tone(Tone.Wave.SINE, 220, .3, 0, Double.NaN));
        Score bright = Score.compile(Pattern.tone(new Tone(Tone.Wave.SAW, 2000, .8, 0, 20000)), transport, 1);
        Score dark = Score.compile(Pattern.tone(new Tone(Tone.Wave.SAW, 2000, .8, 0, 200)), transport, 1);
        float[] brightBuf = new float[192000], darkBuf = new float[192000];
        new Renderer(bright, 4).render(brightBuf, 0, 96000);
        new Renderer(dark, 4).render(darkBuf, 0, 96000);
        check(highFrequencyEnergy(darkBuf) < highFrequencyEnergy(brightBuf) * .5,
                "Low cutoff measurably attenuates high-frequency content");
        for (float sample : darkBuf) if (!Float.isFinite(sample) || Math.abs(sample) > 1)
            throw new AssertionError("Filtered output out of range at low cutoff");
        Score edge = Score.compile(Pattern.tone(new Tone(Tone.Wave.SAW, 2000, .8, 0, 20)), transport, 1);
        float[] edgeBuf = new float[192000];
        new Renderer(edge, 4).render(edgeBuf, 0, 96000);
        for (float sample : edgeBuf) if (!Float.isFinite(sample))
            throw new AssertionError("Filter unstable at 20 Hz cutoff");
        float[] darkWhole = new float[192000], darkChunks = new float[192000];
        new Renderer(dark, 4).render(darkWhole, 0, 96000);
        Renderer darkChunked = new Renderer(dark, 4);
        for (int offset = 0; offset < 96000; offset += 137)
            darkChunked.render(darkChunks, offset, Math.min(137, 96000 - offset));
        check(Arrays.equals(darkWhole, darkChunks), "Filtered audio independent of render chunk boundaries");
        Tone darkHalf = new Tone(Tone.Wave.SAW, 2000, .8, 0, 200);
        Tone brightHalf = new Tone(Tone.Wave.SAW, 2000, .8, 0, 20000);
        Score sharedVoice = Score.compile(Pattern.stack(
                Pattern.tone(darkHalf).euclid(2, 1, 0), Pattern.tone(brightHalf).euclid(2, 1, 1)), transport, 1);
        float[] shared = new float[192000];
        new Renderer(sharedVoice, 1).render(shared, 0, 96000);
        Score isolated = Score.compile(Pattern.tone(brightHalf).euclid(2, 1, 1), transport, 1);
        float[] isolatedBuf = new float[192000];
        new Renderer(isolated, 1).render(isolatedBuf, 0, 96000);
        check(Arrays.equals(Arrays.copyOfRange(shared, 96000, shared.length),
                Arrays.copyOfRange(isolatedBuf, 96000, isolatedBuf.length)),
                "Filter state does not bleed across voice reuse");
        System.out.println("Passed " + checks + " checks.");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid argument rejection");
    }
    /** Mean squared sample-to-sample delta: a crude proxy that rises with high-frequency content. */
    private static double highFrequencyEnergy(float[] interleavedStereo) {
        double sum = 0;
        int count = 0;
        for (int i = 2; i < interleavedStereo.length; i++) {
            double delta = interleavedStereo[i] - interleavedStereo[i - 2];
            sum += delta * delta;
            count++;
        }
        return sum / count;
    }
}
