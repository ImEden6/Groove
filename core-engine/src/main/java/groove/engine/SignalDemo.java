package groove.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Slow shared-clock filter sweep with quiet, 250 ms feedback echoes. */
public final class SignalDemo {
    private SignalDemo() {}
    public static final int TOTAL_FRAMES = LiveRenderer.SAMPLE_RATE * 8;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args.length == 0 ? "signal-demo.wav" : args[0]);
        Demo.writeWav(path, render(reverbSources(), TOTAL_FRAMES), LiveRenderer.SAMPLE_RATE);
    }

    /** Renders a sample-free graph live from time zero, preparing lookahead each block as the worker would. */
    static float[] render(Graph graph, int totalFrames) {
        var state = new SessionState(1, 0, 0, 120, true, graph);
        var timeline = new LiveRenderer.Timeline(new LiveRenderer.Program(state, GraphCompiler.compile(graph)), null);
        LiveRenderer renderer = new LiveRenderer();
        renderer.publish(timeline);
        float[] output = new float[totalFrames * 2], block = new float[1024];
        for (int at = 0; at < totalFrames; at += 512) {
            int frames = Math.min(512, totalFrames - at);
            long now = Math.round(at * 1e9 / LiveRenderer.SAMPLE_RATE);
            timeline.prepare(now);
            renderer.render(block, frames, now);
            System.arraycopy(block, 0, output, at * 2, frames * 2);
        }
        return output;
    }

    /** multipleSources() with a reverb on the filtered bass and its echoes, outside the delay loop; the lead stays dry. */
    public static Graph reverbSources() {
        Graph dry = multipleSources();
        var nodes = new java.util.ArrayList<>(dry.nodes());
        nodes.add(new Graph.Node("space", NodeType.REVERB, Map.of(NodeParam.DECAY_SECONDS, 1.8, NodeParam.DAMPING_HZ, 6000.0)));
        var edges = new java.util.ArrayList<>(dry.edges());
        edges.add(Graph.edge("mix", "space"));
        edges.add(Graph.edge("space", "master"));
        return new Graph(3, nodes, edges);
    }
    /** Independent dry lead alongside the filtered bass/feedback source. */
    public static Graph multipleSources() {
        Graph bass = graph();
        var nodes = new java.util.ArrayList<>(bass.nodes());
        nodes.add(new Graph.Node("lead", NodeType.TONE, Map.of(NodeParam.FREQUENCY,330.0,NodeParam.GAIN,.08,NodeParam.PAN,.7)));
        nodes.add(new Graph.Node("leadRhythm", NodeType.EUCLID, Map.of(NodeParam.STEPS,8.0,NodeParam.PULSES,5.0)));
        nodes.add(new Graph.Node("leadRender", NodeType.AUDIO_RENDER, Map.of()));
        nodes.add(new Graph.Node("master", NodeType.MIX_BUS, Map.of(NodeParam.GAIN,.8)));
        var edges = new java.util.ArrayList<>(bass.edges());
        edges.remove(new Graph.Edge("mix","out","out","audio"));
        edges.add(Graph.edge("lead","leadRhythm")); edges.add(Graph.edge("leadRhythm","leadRender"));
        edges.add(Graph.edge("mix","master")); edges.add(Graph.edge("leadRender","master"));
        edges.add(new Graph.Edge("master","out","out","audio"));
        return new Graph(3,nodes,edges);
    }
    public static Graph graph() {
        return new Graph(3, List.of(
                new Graph.Node("tone",NodeType.TONE,Map.of("frequency",110.0,"gain",.15,"wave",1.0)),
                new Graph.Node("rhythm",NodeType.EUCLID,Map.of("steps",8.0,"pulses",3.0)),
                new Graph.Node("render",NodeType.AUDIO_RENDER,Map.of()),
                new Graph.Node("lfo",NodeType.LFO,Map.of("rate",.2)),
                new Graph.Node("range",NodeType.ATTENUVERTER,Map.of("scale",1500.0,"offset",1800.0)),
                new Graph.Node("filter",NodeType.FILTER,Map.of("resonanceQ",1.5)),
                new Graph.Node("mix",NodeType.MIX_BUS,Map.of("gain",.65)),
                new Graph.Node("delay",NodeType.DELAY,Map.of(NodeParam.SYNC, 1.0, NodeParam.DIVISION, 2.0)),
                new Graph.Node("out",NodeType.OUTPUT,Map.of())), List.of(
                Graph.edge("tone","rhythm"),Graph.edge("rhythm","render"),Graph.edge("render","filter"),
                Graph.edge("lfo","range"),new Graph.Edge("range","out","filter","cutoff"),
                Graph.edge("filter","mix"),Graph.edge("delay","mix"),Graph.edge("mix","delay"),
                new Graph.Edge("mix","out","out","audio")));
    }
}
