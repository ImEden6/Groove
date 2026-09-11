package groove.engine;

import groove.engine.samples.*;
import java.util.*;

/** Signal-level regression checks for the Phase 1 DSP path. */
final class DspTests {
    static void run() {
        for (double q : new double[]{.1, Biquad.DEFAULT_Q, 2, 20}) {
            Biquad filter = new Biquad();
            for (double cutoff : new double[]{20, 100, 500, 5000, 20000}) {
                filter.setLowPass(cutoff, q, 48000);
                for (int i = 0; i < 24000; i++) {
                    double y = filter.process(.1 * Math.sin(2 * Math.PI * cutoff * i / 48000));
                    check(Double.isFinite(y) && Math.abs(y) < 10, "Q sweep stays bounded");
                }
            }
        }
        Biquad normal = new Biquad(), resonant = new Biquad();
        normal.setLowPass(1000, 48000); resonant.setLowPass(1000, 4, 48000);
        double a = 0, b = 0;
        for (int i = 0; i < 4800; i++) {
            double x = .1 * Math.sin(2 * Math.PI * 1000 * i / 48000);
            double y = normal.process(x), z = resonant.process(x);
            if (i > 2400) { a += y*y; b += z*z; }
        }
        check(b > a * 20, "Q produces a resonance peak");
        invalid(() -> normal.setLowPass(500, Double.NaN, 48000));
        invalid(() -> new Tone(Tone.Wave.SINE, 220, .2, 0, 500, 21));
        for (int version : new int[]{1, 2}) {
            Graph g = graph(version, NodeType.TONE, Map.of(NodeParam.RESONANCE_Q, 3.0), null);
            check(GraphCompiler.compile(g).event(0).tone().resonanceQ() == 3, "Q accepted without schema bump");
            check(GraphCompiler.compile(graph(version, NodeType.TONE, Map.of(), null)).event(0).tone().resonanceQ() == Biquad.DEFAULT_Q, "Legacy Q default");
        }
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");
        invalid(() -> new SampleVoice(ref, 1, 1, 0, 0, 1));
        Graph filteredGraph = graph(2, NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.CUTOFF_HZ, 500.0, NodeParam.RESONANCE_Q, 1.0), ref);
        check(GraphCompiler.compile(filteredGraph).event(0).sample().cutoffHz() == 500, "Sample filter compiles");
        float[] source = new float[48000 * 3 * 2];
        for (int i = 0; i < source.length / 2; i++) source[i * 2] = (float)(.4 * Math.sin(2 * Math.PI * 8000 * i / 48000));
        SampleData pcm = new SampleData(48000, 2, source);
        float[] dry = liveSample(ref, pcm, 20000), wet = liveSample(ref, pcm, 500);
        check(energy(wet, 48000) < energy(dry, 48000) * .001, "Overlapping samples attenuate treble");
        for (int i = 1; i < wet.length; i += 2) check(wet[i] == 0, "Stereo filters do not leak across channels");

