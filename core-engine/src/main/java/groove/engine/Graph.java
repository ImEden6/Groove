package groove.engine;

import java.util.List;
import java.util.Map;

/** Versioned musical graph; editor layout deliberately lives outside this model. */
public record Graph(int version, List<Node> nodes, List<Edge> edges) {
    public Graph { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
    public record Node(String id, String type, Map<String, Double> params, groove.engine.samples.AssetRef sample) {
        public Node(String id, String type, Map<String, Double> params) { this(id, type, params, null); }
        public Node { params = Map.copyOf(params); }
    }
    public record Edge(String fromNode, String fromPort, String toNode, String toPort) {}

    public static Graph demo() {
        return new Graph(1, List.of(
                new Node("bass", "tone", Map.of("frequency", 65.406, "gain", .5)),
                new Node("bassRhythm", "euclid", Map.of("steps", 8.0, "pulses", 4.0)),
                new Node("lead", "tone", Map.of("frequency", 261.626, "gain", .12, "wave", 1.0, "pan", -.5, "cutoffHz", 900.0)),
                new Node("leadRhythm", "euclid", Map.of("steps", 16.0, "pulses", 5.0)),
                new Node("mix", "stack", Map.of()), new Node("out", "output", Map.of())),
                List.of(edge("bass", "bassRhythm"), edge("lead", "leadRhythm"),
                        edge("bassRhythm", "mix"), edge("leadRhythm", "mix"), edge("mix", "out")));
    }
    public static Edge edge(String from, String to) { return new Edge(from, "out", to, "in"); }
}
