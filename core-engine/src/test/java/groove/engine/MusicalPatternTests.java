package groove.engine;

import java.util.*;

/** Musical structure must survive arbitrary scheduler windows and replay. */
public final class MusicalPatternTests {
    private static final Tone A = new Tone(Tone.Wave.SINE, 220, .3, 0, 20000);
    private static final Tone B = new Tone(Tone.Wave.SINE, 330, .3, 0, 20000);
    private static final Tone C = new Tone(Tone.Wave.SINE, 440, .3, 0, 20000);
    public static void run() {
        Pattern a = Pattern.tone(A), b = Pattern.tone(B), c = Pattern.tone(C);
        Pattern alt = Pattern.alternate(a, b, c);
        check(alt.query(new Arc(-1, 4)).stream().map(Event::tone).toList().equals(List.of(C,A,B,C,A)), "Alternation wraps negative and positive cycles");
        Pattern nested = Pattern.alternate(Pattern.alternate(a,b), c);
        check(nested.query(new Arc(0,4)).stream().map(Event::tone).toList().equals(List.of(A,C,B,C)), "Children advance on their own cycles");
        Pattern poly = Pattern.polymeter(2, a,b,c);
        check(poly.query(new Arc(0,3)).stream().map(Event::tone).toList().equals(List.of(A,B,C,A,B,C)), "Three steps phase across two-pulse bars");
        check(poly.query(new Arc(1,2)).getFirst().whole().equals(new Arc(1,1.5)), "Polymeter preserves step duration");
        Pattern random = a.fast(8).probability(.5, 42);
        List<Event> events = random.query(new Arc(-8,8));
        check(events.size() > 30 && events.size() < 100, "Probability drops some events");
        check(events.equals(random.query(new Arc(-8,8))), "Replay is deterministic");
        check(!events.equals(a.fast(8).probability(.5,43).query(new Arc(-8,8))), "Seed changes choices");
        check(a.probability(0,0).query(new Arc(0,8)).isEmpty(), "Zero chance is silent");
        check(a.probability(1,0).query(new Arc(0,8)).equals(a.query(new Arc(0,8))), "Unit chance is transparent");
        for (Pattern pattern : List.of(alt, nested, poly, random, Pattern.alternate(random,b), poly.probability(.4,7))) {
            Arc window = new Arc(-2,5);
            List<Event> full = pattern.query(window);
            for (int i=0;i<112;i++) {
                Arc part = new Arc(-2+i/16.0,-2+(i+1)/16.0);
                List<Event> expected = new ArrayList<>();
                for (Event e : full) {
                    Arc clipped = e.part().intersect(part);
                    if (clipped != null) expected.add(new Event(e.whole(),clipped,e.tone(),e.sample()));
                }
                check(pattern.query(part).equals(expected), "Partitioned query preserves whole arcs and choices");
            }
            check(pattern.query(new Arc(.5,.5)).isEmpty(), "Empty window");
        }
        invalid(() -> Pattern.alternate());
        invalid(() -> Pattern.polymeter(0,a));
        invalid(() -> a.probability(Double.NaN,0));
        invalid(() -> a.probability(1.1,0));
        for (NodeType type : List.of(NodeType.ALTERNATE,NodeType.PROBABILITY,NodeType.POLYMETER)) {
            Graph graph = graph(type, Map.of());
            LoopPlan plan = GraphCompiler.compile(graph);
            check(plan.pattern().query(new Arc(4,5)) != null, "Later cycles compile");
            List<Graph.Node> nodes = new ArrayList<>(graph.nodes());
            nodes.set(nodes.size()-1,new Graph.Node("out",NodeType.AUDIO_RENDER,Map.of()));
            nodes.add(new Graph.Node("sink",NodeType.OUTPUT,Map.of()));
            List<Graph.Edge> edges = new ArrayList<>(graph.edges());
            edges.add(new Graph.Edge("out","out","sink","audio"));
            check(GraphCompiler.compile(new Graph(3,nodes,edges)).signals().sourceCount()==1, "Signal routing accepts musical patterns");
        }
        invalid(() -> GraphCompiler.compile(graph(NodeType.PROBABILITY,Map.of("seed",.5))));
        invalid(() -> GraphCompiler.compile(graph(NodeType.PROBABILITY,Map.of("chance",-1.0))));
        invalid(() -> GraphCompiler.compile(graph(NodeType.PROBABILITY,Map.of("chance",1.5))));
        invalid(() -> GraphCompiler.compile(graph(NodeType.POLYMETER,Map.of("stepsPerCycle",65.0))));
        // A quiet first branch cannot hide a later branch's cost from the compiler.
        Graph costly = new Graph(3,List.of(new Graph.Node("a",NodeType.TONE,Map.of()),
            new Graph.Node("fast",NodeType.FAST,Map.of("factor",16.0)),
            new Graph.Node("seq",NodeType.POLYMETER,Map.of("stepsPerCycle",16.0)),
            new Graph.Node("out",NodeType.OUTPUT,Map.of())),
            List.of(Graph.edge("a","fast"),Graph.edge("a","seq"),Graph.edge("fast","seq"),Graph.edge("seq","out")));
        invalid(() -> GraphCompiler.compile(costly));
        System.out.println("Musical pattern tests passed");
    }
    private static Graph graph(NodeType type, Map<String,Double> params) {
        return new Graph(3,List.of(new Graph.Node("a",NodeType.TONE,Map.of()),
            new Graph.Node("pattern",type,params),new Graph.Node("out",NodeType.OUTPUT,Map.of())),
            List.of(Graph.edge("a","pattern"),Graph.edge("pattern","out")));
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
}
