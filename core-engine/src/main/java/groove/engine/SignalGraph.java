package groove.engine;

import java.util.*;

/** Immutable, bounded signal routing compiled on the control worker. One stereo pattern source. */
public final class SignalGraph {
    public static final int CONTROL_FRAMES = 64, MAX_DELAY_FRAMES = 48000, MAX_TOTAL_DELAY_FRAMES = 192000;
    final Graph.Node[] nodes;
    final int[][] audioInputs;
    final int[] controlInput, order;
    final int output;

    private SignalGraph(Graph graph, List<Integer> sorted, Map<String, Integer> ids, int output) {
        nodes = graph.nodes().toArray(Graph.Node[]::new);
        this.output = output;
        order = sorted.stream().mapToInt(Integer::intValue).toArray();
        audioInputs = new int[nodes.length][];
        controlInput = new int[nodes.length];
        Arrays.fill(controlInput, -1);
        for (int i = 0; i < nodes.length; i++) {
            List<Integer> audio = new ArrayList<>();
            for (Graph.Edge edge : graph.edges()) if (edge.toNode().equals(nodes[i].id())) {
                PortType type = nodes[i].type().inputPort(edge.toPort()).type();
                if (type == PortType.AUDIO) audio.add(ids.get(edge.fromNode()));
                if (type == PortType.MOD_FLOAT || type == PortType.TRIGGER) controlInput[i] = ids.get(edge.fromNode());
            }
            audioInputs[i] = audio.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    static LoopPlan compile(Graph graph) {
        require(graph.version() == 3, "Signal nodes require v3");
        require(!graph.nodes().isEmpty() && graph.nodes().size() <= GraphCompiler.MAX_NODES && graph.edges().size() <= 128, "Graph exceeds size budget");
        Map<String, Integer> ids = new LinkedHashMap<>();
        int output = -1, render = -1, delayFrames = 0;
        for (int i = 0; i < graph.nodes().size(); i++) {
            Graph.Node n = graph.nodes().get(i);
            require(n.id() != null && n.id().matches("[a-zA-Z0-9_-]{1,32}") && ids.putIfAbsent(n.id(), i) == null, "Invalid or duplicate node id");
            require(n.type() != null && n.params().size() <= 16, "Invalid node");
            require(n.type() == NodeType.GENERATOR_SAMPLE ? n.sample() != null : n.sample() == null, "Invalid sample reference");
            require(n.type() == NodeType.LFO || n.birthNanos() == null, "Birth timestamp is only valid on LFO");
            for (double value : n.params().values()) require(Double.isFinite(value), "Non-finite parameter");
            if (n.type().isSignalNode()) validateParams(n);
            if (n.type() == NodeType.OUTPUT) { require(output == -1 && n.params().isEmpty(), "Exactly one output required"); output = i; }
            if (n.type() == NodeType.AUDIO_RENDER) { require(render == -1, "One pattern-to-audio source supported; stack patterns before rendering"); render = i; }
            if (n.type() == NodeType.DELAY) delayFrames += (int)param(n, NodeParam.FRAMES, 64);
        }
        require(output >= 0 && render >= 0, "Audio routing requires output and audio_render");
        require(delayFrames <= MAX_TOTAL_DELAY_FRAMES, "Delay memory budget exceeded");
        Set<Graph.Edge> unique = new HashSet<>();
        for (Graph.Edge e : graph.edges()) {
            require(ids.containsKey(e.fromNode()) && ids.containsKey(e.toNode()), "Dangling edge");
            Port from = graph.nodes().get(ids.get(e.fromNode())).type().outputPort(e.fromPort());
            Port to = graph.nodes().get(ids.get(e.toNode())).type().inputPort(e.toPort());
            require(from != null && to != null && to.accepts(from), "Unknown or incompatible port");
            require(unique.add(e), "Duplicate edge");
        }
        Port.validateArity(graph.nodes(), graph.edges());
        String outId = graph.nodes().get(output).id();
        require(graph.edges().stream().filter(e -> e.toNode().equals(outId)).count() == 1
                && graph.edges().stream().anyMatch(e -> e.toNode().equals(outId) && e.toPort().equals("audio")), "Signal graph output requires one audio input");
        Set<String> reached = new HashSet<>();
        collect(outId, graph, reached);
        require(reached.size() == ids.size(), "Every node must reach output");
        // Cut only AUDIO edges entering delays. A parallel bypass must still be acyclic.
        List<Integer> sorted = new ArrayList<>();
        int[] colors = new int[ids.size()];
        for (int i = 0; i < colors.length; i++) visit(i, graph, ids, colors, sorted, 0);
        String renderId = graph.nodes().get(render).id();
        Set<String> patternIds = new HashSet<>();
        collect(renderId, graph, patternIds);
        List<Graph.Node> patterns = new ArrayList<>();
        for (Graph.Node n : graph.nodes()) if (patternIds.contains(n.id())) {
            require(n.id().equals(renderId) || !n.type().isSignalNode(), "Invalid pattern source");
            patterns.add(n.id().equals(renderId) ? new Graph.Node(n.id(), NodeType.OUTPUT, Map.of()) : n);
        }
        List<Graph.Edge> edges = graph.edges().stream().filter(e -> patternIds.contains(e.toNode())).toList();
        LoopPlan plan = GraphCompiler.compile(new Graph(3, patterns, edges));
        return plan.withSignals(new SignalGraph(graph, sorted, ids, output));
    }

    private static void collect(String id, Graph graph, Set<String> seen) {
        if (!seen.add(id)) return;
        for (Graph.Edge e : graph.edges()) if (e.toNode().equals(id)) collect(e.fromNode(), graph, seen);
    }

    private static void visit(int i, Graph graph, Map<String, Integer> ids, int[] colors, List<Integer> sorted, int depth) {
        require(depth <= 16, "Signal graph nesting exceeds 16");
        require(colors[i] != 1, "Zero-delay graph cycle");
        if (colors[i] == 2) return;
        colors[i] = 1;
        Graph.Node n = graph.nodes().get(i);
        for (Graph.Edge e : graph.edges()) if (e.toNode().equals(n.id())) {
            if (n.type() == NodeType.DELAY && n.type().inputPort(e.toPort()).type() == PortType.AUDIO) continue;
            visit(ids.get(e.fromNode()), graph, ids, colors, sorted, depth + 1);
        }
        colors[i] = 2; sorted.add(i);
    }

    static double param(Graph.Node n, String key, double fallback) {
        Double value = n.params().get(key);
        return value == null ? fallback : value;
    }
    private static void range(Graph.Node n, String key, double fallback, double min, double max, boolean integer) {
        double v = param(n, key, fallback);
        require(v >= min && v <= max && (!integer || v == Math.rint(v)), "Invalid " + key + " on " + n.id());
    }
    private static void validateParams(Graph.Node n) {
        Set<String> allowed = switch (n.type()) {
            case LFO -> Set.of(NodeParam.RATE, NodeParam.SYNC, NodeParam.WAVE);
            case STEP_SEQUENCE -> Set.of(NodeParam.STEPS, NodeParam.RATE, NodeParam.GATE, "value0", "value1", "value2", "value3", "value4", "value5", "value6", "value7");
            case ENVELOPE -> Set.of(NodeParam.ATTACK, NodeParam.DECAY, NodeParam.SUSTAIN, NodeParam.RELEASE, NodeParam.MODE);
            case ATTENUVERTER -> Set.of(NodeParam.SCALE, NodeParam.OFFSET);
            case FILTER -> Set.of(NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q);
            case DELAY -> Set.of(NodeParam.FRAMES);
            case MIX_BUS -> Set.of(NodeParam.GAIN);
            case AUDIO_RENDER -> Set.of();
            default -> throw new IllegalArgumentException("Invalid signal node");
        };
        require(allowed.containsAll(n.params().keySet()), "Unknown parameter on " + n.id());
        switch (n.type()) {
            case LFO -> { range(n,NodeParam.RATE,1,.001,40,false); range(n,NodeParam.SYNC,0,0,1,true); range(n,NodeParam.WAVE,0,0,3,true); }
            case STEP_SEQUENCE -> {
                range(n,NodeParam.STEPS,4,1,8,true); range(n,NodeParam.RATE,1,.125,16,false); range(n,NodeParam.GATE,.5,.001,1,false);
                for (int i=0;i<8;i++) range(n,"value"+i,0,-1,1,false);
            }
            case ENVELOPE -> {
                range(n,NodeParam.ATTACK,.01,0,8,false); range(n,NodeParam.DECAY,.1,0,8,false); range(n,NodeParam.SUSTAIN,.5,0,1,false);
                range(n,NodeParam.RELEASE,.1,0,8,false); range(n,NodeParam.MODE,0,0,1,true);
            }
            case ATTENUVERTER -> { range(n,NodeParam.SCALE,1,-20000,20000,false); range(n,NodeParam.OFFSET,0,-20000,20000,false); }
            case FILTER -> { range(n,NodeParam.CUTOFF_HZ,20000,20,20000,false); range(n,NodeParam.RESONANCE_Q,Biquad.DEFAULT_Q,.1,20,false); }
            case DELAY -> range(n,NodeParam.FRAMES,64,CONTROL_FRAMES,MAX_DELAY_FRAMES,true);
            case MIX_BUS -> range(n,NodeParam.GAIN,1,0,1,false);
            default -> { }
        }
    }

    /** Server overwrites submitted birth stamps. Only rate/domain changes restart an existing Hz LFO. */
    public static Graph assignBirths(Graph submitted, Graph previous, long atNanos) {
        Map<String, Graph.Node> old = new HashMap<>();
        if (previous != null) for (Graph.Node n : previous.nodes()) old.put(n.id(), n);
        List<Graph.Node> result = new ArrayList<>();
        for (Graph.Node n : submitted.nodes()) {
            if (n.type() != NodeType.LFO) { result.add(n); continue; }
            Graph.Node before = old.get(n.id());
            Long birth = before != null && before.type() == NodeType.LFO && before.birthNanos() != null
                    && param(n,NodeParam.RATE,1) == param(before,NodeParam.RATE,1) && param(n,NodeParam.SYNC,0) == param(before,NodeParam.SYNC,0)
                    ? before.birthNanos() : atNanos;
            result.add(new Graph.Node(n.id(), n.type(), n.params(), n.sample(), birth));
        }
        return new Graph(submitted.version(), result, submitted.edges());
    }

    public SignalRuntime runtime(SessionState state) { return new SignalRuntime(this, state); }
    static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
