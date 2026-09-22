package groove.engine;

import groove.engine.samples.AssetRef;
import groove.engine.samples.FactorySamples;
import groove.engine.samples.WavDecoder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chopped break, a wandering bass and a lead that arrives with the rain, in D minor pentatonic at 96 BPM.
 * Uses the world node, quantize and a controlled slice index together.
 */
public final class ShowcaseDemo {
    public static final double BPM = 96;
    /** Order the break's eight slices play in: straight, then a kick repeat and a snare roll. */
    private static final int[] CHOP = {0, 1, 2, 3, 0, 5, 6, 6};

    private ShowcaseDemo() {}

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args.length == 0 ? "showcase.wav" : args[0]);
        double seconds = args.length > 1 ? Double.parseDouble(args[1]) : 60;
        Demo.writeWav(path, render(seconds), LiveRenderer.SAMPLE_RATE);
    }

    public static Graph graph() {
        List<Graph.Node> nodes = new ArrayList<>();
        List<Graph.Edge> edges = new ArrayList<>();
        AssetRef drums = FactorySamples.ref(FactorySamples.BREAK);

        // Drums: the break chopped into eighths, darker as night falls
        nodes.add(new Graph.Node("break", NodeType.GENERATOR_SAMPLE, Map.of(NodeParam.GAIN, .6), drums, null));
        nodes.add(node("eighths", NodeType.FAST, Map.of(NodeParam.FACTOR, 8.0)));
        nodes.add(node("chop", NodeType.SAMPLE_SLICE, Map.of(NodeParam.SLICES, 8.0, NodeParam.INDEX, 0.0)));
        Map<String, Double> order = new HashMap<>(Map.of(NodeParam.STEPS, 8.0, NodeParam.RATE, 1.0));
        for (int i = 0; i < 8; i++) order.put(NodeParam.VALUES[i], (CHOP[i] + .5) / 8);
        nodes.add(node("chopOrder", NodeType.STEP_SEQUENCE, order));
        nodes.add(node("drums", NodeType.AUDIO_RENDER, Map.of()));
        nodes.add(node("daylight", NodeType.WORLD, Map.of(NodeParam.SOURCE, (double) WorldInputs.DAYLIGHT, NodeParam.SMOOTH, 3.0)));
        nodes.add(node("dayTone", NodeType.ATTENUVERTER, Map.of(NodeParam.SCALE, 6500.0, NodeParam.OFFSET, 1800.0)));
        nodes.add(node("drumTone", NodeType.FILTER, Map.of(NodeParam.RESONANCE_Q, .9)));
        edges.addAll(List.of(Graph.edge("break", "eighths"), Graph.edge("eighths", "chop"), Graph.edge("chop", "drums"),
                new Graph.Edge("chopOrder", "out", "chop", "index"), Graph.edge("drums", "drumTone"),
                Graph.edge("daylight", "dayTone"), new Graph.Edge("dayTone", "out", "drumTone", "cutoff")));

        // Bass: a slow triangle walks the lower octave, one note per euclidean hit
        nodes.add(node("bassTone", NodeType.TONE, Map.of(NodeParam.WAVE, 1.0, NodeParam.GAIN, .2, NodeParam.CUTOFF_HZ, 700.0, NodeParam.RESONANCE_Q, 2.0)));
        nodes.add(node("bassRhythm", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 6.0, NodeParam.ROTATION, 2.0)));
        nodes.add(node("bassNotes", NodeType.QUANTIZE, scale(38, 0, 5)));
        nodes.add(node("bassWalk", NodeType.LFO, Map.of(NodeParam.SYNC, 1.0, NodeParam.RATE, .25, NodeParam.WAVE, 1.0)));
        nodes.add(node("bassRange", NodeType.ATTENUVERTER, Map.of(NodeParam.SCALE, .5, NodeParam.OFFSET, .5)));
        nodes.add(node("bass", NodeType.AUDIO_RENDER, Map.of()));
        edges.addAll(List.of(Graph.edge("bassTone", "bassRhythm"), Graph.edge("bassRhythm", "bassNotes"), Graph.edge("bassNotes", "bass"),
                Graph.edge("bassWalk", "bassRange"), new Graph.Edge("bassRange", "out", "bassNotes", "degree")));

        // Lead: a free-running sine picks notes two octaves up; it only comes forward in the rain
        nodes.add(node("leadTone", NodeType.TONE, Map.of(NodeParam.WAVE, 2.0, NodeParam.PULSE_WIDTH, .3, NodeParam.GAIN, .16, NodeParam.CUTOFF_HZ, 3500.0, NodeParam.PAN, .3)));
        nodes.add(node("leadRhythm", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 5.0, NodeParam.ROTATION, 3.0)));
        nodes.add(node("leadNotes", NodeType.QUANTIZE, scale(62, 0, 7)));
        nodes.add(node("leadMotion", NodeType.LFO, Map.of(NodeParam.RATE, .31)));
        nodes.add(node("leadRange", NodeType.ATTENUVERTER, Map.of(NodeParam.SCALE, .5, NodeParam.OFFSET, .5)));
        nodes.add(node("lead", NodeType.AUDIO_RENDER, Map.of()));
        nodes.add(node("rain", NodeType.WORLD, Map.of(NodeParam.SOURCE, (double) WorldInputs.RAIN, NodeParam.SMOOTH, 4.0)));
        nodes.add(node("rainLevel", NodeType.ATTENUVERTER, Map.of(NodeParam.SCALE, .85, NodeParam.OFFSET, .15)));
        nodes.add(node("leadLevel", NodeType.MIX_BUS, Map.of()));
        edges.addAll(List.of(Graph.edge("leadTone", "leadRhythm"), Graph.edge("leadRhythm", "leadNotes"), Graph.edge("leadNotes", "lead"),
                Graph.edge("leadMotion", "leadRange"), new Graph.Edge("leadRange", "out", "leadNotes", "degree"),
                Graph.edge("lead", "leadLevel"), Graph.edge("rain", "rainLevel"), new Graph.Edge("rainLevel", "out", "leadLevel", "gain")));

        // Dotted-eighth echoes under the dry lead, then one room for the lead and a little of the drums
        nodes.add(node("echoes", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .4)));
        nodes.add(node("echo", NodeType.DELAY, Map.of(NodeParam.SYNC, 1.0, NodeParam.DIVISION, 4.0)));
        nodes.add(node("send", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, .5)));
        nodes.add(node("room", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 3.2, NodeParam.DAMPING_HZ, 5000.0, NodeParam.PRE_DELAY_MS, 20.0)));
        nodes.add(node("master", NodeType.MIX_BUS, Map.of(NodeParam.GAIN, 1.0)));
        nodes.add(node("out", NodeType.OUTPUT, Map.of()));
        edges.addAll(List.of(Graph.edge("leadLevel", "echoes"), Graph.edge("echo", "echoes"), Graph.edge("echoes", "echo"),
                Graph.edge("echoes", "send"), Graph.edge("drumTone", "send"), Graph.edge("send", "room"),
                Graph.edge("drumTone", "master"), Graph.edge("bass", "master"), Graph.edge("leadLevel", "master"),
                Graph.edge("echoes", "master"), Graph.edge("room", "master"),
                new Graph.Edge("master", "out", "out", "audio")));
        return new Graph(3, nodes, edges);
    }

    /** Afternoon into night, with rain rolling in over the middle of the piece. */
    static double[] weather(double seconds, double length) {
        double[] values = new double[WorldInputs.COUNT];
        java.util.Arrays.fill(values, Double.NaN);
        double at = seconds / length;
        values[WorldInputs.DAYLIGHT] = 1 - smooth((at - .15) / .7);
        values[WorldInputs.RAIN] = smooth((at - .25) / .25) - smooth((at - .8) / .2);
        return values;
    }

    private static double smooth(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    static float[] render(double seconds) { return render(graph(), seconds); }

    static float[] render(Graph graph, double seconds) {
        var state = new SessionState(1, 0, 0, BPM, true, graph);
        var bank = Map.of(FactorySamples.ref(FactorySamples.BREAK), WavDecoder.decode(FactorySamples.bytes(FactorySamples.BREAK)));
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(graph), bank), null);
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(timeline);
        int total = (int) Math.round(seconds * LiveRenderer.SAMPLE_RATE);
        float[] output = new float[total * 2], block = new float[1024];
        for (int at = 0; at < total; at += 512) {
            int frames = Math.min(512, total - at);
            long now = Math.round(at * 1e9 / LiveRenderer.SAMPLE_RATE);
            // The game updates world values once a tick; every block is finer, and the node smooths either way
            renderer.world().update(weather(at / (double) LiveRenderer.SAMPLE_RATE, seconds));
            timeline.prepare(now);
            renderer.render(block, frames, now);
            System.arraycopy(block, 0, output, at * 2, frames * 2);
        }
        return output;
    }

    private static Map<String, Double> scale(int root, int low, int high) {
        return Map.of(NodeParam.ROOT, (double) root, NodeParam.SCALE, (double) Pitch.Scale.MINOR_PENTATONIC.ordinal(),
                NodeParam.LOW, (double) low, NodeParam.HIGH, (double) high);
    }

    private static Graph.Node node(String id, NodeType type, Map<String, Double> params) { return new Graph.Node(id, type, params); }
}
