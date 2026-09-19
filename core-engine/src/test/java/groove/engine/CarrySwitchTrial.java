package groove.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * Report only: discontinuities around switches, carried or not. Inputs are sines at 300 Hz and
 * levels stay low, so nearly all energy above 6 kHz comes from steps in the signal. The score is
 * the loudest 5 ms window of that energy in the 2 s after each switch, over the loudest steady
 * window well before the first switch and well after the last, in dB. Run with
 * {@code java -cp <core-engine test and main classes> groove.engine.CarrySwitchTrial [rampFrames]}.
 */
public final class CarrySwitchTrial {
    private static final int RATE = LiveRenderer.SAMPLE_RATE, BLOCK = 512, WINDOW = RATE / 200;
    private static final long SETTLE = Math.round(3e9), AFTER = Math.round(2e9), TAIL = Math.round(4e9);

    public static void main(String[] args) {
        if (args.length > 0) SignalRuntime.rampFrames = Integer.parseInt(args[0]);
        System.out.println(String.format(Locale.ROOT, "Ramp %d frames (%.1f ms)", SignalRuntime.rampFrames, SignalRuntime.rampFrames * 1e3 / RATE));
        System.out.println("Scenario | carried dB (carries) at worst window | not carried dB at worst window");
        report("tone gain 0.1 -> 0.3, into a 50 ms feedback delay", v -> loop(v, 0.5, 2400), 0.1, 0.3, 1);
        report("loop mix gain 0.2 -> 0.9", v -> loop(0.2, v, 2400), 0.2, 0.9, 1);
        report("filter cutoff 300 -> 8000 Hz, Q 2", v -> filter(v, 2.0), 300, 8000, 1);
        report("filter Q 0.7 -> 8 at 600 Hz", v -> filter(600, v), 0.7, 8, 1);
        report("reverb decay 2 -> 8 s", v -> reverb(v, 6000, 12000, 20), 2, 8, 1);
        report("reverb damping 12000 -> 1000 Hz", v -> reverb(2, v, 12000, 20), 12000, 1000, 1);
        report("reverb bandwidth 12000 -> 1000 Hz", v -> reverb(2, 6000, v, 20), 12000, 1000, 1);
        report("reverb predelay 20 -> 120 ms", v -> reverb(2, 6000, 12000, v), 20, 120, 1);
        report("delay time 2400 -> 3600 frames, in a loop", v -> loop(0.2, 0.5, (int) v), 2400, 3600, 1);
        report("tempo 120 -> 140 BPM, synced delay in a loop", v -> loop(0.2, 0.5, Map.of(NodeParam.SYNC, 1.0, NodeParam.DIVISION, 0.0)), 120, 140, 1);
        report("sustained sine, republished unchanged", v -> loop(0.2, 0.5, 2400), 0, 0, 1);
        report("20 rapid edits 20 ms apart, loop mix 0.5 <-> 0.7", v -> loop(0.2, v, 2400), 0.5, 0.7, 20);
        report("delay time 3600 -> 2400 frames, in a loop", v -> loop(0.2, 0.5, (int) v), 3600, 2400, 1);
        report("delay 2400 -> 3600, republished unchanged 10 ms later", v -> loop(0.2, 0.5, (int) v),
                new double[] {2400, 3600, 3600}, Math.round(0.01e9));
        double[] drag = new double[11];
        for (int k = 0; k < drag.length; k++) drag[k] = 2400 + 120 * k;
        report("delay time dragged 2400 -> 3600 in 10 steps 10 ms apart", v -> loop(0.2, 0.5, (int) v), drag, Math.round(0.01e9));
    }

    // === scenarios: each value v builds the graph; tempo scenarios vary the session BPM instead ===

