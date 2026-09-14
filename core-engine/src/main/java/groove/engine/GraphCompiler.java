package groove.engine;

import java.util.*;

/** Strict, bounded v1/v2/v3 compiler. Run on a control/worker thread, never an audio callback. */
public final class GraphCompiler {
    public static final int MAX_NODES = 64, MAX_EVENTS = 128;
    private final Map<String, Graph.Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> inputs = new HashMap<>();
    private final Map<String, Compiled> compiled = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    private record Compiled(Pattern pattern, int cost) {}

    public static LoopPlan compile(Graph graph) {
        if (graph.nodes().stream().anyMatch(n -> n.type() != null && n.type().isSignalNode())) return SignalGraph.compile(graph);
        return new GraphCompiler().build(graph);
    }

    private LoopPlan build(Graph graph) {
        require(graph.version() >= 1 && graph.version() <= Graph.CURRENT_VERSION, "Unsupported graph version");
        require(!graph.nodes().isEmpty() && graph.nodes().size() <= MAX_NODES, "Expected 1..64 nodes");
        require(graph.edges().size() <= 128, "Too many edges");
        for (Graph.Node node : graph.nodes()) {
            require(node.id() != null && node.id().matches("[a-zA-Z0-9_-]{1,32}"), "Invalid node id");
            require(nodes.putIfAbsent(node.id(), node) == null, "Duplicate node id");
            require(node.type() != null, "Unknown node type");
            require(node.birthNanos() == null, "Birth timestamp is only valid on a v3 LFO");
            require(!node.type().isSignalNode(), "Signal node requires v3 routing");
            require(node.type() == NodeType.GENERATOR_SAMPLE ? graph.version() >= 2 && node.sample() != null : node.sample() == null,
                    "Sample reference requires a v2/v3 generator/sample node");
            require(node.params().size() <= 8, "Too many parameters");
            inputs.put(node.id(), new ArrayList<>());
        }
        Set<Graph.Edge> unique = new HashSet<>();
        for (Graph.Edge edge : graph.edges()) {
            require(nodes.containsKey(edge.fromNode()) && nodes.containsKey(edge.toNode()), "Dangling edge");
            Port source = nodes.get(edge.fromNode()).type().outputPort(edge.fromPort());
            Port target = nodes.get(edge.toNode()).type().inputPort(edge.toPort());
            require(source != null && target != null, "Unknown port or wrong port direction");
            require(target.accepts(source), "Incompatible port types");
            require(unique.add(edge), "Duplicate edge");
            inputs.get(edge.toNode()).add(edge.fromNode());
        }
        Port.validateArity(nodes.values(), graph.edges());
        var outputs = nodes.values().stream().filter(n -> n.type() == NodeType.OUTPUT).toList();
        require(outputs.size() == 1, "Exactly one output required");
        require(inputs.get(outputs.getFirst().id()).size() == 1, "Exactly one output input required");
        Compiled result = visit(outputs.getFirst().id(), 0);
        require(compiled.size() == nodes.size(), "Every node must reach the output");
        List<Event> events = result.pattern.query(new Arc(0, 1));
        require(events.size() <= MAX_EVENTS, "Too many events");
        events.sort(Comparator.comparingDouble(e -> e.whole().start()));
        return new LoopPlan(events, result.pattern, result.cost);
    }

