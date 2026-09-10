package groove.engine;

import java.util.List;
import java.util.Map;

/** Versioned musical graph; editor layout deliberately lives outside this model. */
public record Graph(int version, List<Node> nodes, List<Edge> edges) {
    public Graph { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
    public record Node(String id, NodeType type, Map<String, Double> params, groove.engine.samples.AssetRef sample) {
        public Node(String id, NodeType type, Map<String, Double> params) { this(id, type, params, null); }
        public Node { params = Map.copyOf(params); }
    }
    public record Edge(String fromNode, String fromPort, String toNode, String toPort) {}

    public static Graph demo() {
        return new Graph(1, List.of(
                new Node("bass", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 65.406, NodeParam.GAIN, .5)),
                new Node("bassRhythm", NodeType.EUCLID, Map.of(NodeParam.STEPS, 8.0, NodeParam.PULSES, 4.0)),
                new Node("lead", NodeType.TONE, Map.of(NodeParam.FREQUENCY, 261.626, NodeParam.GAIN, .12, NodeParam.WAVE, 1.0, NodeParam.PAN, -.5, NodeParam.CUTOFF_HZ, 900.0)),
                new Node("leadRhythm", NodeType.EUCLID, Map.of(NodeParam.STEPS, 16.0, NodeParam.PULSES, 5.0)),
                new Node("mix", NodeType.STACK, Map.of()), new Node("out", NodeType.OUTPUT, Map.of())),
                List.of(edge("bass", "bassRhythm"), edge("lead", "leadRhythm"),
                        edge("bassRhythm", "mix"), edge("leadRhythm", "mix"), edge("mix", "out")));
    }
    public static Edge edge(String from, String to) { return new Edge(from, "out", to, "in"); }
}