    private static Graph.Node sine(double gain) {
        return new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.GAIN, gain, NodeParam.FREQUENCY, 300.0, NodeParam.WAVE, 0.0));
    }

    /** Stretches the tone to one 8 s note, so no note boundary falls inside the 7 s measured. */
    private static Graph held(Graph graph) {
        List<Graph.Node> nodes = new ArrayList<>(graph.nodes());
        nodes.add(new Graph.Node("hold", NodeType.FAST, Map.of(NodeParam.FACTOR, 0.25)));
        List<Graph.Edge> edges = new ArrayList<>();
        for (Graph.Edge e : graph.edges())
            if (e.fromNode().equals("tone")) { edges.add(Graph.edge("tone", "hold")); edges.add(new Graph.Edge("hold", e.fromPort(), e.toNode(), e.toPort())); }
            else edges.add(e);
        return new Graph(3, nodes, edges);
    }

    private static Graph loop(double toneGain, double mix, int frames) {
        return loop(toneGain, mix, Map.of(NodeParam.FRAMES, (double) frames));
    }

    private static Graph loop(double toneGain, double mix, Map<String, Double> delay) {
        return new Graph(3, List.of(sine(toneGain), new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, mix)),
                new Graph.Node("echo", NodeType.DELAY, delay),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "bus"), Graph.edge("bus", "echo"),
                        Graph.edge("echo", "bus"), new Graph.Edge("bus", "out", "out", "audio")));
    }

    private static Graph filter(double cutoff, double q) {
        return new Graph(3, List.of(sine(0.3), new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("lp", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, cutoff, NodeParam.RESONANCE_Q, q)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "lp"), new Graph.Edge("lp", "out", "out", "audio")));
    }

    private static Graph reverb(double decay, double damping, double bandwidth, double predelay) {
        return new Graph(3, List.of(sine(0.2), new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, decay, NodeParam.DAMPING_HZ, damping,
                        NodeParam.BANDWIDTH_HZ, bandwidth, NodeParam.PRE_DELAY_MS, predelay)),
                new Graph.Node("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.7)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "rev"), Graph.edge("render", "mix"),
                        Graph.edge("rev", "mix"), new Graph.Edge("mix", "out", "out", "audio")));
    }

    // === measurement ===

    /** Edits alternate between the two values, 20 ms apart. */
    private static void report(String name, DoubleFunction<Graph> raw, double from, double to, int edits) {
        double[] values = new double[edits + 1];
        for (int e = 0; e <= edits; e++) values[e] = e % 2 == 0 ? from : to;
        report(name, raw, values, Math.round(0.02e9));
    }

    /** values[0] plays first; each later value is one edit, spacing apart. */
    private static void report(String name, DoubleFunction<Graph> raw, double[] values, long spacing) {
        DoubleFunction<Graph> graph = v -> held(raw.apply(v));
        boolean tempo = name.startsWith("tempo");
        double[] carried = score(graph, values, spacing, tempo, "trial");
        double[] plain = score(graph, values, spacing, tempo, null);
        System.out.println(String.format(Locale.ROOT, "%s | %+.1f (%d) at %.1f ms | %+.1f at %.1f ms",
                name, carried[0], (long) carried[1], carried[2], plain[0], plain[2]));
    }

    /** {worst switch window over steady, in dB; carries; worst window's start in ms after its switch}. */
    private static double[] score(DoubleFunction<Graph> graph, double[] values, long spacing, boolean tempo, Object session) {
        int edits = values.length - 1;
        double bpm = tempo ? values[0] : 120;
        SessionState state = new SessionState(1, 0, 0, bpm, true, graph.apply(values[0]));
        LiveRenderer renderer = new LiveRenderer();
        var timeline = timeline(state);
        renderer.publish(timeline, session);
        List<float[]> audio = new ArrayList<>();
        long now = render(renderer, timeline, 0, SETTLE, audio);
        List<Long> switches = new ArrayList<>();
        for (int e = 0; e < edits; e++) {
            double value = values[e + 1];
            state = new SessionState(state.revision() + 1, now, state.cycleAt(now), tempo ? value : bpm, true, graph.apply(value));
            timeline = timeline(state);
            timeline.prepare(now);
            renderer.publish(timeline, session);
            switches.add(now);
            long until = e == edits - 1 ? now + TAIL : now + spacing;
            now = render(renderer, timeline, now, until, audio);
        }
        double[] highs = highPassEnergy(concat(audio));
        int first = frame(switches.getFirst()), last = frame(switches.getLast());
        // Windows start up to the bound, so the one before the first switch must end before it
        double steady = Math.max(loudest(highs, first - RATE, first - WINDOW), loudest(highs, last + frame(TAIL) - RATE, last + frame(TAIL)));
        double worst = 0, worstAt = 0;
        for (long at : switches) {
            double[] found = loudestAt(highs, frame(at), frame(at) + frame(AFTER));
            if (found[0] > worst) { worst = found[0]; worstAt = (found[1] - frame(at)) * 1e3 / RATE; }
        }
        return new double[] {10 * Math.log10(Math.max(worst, 1e-30) / Math.max(steady, 1e-30)), renderer.effectTransfers(), worstAt};
    }

    /** Per-frame energy above about 6 kHz: two cascaded high-pass biquads (4th-order Butterworth), both channels. */
    private static double[] highPassEnergy(float[] stereo) {
        int frames = stereo.length / 2;
        double[] energy = new double[frames];
        for (int c = 0; c < 2; c++) {
            Biquad a = new Biquad(), b = new Biquad();
            a.set(Biquad.Mode.HIGH_PASS, 6000, 0.5412, RATE);
            b.set(Biquad.Mode.HIGH_PASS, 6000, 1.3066, RATE);
            for (int f = 0; f < frames; f++) {
                double y = b.process(a.process(stereo[f * 2 + c]));
                energy[f] += y * y;
            }
        }
        return energy;
    }

    /** The largest mean energy of any 5 ms window starting in [from, to). */
    private static double loudest(double[] energy, int from, int to) { return loudestAt(energy, from, to)[0]; }

    /** {largest mean energy, its window's start frame}. */
    private static double[] loudestAt(double[] energy, int from, int to) {
        double best = 0, bestAt = from;
        for (int start = Math.max(0, from); start + WINDOW <= Math.min(energy.length, to + WINDOW); start += WINDOW / 4) {
            double sum = 0;
            for (int f = start; f < start + WINDOW; f++) sum += energy[f];
            if (sum / WINDOW > best) { best = sum / WINDOW; bestAt = start; }
        }
        return new double[] {best, bestAt};
    }

    private static int frame(long nanos) { return (int) Math.round(nanos * RATE / 1e9); }

    private static LiveRenderer.Timeline timeline(SessionState state) {
        return new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(state.graph())), null);
    }

    private static long render(LiveRenderer renderer, LiveRenderer.Timeline timeline, long from, long to, List<float[]> into) {
        int frames = frame(to - from);
        float[] out = new float[frames * 2], block = new float[BLOCK * 2];
        for (int at = 0; at < frames; at += BLOCK) {
            int n = Math.min(BLOCK, frames - at);
            long now = from + Math.round(at * 1e9 / RATE);
            timeline.prepare(now);
            renderer.render(block, n, now);
            System.arraycopy(block, 0, out, at * 2, n * 2);
        }
        into.add(out);
        return from + Math.round(frames * 1e9 / RATE);
    }

    private static float[] concat(List<float[]> parts) {
        int length = 0;
        for (float[] p : parts) length += p.length;
        float[] all = new float[length];
        int at = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, all, at, p.length); at += p.length; }
        return all;
    }
}
