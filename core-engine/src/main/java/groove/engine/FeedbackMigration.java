package groove.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps patches saved before the loop-gain limit loading and sounding the same. Every delay in
 * a loop over the limit is marked free-running, which only changes validation, never the audio.
 */
public final class FeedbackMigration {
    private FeedbackMigration() {}

    /** The migrated graph (the same instance when nothing changed) and the delays it marked. */
    public record Result(Graph graph, List<String> freeRunDelays) {
        public boolean changed() { return !freeRunDelays.isEmpty(); }
    }

    /** Never throws: a graph the analysis can't read is returned unchanged for compile to judge. */
    public static Result markFreeRunning(Graph graph) {
        Set<String> marked = new LinkedHashSet<>();
        try {
            for (SignalGraph.FeedbackLoop loop : SignalGraph.feedbackLoops(graph.nodes(), graph.edges()))
                if (loop.overLimit()) marked.addAll(loop.delays());
        } catch (RuntimeException unreadable) {
            return new Result(graph, List.of());
        }
        if (marked.isEmpty()) return new Result(graph, List.of());
        List<Graph.Node> nodes = new ArrayList<>(graph.nodes().size());
        for (Graph.Node node : graph.nodes()) {
            if (!marked.contains(node.id())) { nodes.add(node); continue; }
            Map<String, Double> params = new LinkedHashMap<>(node.params());
            params.put(NodeParam.FREE_RUN, 1.0);
            nodes.add(new Graph.Node(node.id(), node.type(), params, node.sample(), node.birthNanos()));
        }
        return new Result(new Graph(graph.version(), nodes, graph.edges()), List.copyOf(marked));
    }
}