        float[] tone = new float[48000];
        for (int i = 0; i < tone.length; i++) tone[i] = (float)Math.sin(2 * Math.PI * 10000 * i / 48000);
        SampleData sine = new SampleData(48000, 1, tone);
        double linear = 0, sinc = 0;
        for (int i = 10; i < 11000; i++) {
            double position = i * 4 + .25;
            double l = sine.at(position, 0), r = sine.at(position, 0, 4);
            linear += l*l; sinc += r*r;
        }
        double rejection = 10 * Math.log10(linear / sinc);
        System.out.printf(Locale.ROOT, "8-point sinc: %.2f dB alias reduction vs linear (10 kHz, 4x, 48 kHz).%n", rejection);
        check(rejection > 6, "Sinc reduces above-Nyquist alias energy");
        for (double step : new double[]{.25, .5, 1, 2, 4, 16})
            for (double position : new double[]{0, .1, 100.25, 47999.9})
                check(Float.isFinite(sine.at(position, 0, step)), "Resampling finite at boundaries and rates");
        float[] dc = new float[100]; Arrays.fill(dc, .5f);
        SampleData constant = new SampleData(48000, 1, dc);
        check(Math.abs(constant.at(50.25, 0, 4) - .5) < 1e-6, "Sinc has unity DC gain");
        stealFade();
        System.out.println("Phase 1 DSP regressions passed.");
    }
    private static void stealFade() {
        Tone t = new Tone(Tone.Wave.SINE, 53, .7, 0, 20000);
        // A sustained voice is stolen at 0.1 seconds, away from its zero crossing.
        Pattern p = arc -> {
            List<Event> events = new ArrayList<>();
            for (Arc whole : List.of(new Arc(0, 1), new Arc(.05, .1))) {
                Arc part = whole.intersect(arc);
                if (part != null) events.add(new Event(whole, part, t));
            }
            return events;
        };
        Score score = Score.compile(p, new Transport(48000, 120, 4), 1);
        Renderer renderer = new Renderer(score, 1);
        float[] audio = new float[12000]; renderer.render(audio, 0, 6000);
        check(renderer.stolenVoices() == 1, "Forced offline steal");
        check(Math.abs(audio[9600] - audio[9598]) < .05, "Offline steal is continuous");
        float[] split = new float[audio.length]; Renderer chunked = new Renderer(score, 1);
        for (int i = 0; i < 6000; i += 73) chunked.render(split, i, Math.min(73, 6000-i));
        check(Arrays.equals(audio, split), "Tail fades independent of block size");
        List<Event> crowded = new ArrayList<>();
        Tone quiet = new Tone(Tone.Wave.SINE, 53, .015, 0, 20000);
        for (int i = 0; i < 32; i++) crowded.add(new Event(new Arc(0, 1), new Arc(0, 1),
                new Tone(Tone.Wave.SINE, 53, i == 31 ? .7 : 0, 0, 20000)));
        crowded.add(new Event(new Arc(.05, .1), new Arc(.05, .1), quiet));
        LiveRenderer live = new LiveRenderer();
        var state = new SessionState(1, 0, 0, 120, true, Graph.demo());
        live.publish(new LiveRenderer.Timeline(new LiveRenderer.Program(state, new LoopPlan(crowded)), null));
        live.render(audio, 6000, 0);
        check(Math.abs(audio[9600]-audio[9598]) < .05, "Live steal is continuous");
        check(energy(audio, 0) > 1, "Crowded live fixture is audible");
        AssetRef ref = FactorySamples.ref("factory:basic/kick.wav");
        float[] dc = new float[48000]; Arrays.fill(dc, .8f);
        SampleData pcm = new SampleData(48000, 1, dc);
        crowded.clear();
        for (int i = 0; i < 32; i++) crowded.add(new Event(new Arc(0, 1), new Arc(0, 1), null,
                new SampleVoice(ref, 1, i == 31 ? .7 : 0, 0)));
        crowded.add(new Event(new Arc(.05, .1), new Arc(.05, .1), null, new SampleVoice(ref, 1, 0, 0)));
        LiveRenderer samples = new LiveRenderer();
        samples.publish(new LiveRenderer.Timeline(new LiveRenderer.Program(state, new LoopPlan(crowded), Map.of(ref, pcm)), null));
        samples.render(audio, 6000, 0);
        check(Math.abs(audio[9598]) > .2, "Sample steal fixture carries a loud victim");
        for (int i = 4800; i < 4921; i++) check(Math.abs(audio[i*2] - audio[i*2-2]) < .05, "Sample steal cosine tail is continuous");
        check(Math.abs(audio[10000]) < 1e-6, "Stolen sample tail finishes after 120 frames");

    }
    private static float[] liveSample(AssetRef ref, SampleData pcm, double cutoff) {
        SampleVoice v = new SampleVoice(ref, 1, .3, 0, cutoff, Biquad.DEFAULT_Q);
        LoopPlan plan = new LoopPlan(Pattern.sample(v).fast(4).query(new Arc(0, 1)));
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(new LiveRenderer.Timeline(new LiveRenderer.Program(new SessionState(1, 0, 0, 120, true, Graph.demo()), plan, Map.of(ref, pcm)), null));
        float[] out = new float[48000 * 3 * 2]; renderer.render(out, out.length / 2, 0); return out;
    }
    private static Graph graph(int version, NodeType type, Map<String, Double> params, AssetRef ref) {
        return new Graph(version, List.of(new Graph.Node("v", type, params, ref), new Graph.Node("out", NodeType.OUTPUT, Map.of())), List.of(Graph.edge("v", "out")));
    }
    private static double energy(float[] data, int start) { double sum = 0; for (int i = start; i < data.length; i++) sum += data[i]*data[i]; return sum; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void invalid(Runnable r) { try { r.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejection"); }
}
