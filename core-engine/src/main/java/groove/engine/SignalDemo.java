package groove.engine;

import java.util.List;
import java.util.Map;

/** Slow shared-clock filter sweep with quiet, 250 ms feedback echoes. */
public final class SignalDemo {
    private SignalDemo() {}
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
