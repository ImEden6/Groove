package groove.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PortTests {
    static void run() {
        for (PortType input : PortType.values()) for (PortType output : PortType.values()) {
            check(new Port("in", input, 1, 1).accepts(new Port("out", output, 0, 128)) == (input == output),
                    "Only matching signal domains connect");
        }
        for (Graph old : List.of(Graph.demo(), groove.engine.samples.FactorySamples.demo())) {
            Graph migrated = old.toV3();
            check(migrated.version() == 3 && migrated.toV3() == migrated, "Idempotent migration");
            check(old.nodes().equals(migrated.nodes()) && old.edges().equals(migrated.edges()), "Migration preserves IDs, order, assets and parameters");
            LoopPlan before = GraphCompiler.compile(old), after = GraphCompiler.compile(migrated);
            check(before.size() == after.size(), "Migration preserves event count");
            for (int i = 0; i < before.size(); i++) check(before.event(i).equals(after.event(i)), "Migration preserves events");
            for (int cycle = -2; cycle < 8; cycle++)
                check(before.pattern().query(new Arc(cycle + .25, cycle + 1.25))
                        .equals(after.pattern().query(new Arc(cycle + .25, cycle + 1.25))), "Migration preserves continuous queries");
            var bad = new ArrayList<>(migrated.edges());
            Graph.Edge first = bad.getFirst();
            bad.set(0, new Graph.Edge(first.fromNode(), "audio", first.toNode(), first.toPort()));
            invalid(() -> GraphCompiler.compile(new Graph(3, migrated.nodes(), bad)));
            bad.set(0, new Graph.Edge(first.fromNode(), "in", first.toNode(), first.toPort()));
            invalid(() -> GraphCompiler.compile(new Graph(3, migrated.nodes(), bad)));
        }
        Graph sample = groove.engine.samples.FactorySamples.demo();
        invalid(() -> new Graph(1, sample.nodes(), sample.edges()).toV3());
        invalid(() -> new Graph(4, Graph.demo().nodes(), Graph.demo().edges()).toV3());
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(
                new Graph.Node("a", NodeType.TONE, Map.of()), new Graph.Node("b", NodeType.TONE, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("a", "b"), Graph.edge("b", "out")))));
        invalid(() -> GraphCompiler.compile(new Graph(3, List.of(
                new Graph.Node("a", NodeType.FAST, Map.of()), new Graph.Node("b", NodeType.FAST, Map.of()),
                new Graph.Node("out", NodeType.OUTPUT, Map.of())),
                List.of(Graph.edge("a", "b"), Graph.edge("b", "a"), Graph.edge("b", "out")))));
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid graph");
    }
}