    private Compiled visit(String id, int depth) {
        require(depth <= 16, "Graph nesting exceeds 16");
        if (compiled.containsKey(id)) return compiled.get(id);
        require(visiting.add(id), "Graph contains a cycle");
        Graph.Node node = nodes.get(id);
        List<String> links = inputs.get(id);
        Set<String> allowed = switch (node.type()) {
            case TONE -> Set.of(NodeParam.FREQUENCY, NodeParam.GAIN, NodeParam.PAN, NodeParam.WAVE, NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q);
            case GENERATOR_SAMPLE -> Set.of(NodeParam.PITCH_RATIO, NodeParam.GAIN, NodeParam.PAN, NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q);
            case FAST -> Set.of(NodeParam.FACTOR);
            case EUCLID -> Set.of(NodeParam.STEPS, NodeParam.PULSES, NodeParam.ROTATION);
            case STACK, ALTERNATE, OUTPUT -> Set.of();
            case PROBABILITY -> Set.of(NodeParam.CHANCE, NodeParam.SEED);
            case POLYMETER -> Set.of(NodeParam.STEPS_PER_CYCLE);
            default -> throw new IllegalStateException("unreachable: signal nodes are rejected in build()");
        };
        require(allowed.containsAll(node.params().keySet()), "Unknown parameter on " + id);
        for (Double value : node.params().values()) require(Double.isFinite(value), "Non-finite parameter");
        List<Compiled> children = new ArrayList<>();
        for (String link : links) children.add(visit(link, depth + 1));
        Compiled result = switch (node.type()) {
        case TONE -> {
            double frequency = number(node, NodeParam.FREQUENCY, 220);
            require(frequency >= 20 && frequency <= 16000, "Frequency must be 20..16000 Hz");
            int wave = integer(node, NodeParam.WAVE, 0, 0, 1);
            double cutoffHz = number(node, NodeParam.CUTOFF_HZ, 20000);
            require(cutoffHz >= 20 && cutoffHz <= 20000, "cutoffHz must be 20..20000 Hz");
            yield new Compiled(Pattern.tone(new Tone(Tone.Wave.values()[wave], frequency,
                    number(node, NodeParam.GAIN, .25), number(node, NodeParam.PAN, 0), cutoffHz, number(node, NodeParam.RESONANCE_Q, Biquad.DEFAULT_Q))), 1);
        }
        case GENERATOR_SAMPLE -> {
            yield new Compiled(Pattern.sample(new groove.engine.samples.SampleVoice(node.sample(),
                    number(node, NodeParam.PITCH_RATIO, 1), number(node, NodeParam.GAIN, .8), number(node, NodeParam.PAN, 0), number(node, NodeParam.CUTOFF_HZ, 20000),
                    number(node, NodeParam.RESONANCE_Q, Biquad.DEFAULT_Q))), 1);
        }
        case ALTERNATE, POLYMETER -> {
            int rate = node.type() == NodeType.POLYMETER ? integer(node, NodeParam.STEPS_PER_CYCLE, 4, 1, 64) : 1;
            int cost = children.stream().mapToInt(Compiled::cost).max().orElseThrow() * rate;
            require(cost <= MAX_EVENTS, "Graph exceeds event budget");
            Pattern[] patterns = children.stream().map(Compiled::pattern).toArray(Pattern[]::new);
            yield new Compiled(node.type() == NodeType.ALTERNATE ? Pattern.alternate(patterns) : Pattern.polymeter(rate, patterns), cost);
        }
        case PROBABILITY -> {
            Compiled child = children.getFirst();
            double chance = number(node, NodeParam.CHANCE, .5);
            require(chance >= 0 && chance <= 1, "Invalid chance");
            int seed = integer(node, NodeParam.SEED, 0, 0, 65535);
            yield new Compiled(child.pattern.probability(chance, seed), child.cost);
        }
        case STACK -> {
            int cost = children.stream().mapToInt(Compiled::cost).sum();
            require(cost <= MAX_EVENTS, "Graph exceeds event budget");
            yield new Compiled(Pattern.stack(children.stream().map(Compiled::pattern).toArray(Pattern[]::new)), cost);
        }
        case FAST -> {
            Compiled child = children.getFirst();
            double factor = number(node, NodeParam.FACTOR, 2);
            require(factor >= .25 && factor <= 16, "Invalid factor");
            require(child.cost * factor <= MAX_EVENTS, "Graph exceeds event budget");
            yield new Compiled(child.pattern.fast(factor), (int) Math.ceil(child.cost * Math.max(1, factor)));
        }
        case EUCLID -> {
            Compiled child = children.getFirst();
            int steps = integer(node, NodeParam.STEPS, 16, 1, 64);
            int pulses = integer(node, NodeParam.PULSES, 4, 0, steps);
            int rotation = integer(node, NodeParam.ROTATION, 0, -1024, 1024);
            require(child.cost * Math.max(1, pulses) <= MAX_EVENTS, "Graph exceeds event budget");
            yield new Compiled(child.pattern.euclid(steps, pulses, rotation), child.cost * pulses);
        }
        case OUTPUT -> children.getFirst();
        default -> throw new IllegalStateException("unreachable: signal nodes are rejected in build()");
        };
        visiting.remove(id);
        compiled.put(id, result);
        return result;
    }

    private static double number(Graph.Node n, String key, double fallback) { return n.params().getOrDefault(key, fallback); }
    private static int integer(Graph.Node n, String key, int fallback, int min, int max) {
        double value = number(n, key, fallback);
        require(value == Math.rint(value) && value >= min && value <= max, "Invalid " + key);
        return (int) value;
    }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
