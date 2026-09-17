package groove.engine;

import java.util.*;

/** Immutable, bounded signal routing compiled on the control worker, with independent pattern sources. */
public final class SignalGraph {
    public static final int CONTROL_FRAMES = 64, MAX_DELAY_FRAMES = 48000, MAX_TOTAL_DELAY_FRAMES = 192000;
    public static final int MAX_SYNC_DELAY_FRAMES = 192000;
    public static final double[] DELAY_DIVISION_BEATS = {0.25, 1.0 / 3.0, 0.5, 2.0 / 3.0, 0.75, 1.0, 1.5, 2.0};
    public static final int MAX_AUDIO_SOURCES = 8, MAX_TRIGGER_SOURCES = 8;
    /** Longest an ENVELOPE's attack+decay+release can span (each 0-8 cycles); a trigger source's
     *  scheduler must look back at least this far so a still-releasing voice's onset stays visible. */
    public static final int MAX_ENVELOPE_TAIL_CYCLES = 24;
    final Graph.Node[] nodes;
    final int[][] audioInputs;
    final int[] controlInput, order;
    final int output;
    private final int[] sourceNodes;
    private final LoopPlan[] sourcePlans;
    private final int[] triggerNodes;
    private final LoopPlan[] triggerPlans;
    public int sourceCount() { return sourcePlans.length; }
    public String sourceNodeId(int source) { return nodes[sourceNodes[source]].id(); }
    int sourceNode(int source) { return sourceNodes[source]; }
    LoopPlan sourcePlan(int source) { return sourcePlans[source]; }
    public int triggerCount() { return triggerPlans.length; }
    public String triggerNodeId(int trigger) { return nodes[triggerNodes[trigger]].id(); }
    int triggerNode(int trigger) { return triggerNodes[trigger]; }
    LoopPlan triggerPlan(int trigger) { return triggerPlans[trigger]; }

    private SignalGraph(Graph graph, List<Integer> sorted, Map<String, Integer> ids, int output,
                        List<Integer> renders, List<LoopPlan> plans, List<Integer> triggers, List<LoopPlan> triggerLoopPlans) {
        nodes = graph.nodes().toArray(Graph.Node[]::new);
        this.output = output;
        sourceNodes = renders.stream().mapToInt(Integer::intValue).toArray();
        sourcePlans = plans.toArray(LoopPlan[]::new);
        triggerNodes = triggers.stream().mapToInt(Integer::intValue).toArray();
        triggerPlans = triggerLoopPlans.toArray(LoopPlan[]::new);
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
        int output = -1, delayFrames = 0, reverbs = 0;
        List<Integer> renders = new ArrayList<>();
        List<Integer> triggers = new ArrayList<>();
        for (int i = 0; i < graph.nodes().size(); i++) {
            Graph.Node n = graph.nodes().get(i);
            require(n.id() != null && n.id().matches("[a-zA-Z0-9_-]{1,32}") && ids.putIfAbsent(n.id(), i) == null, "Invalid or duplicate node id");
            require(n.type() != null && n.params().size() <= 16, "Invalid node");
            require(n.type() == NodeType.GENERATOR_SAMPLE ? n.sample() != null : n.sample() == null, "Invalid sample reference");
            require(n.type() == NodeType.LFO || n.birthNanos() == null, "Birth timestamp is only valid on LFO");
            for (double value : n.params().values()) require(Double.isFinite(value), "Non-finite parameter");
            if (n.type().isSignalNode()) validateParams(n);
            if (n.type() == NodeType.OUTPUT) { require(output == -1 && n.params().isEmpty(), "Exactly one output required"); output = i; }
            if (n.type() == NodeType.AUDIO_RENDER) renders.add(i);
            if (n.type() == NodeType.TRIGGER_RENDER) triggers.add(i);
            if (n.type() == NodeType.REVERB) reverbs++;
            if (n.type() == NodeType.DELAY) {
                boolean sync = param(n, NodeParam.SYNC, 0) == 1;
                if (sync) {
                    int division = (int) param(n, NodeParam.DIVISION, 2);
                    delayFrames += (int) Math.round(96000.0 * DELAY_DIVISION_BEATS[division]);
                } else {
                    delayFrames += (int) param(n, NodeParam.FRAMES, 64);
                }
            }
        }
        require(output >= 0 && !renders.isEmpty(), "Audio routing requires output and audio_render");
        require(renders.size() <= MAX_AUDIO_SOURCES, "At most eight audio_render sources supported");
        require(triggers.size() <= MAX_TRIGGER_SOURCES, "At most eight trigger_render sources supported");
        require(delayFrames <= MAX_TOTAL_DELAY_FRAMES,
                "Delay memory budget exceeded: " + delayFrames + " frames (max " + MAX_TOTAL_DELAY_FRAMES + "; synced delays count at 30 BPM)");
        require(reverbs <= 2, "At most 2 reverbs per graph");
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
        checkLoopGain(graph.nodes(), graph.edges());
        // Trigger sources share the audio sources' event budget, not an independent one, so a
        // trigger-only patch can't bypass the combined MAX_EVENTS cap; threading cost through
        // both calls (rather than each starting fresh) is what enforces that.
        SourceBatch audio = compileSources(graph, renders, 0, "Audio sources exceed combined event budget");
        SourceBatch triggered = compileSources(graph, triggers, audio.cost(), "Trigger sources exceed combined event budget");
        List<LoopPlan> plans = audio.plans(), triggerPlans = triggered.plans();
        List<LoopPlan> allPlans = new ArrayList<>(plans); allPlans.addAll(triggerPlans);
        int cost = triggered.cost();
        // Aggregate preview is for inspection only. Playback schedules each source independently.
        Pattern preview = Pattern.stack(allPlans.stream().map(LoopPlan::pattern).toArray(Pattern[]::new));
        // eventCost() above is GraphCompiler's upper-bound estimate per source (can be loose
        // through STACK/FAST/EUCLID); this checks the real materialized count of the combined
        // preview, which can differ from the summed estimate. Not redundant with the loop above.
        List<Event> events = preview.query(new Arc(0, 1));
        require(events.size() <= GraphCompiler.MAX_EVENTS, "Too many combined source events");
        events.sort(Comparator.comparingDouble(e -> e.whole().start()));
        Set<groove.engine.samples.SampleVoice> voices = new HashSet<>();
        for (LoopPlan plan : allPlans) voices.addAll(plan.sampleVoices());
        require(voices.size() <= groove.engine.samples.PreparedSamples.MAX_VOICES, "Too many sample voice variants");
        return new LoopPlan(events, preview, cost).withSampleVoices(voices)
                .withSignals(new SignalGraph(graph, sorted, ids, output, renders, plans, triggers, triggerPlans));
    }

