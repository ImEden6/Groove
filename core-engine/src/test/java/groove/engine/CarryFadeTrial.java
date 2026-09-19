package groove.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Report only: clicks around carried switches at different crossfade lengths. The score is the
 * largest second difference in the 20 ms after a switch over the largest in the 20 ms before it,
 * so a smooth change stays near 1 and a click jumps well above it. Run with
 * {@code java -cp <core-engine test and main classes> groove.engine.CarryFadeTrial}.
 */
public final class CarryFadeTrial {
    private static final int RATE = LiveRenderer.SAMPLE_RATE, BLOCK = 512;
    private static final long SWITCH = Math.round(3e9);
    private static final int[] FADES = {1, 64, 240};

    public static void main(String[] args) {
        System.out.println("Scenario | carried fade 1 | carried fade 64 | carried fade 240 | not carried (click score, carries)");
        report("tone gain 0.1 -> 0.8, sine into feedback delay",
                sine(0.1, 0.5, true, 300, 2.0), sine(0.8, 0.5, true, 300, 2.0), 1);
        report("filter cutoff 300 -> 12000 Hz, Q 2, saw into reverb",
                sawFilter(300), sawFilter(12000), 1);
        report("loop mix gain 0.2 -> 0.9, sine into feedback delay",
                sine(0.3, 0.2, true, 300, 2.0), sine(0.3, 0.9, true, 300, 2.0), 1);
        report("sustained sine, reverb decay 2 -> 8 s only",
                sine(0.3, 0.5, false, 300, 2.0), sine(0.3, 0.5, false, 300, 8.0), 1);
        report("20 rapid edits 20 ms apart, sine gain 0.3 <-> 0.6 into reverb",
                sine(0.3, 0.5, false, 300, 2.0), sine(0.6, 0.5, false, 300, 2.0), 20);
        LiveRenderer.carriedFadeFrames = 240;
    }

    /** Sine tone at toneGain; into a bus(mix)/delay loop, or into a reverb with the given decay. */
    private static Graph sine(double toneGain, double mix, boolean loop, double freq, double decay) {
        List<Graph.Node> nodes = new ArrayList<>(List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.GAIN, toneGain, NodeParam.FREQUENCY, freq, NodeParam.WAVE, 0.0)),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()), new Graph.Node("out", NodeType.OUTPUT, Map.of())));
        List<Graph.Edge> edges = new ArrayList<>(List.of(Graph.edge("tone", "render")));
        if (loop) {
            nodes.add(new Graph.Node("bus", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, mix)));
            nodes.add(new Graph.Node("echo", NodeType.DELAY, Map.of(NodeParam.FRAMES, 2400.0)));
            edges.addAll(List.of(Graph.edge("render", "bus"), Graph.edge("bus", "echo"), Graph.edge("echo", "bus"),
                    new Graph.Edge("bus", "out", "out", "audio")));
        } else {
            nodes.add(new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, decay)));
            nodes.add(new Graph.Node("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.7)));
            edges.addAll(List.of(Graph.edge("render", "rev"), Graph.edge("render", "mix"), Graph.edge("rev", "mix"),
                    new Graph.Edge("mix", "out", "out", "audio")));
        }
        return new Graph(3, nodes, edges);
    }

    private static Graph sawFilter(double cutoff) {
        return new Graph(3, List.of(
                new Graph.Node("tone", NodeType.TONE, Map.of(NodeParam.GAIN, 0.3, NodeParam.FREQUENCY, 110.0, NodeParam.WAVE, 1.0)),
                new Graph.Node("render", NodeType.AUDIO_RENDER, Map.of()),
                new Graph.Node("lp", NodeType.FILTER, Map.of(NodeParam.CUTOFF_HZ, cutoff, NodeParam.RESONANCE_Q, 2.0)),
                new Graph.Node("rev", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 2.0)),
                new Graph.Node("mix", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 0.7)),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("tone", "render"), Graph.edge("render", "lp"), Graph.edge("lp", "rev"), Graph.edge("lp", "mix"),
                        Graph.edge("rev", "mix"), new Graph.Edge("mix", "out", "out", "audio")));
    }

    private static void report(String name, Graph before, Graph after, int edits) {
        StringBuilder line = new StringBuilder(name);
        for (int fade : FADES) {
            LiveRenderer.carriedFadeFrames = fade;
            double[] result = score(before, after, edits, "trial");
            line.append(String.format(Locale.ROOT, " | %.2f (%d)", result[0], (long) result[1]));
        }
        double[] uncarried = score(before, after, edits, null);
        line.append(String.format(Locale.ROOT, " | %.2f (%d)", uncarried[0], (long) uncarried[1]));
        System.out.println(line);
    }

    /** {worst click score over the edits, carries}. Edits alternate between the two graphs. */
    private static double[] score(Graph before, Graph after, int edits, Object session) {
        SessionState state = new SessionState(1, 0, 0, 120, true, before);
        LiveRenderer renderer = new LiveRenderer();
        var timeline = timeline(state);
        renderer.publish(timeline, session);
        long now = 0;
        List<float[]> audio = new ArrayList<>();
        now = render(renderer, timeline, now, SWITCH, audio);
        List<Long> switches = new ArrayList<>();
        for (int e = 0; e < edits; e++) {
            state = new SessionState(state.revision() + 1, now, state.cycleAt(now), 120, true, e % 2 == 0 ? after : before);
            timeline = timeline(state);
            timeline.prepare(now);
            renderer.publish(timeline, session);
            switches.add(now);
            now = render(renderer, timeline, now, now + Math.round(0.02e9), audio);
        }
        render(renderer, timeline, now, now + Math.round(0.2e9), audio);
        float[] all = concat(audio);
        int window = RATE / 50;
        int firstSwitch = frame(switches.getFirst());
        double steady = maxSecondDifference(all, firstSwitch - window, firstSwitch);
        double worst = 0;
        for (long at : switches) worst = Math.max(worst, maxSecondDifference(all, frame(at), frame(at) + window));
        return new double[] {worst / steady, renderer.effectTransfers()};
    }

    private static int frame(long nanos) { return (int) Math.round(nanos * RATE / 1e9); }

    private static double maxSecondDifference(float[] stereo, int from, int to) {
        double max = 0;
        for (int f = Math.max(2, from); f < to; f++)
            for (int c = 0; c < 2; c++)
                max = Math.max(max, Math.abs(stereo[f * 2 + c] - 2.0 * stereo[(f - 1) * 2 + c] + stereo[(f - 2) * 2 + c]));
        return max;
    }

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