    private record SourceBatch(List<LoopPlan> plans, int cost) {}
    /** Compiles one root list (audio renders, or trigger renders), enforcing budgetMessage
     *  against costSoFar as it goes, so callers can chain two calls to share one running budget. */
    private static SourceBatch compileSources(Graph graph, List<Integer> roots, int costSoFar, String budgetMessage) {
        List<LoopPlan> plans = new ArrayList<>();
        int cost = costSoFar;
        for (int root : roots) {
            LoopPlan plan = compileSource(graph, graph.nodes().get(root).id());
            cost += plan.eventCost();
            require(cost <= GraphCompiler.MAX_EVENTS, budgetMessage);
            plans.add(plan);
        }
        return new SourceBatch(plans, cost);
    }

    private static LoopPlan compileSource(Graph graph, String renderId) {
        Set<String> patternIds = new HashSet<>();
        collect(renderId, graph, patternIds);
        List<Graph.Node> patterns = new ArrayList<>();
        for (Graph.Node n : graph.nodes()) if (patternIds.contains(n.id())) {
            require(n.id().equals(renderId) || !n.type().isSignalNode(), "Invalid pattern source");
            patterns.add(n.id().equals(renderId) ? new Graph.Node(n.id(), NodeType.OUTPUT, Map.of()) : n);
        }
        List<Graph.Edge> edges = graph.edges().stream().filter(e -> patternIds.contains(e.toNode())).toList();
        return GraphCompiler.compile(new Graph(3, patterns, edges));
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
            case FILTER -> Set.of(NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q, NodeParam.MODE);
            case DELAY -> Set.of(NodeParam.FRAMES, NodeParam.SYNC, NodeParam.DIVISION);
            case MIX_BUS -> Set.of(NodeParam.GAIN);
            case REVERB -> Set.of(NodeParam.DECAY_SECONDS, NodeParam.DAMPING_HZ, NodeParam.BANDWIDTH_HZ, NodeParam.PRE_DELAY_MS);
            case AUDIO_RENDER, TRIGGER_RENDER -> Set.of();
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
            case FILTER -> { range(n,NodeParam.CUTOFF_HZ,20000,20,20000,false); range(n,NodeParam.RESONANCE_Q,Biquad.DEFAULT_Q,.1,20,false); range(n,NodeParam.MODE,0,0,3,true); }
            case DELAY -> {
                range(n, NodeParam.FRAMES, 64, CONTROL_FRAMES, MAX_DELAY_FRAMES, true);
                range(n, NodeParam.SYNC, 0, 0, 1, true);
                range(n, NodeParam.DIVISION, 2, 0, 7, true);
            }
            case MIX_BUS -> range(n,NodeParam.GAIN,1,0,1,false);
            case REVERB -> {
                range(n, NodeParam.DECAY_SECONDS, 1.8, 0.1, 20.0, false);
                range(n, NodeParam.DAMPING_HZ, 6000.0, 200.0, 20000.0, false);
                range(n, NodeParam.BANDWIDTH_HZ, 12000.0, 200.0, 20000.0, false);
                range(n, NodeParam.PRE_DELAY_MS, 0.0, 0.0, 500.0, false);
            }
            default -> { }
        }
    }

    public static boolean isLoopGainValid(Collection<Graph.Node> nodes, Collection<Graph.Edge> edges) {
        try {
            checkLoopGain(nodes, edges);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static void checkLoopGain(Collection<Graph.Node> nodes, Collection<Graph.Edge> edges) {
        Map<String, Graph.Node> signalNodes = new LinkedHashMap<>();
        for (Graph.Node n : nodes) {
            if (n.type() != null && n.type().isSignalNode()) {
                signalNodes.put(n.id(), n);
            }
        }
        boolean anyReverb = false;
        for (Graph.Node n : signalNodes.values()) {
            if (n.type() == NodeType.REVERB) {
                anyReverb = true;
                break;
            }
        }
        if (!anyReverb) return;

        Map<String, List<String>> audioAdj = new HashMap<>();
        for (String id : signalNodes.keySet()) audioAdj.put(id, new ArrayList<>());
        for (Graph.Edge e : edges) {
            Graph.Node from = signalNodes.get(e.fromNode());
            Graph.Node to = signalNodes.get(e.toNode());
            if (from == null || to == null) continue;
            Port port = to.type().inputPort(e.toPort());
            if (port != null && port.type() == PortType.AUDIO) {
                audioAdj.get(from.id()).add(to.id());
            }
        }

        Map<String, Integer> indices = new HashMap<>();
        Map<String, Integer> lowlink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<List<String>> sccs = new ArrayList<>();
        int[] counter = new int[1];

        for (String u : signalNodes.keySet()) {
            if (!indices.containsKey(u)) {
                tarjan(u, audioAdj, indices, lowlink, stack, onStack, sccs, counter);
            }
        }

        for (List<String> scc : sccs) {
            boolean isCycle = scc.size() > 1 || (scc.size() == 1 && audioAdj.get(scc.getFirst()).contains(scc.getFirst()));
            if (!isCycle) continue;
            boolean hasReverb = scc.stream().anyMatch(id -> signalNodes.get(id).type() == NodeType.REVERB);
            if (!hasReverb) continue;

            List<String> delays = scc.stream().filter(id -> signalNodes.get(id).type() == NodeType.DELAY).toList();
            if (delays.isEmpty()) {
                throw new IllegalArgumentException("Zero-delay graph cycle");
            }

            Set<String> sccSet = new HashSet<>(scc);
            Set<String> delaySet = new HashSet<>(delays);

            Map<String, List<String>> cutAdj = new HashMap<>();
            Map<String, Integer> inDegree = new HashMap<>();
            for (String id : scc) {
                cutAdj.put(id, new ArrayList<>());
                inDegree.put(id, 0);
            }
            for (String u : scc) {
                for (String v : audioAdj.get(u)) {
                    if (sccSet.contains(v) && !delaySet.contains(v)) {
                        cutAdj.get(u).add(v);
                        inDegree.put(v, inDegree.get(v) + 1);
                    }
                }
            }

            Deque<String> zeroIn = new ArrayDeque<>();
            for (String id : scc) {
                if (inDegree.get(id) == 0) zeroIn.add(id);
            }
            List<String> topo = new ArrayList<>();
            while (!zeroIn.isEmpty()) {
                String curr = zeroIn.poll();
                topo.add(curr);
                for (String next : cutAdj.get(curr)) {
                    int deg = inDegree.get(next) - 1;
                    inDegree.put(next, deg);
                    if (deg == 0) zeroIn.add(next);
                }
            }
            require(topo.size() == scc.size(), "Zero-delay graph cycle");

            int k = delays.size();
            double[][] M = new double[k][k];
            for (int j = 0; j < k; j++) {
                String delayJ = delays.get(j);
                Map<String, Double> A = new HashMap<>();
                for (String v : topo) {
                    Graph.Node nodeV = signalNodes.get(v);
                    if (nodeV.type() == NodeType.DELAY) {
                        A.put(v, v.equals(delayJ) ? 1.0 : 0.0);
                    } else {
                        List<Double> inVals = new ArrayList<>();
                        for (Graph.Edge e : edges) {
                            if (e.toNode().equals(v)) {
                                Port p = nodeV.type().inputPort(e.toPort());
                                if (p != null && p.type() == PortType.AUDIO) {
                                    inVals.add(sccSet.contains(e.fromNode()) ? A.getOrDefault(e.fromNode(), 0.0) : 0.0);
                                }
                            }
                        }
                        double aVal;
                        switch (nodeV.type()) {
                            case MIX_BUS -> {
                                boolean modulated = edges.stream().anyMatch(e -> e.toNode().equals(v) && e.toPort().equals("gain"));
                                double gainBound = modulated ? 1.0 : param(nodeV, NodeParam.GAIN, 1.0);
                                double sum = 0.0;
                                for (double val : inVals) sum += val;
                                aVal = gainBound * sum;
                            }
                            case FILTER -> {
                                int mode = (int) param(nodeV, NodeParam.MODE, 0);
                                double Q = param(nodeV, NodeParam.RESONANCE_Q, Biquad.DEFAULT_Q);
                                double peak;
                                if (mode == 2 || mode == 3) {
                                    peak = 1.0;
                                } else {
                                    double qPeak = (Q > 1.0 / Math.sqrt(2.0)) ? (Q / Math.sqrt(1.0 - 1.0 / (4.0 * Q * Q))) : 1.0;
                                    peak = qPeak * 1.05;
                                }
                                double inVal = inVals.isEmpty() ? 0.0 : inVals.getFirst();
                                aVal = peak * inVal;
                            }
                            case REVERB -> {
                                double inVal = inVals.isEmpty() ? 0.0 : inVals.getFirst();
                                aVal = 0.95 * inVal;
                            }
                            default -> {
                                double inVal = inVals.isEmpty() ? 0.0 : inVals.getFirst();
                                aVal = inVal;
                            }
                        }
                        A.put(v, aVal);
                    }
                }

                for (int i = 0; i < k; i++) {
                    String delayI = delays.get(i);
                    double arriving = 0.0;
                    for (Graph.Edge e : edges) {
                        if (e.toNode().equals(delayI)) {
                            Port p = signalNodes.get(delayI).type().inputPort(e.toPort());
                            if (p != null && p.type() == PortType.AUDIO) {
                                if (sccSet.contains(e.fromNode())) {
                                    arriving = A.getOrDefault(e.fromNode(), 0.0);
                                }
                                break;
                            }
                        }
                    }
                    M[i][j] = arriving;
                }
            }

            for (int i = 0; i < k; i++) {
                double rowSum = 0.0;
                for (int j = 0; j < k; j++) rowSum += M[i][j];
                if (rowSum > 0.89) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                            "Feedback loop through reverb can exceed unity gain (bound %.2f)", rowSum));
                }
            }
        }
    }

    private static void tarjan(String u, Map<String, List<String>> adj,
                              Map<String, Integer> indices, Map<String, Integer> lowlink,
                              Deque<String> stack, Set<String> onStack,
                              List<List<String>> sccs, int[] counter) {
        indices.put(u, counter[0]);
        lowlink.put(u, counter[0]);
        counter[0]++;
        stack.push(u);
        onStack.add(u);

        for (String v : adj.get(u)) {
            if (!indices.containsKey(v)) {
                tarjan(v, adj, indices, lowlink, stack, onStack, sccs, counter);
                lowlink.put(u, Math.min(lowlink.get(u), lowlink.get(v)));
            } else if (onStack.contains(v)) {
                lowlink.put(u, Math.min(lowlink.get(u), indices.get(v)));
            }
        }

        if (lowlink.get(u).equals(indices.get(u))) {
            List<String> scc = new ArrayList<>();
            while (true) {
                String w = stack.pop();
                onStack.remove(w);
                scc.add(w);
                if (w.equals(u)) break;
            }
            sccs.add(scc);
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
