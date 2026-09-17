package groove.engine;

import java.util.*;
import groove.engine.samples.SampleRegion;
import groove.engine.samples.SampleVoice;

/** Strict, bounded v1/v2/v3 compiler. Run on a control/worker thread, never an audio callback. */
public final class GraphCompiler {
    public static final int MAX_NODES = 64, MAX_EVENTS = 128;
    private final Map<String, Graph.Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> inputs = new HashMap<>();
    private final Map<String, Compiled> compiled = new HashMap<>();
    private final Set<String> visiting = new HashSet<>();
    // Bounds cover every branch, including branches absent from the cycle-zero preview.
    private record Compiled(Pattern pattern, int cost, double minHz, double maxHz, boolean samples, Set<SampleVoice> sampleVoices) {
        Compiled(Pattern pattern, int cost, double minHz, double maxHz, boolean samples) {
            this(pattern, cost, minHz, maxHz, samples, Set.of());
        }
    }

    private static Compiled derived(Pattern pattern, int cost, List<Compiled> children) {
        double min = Double.POSITIVE_INFINITY, max = 0;
        boolean samples = false;
        Set<SampleVoice> voices = new HashSet<>();
        for (Compiled child : children) {
            min = Math.min(min, child.minHz); max = Math.max(max, child.maxHz); samples |= child.samples;
            voices.addAll(child.sampleVoices);
            require(voices.size() <= groove.engine.samples.PreparedSamples.MAX_VOICES, "Too many sample voice variants");
        }
        return new Compiled(pattern, cost, min, max, samples, Set.copyOf(voices));
    }

    private static void pitchBounds(double min, double max) {
        require(Double.isFinite(min) && Double.isFinite(max) && min >= 20 && max <= 16000,
                "Transformed frequency must be 20..16000 Hz in every branch");
    }

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
            require(node.params().size() <= (node.type() == NodeType.SCALE_SEQUENCE || node.type() == NodeType.GENERATOR_SAMPLE ? 12 : 8), "Too many parameters");
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
        return new LoopPlan(events, result.pattern, result.cost).withSampleVoices(result.sampleVoices);
    }

    private Compiled visit(String id, int depth) {
        require(depth <= 16, "Graph nesting exceeds 16");
        if (compiled.containsKey(id)) return compiled.get(id);
        require(visiting.add(id), "Graph contains a cycle");
        Graph.Node node = nodes.get(id);
        List<String> links = inputs.get(id);
        Set<String> allowed = switch (node.type()) {
            case TONE -> Set.of(NodeParam.FREQUENCY, NodeParam.GAIN, NodeParam.PAN, NodeParam.WAVE, NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q, NodeParam.PULSE_WIDTH);
            case GENERATOR_SAMPLE -> Set.of(NodeParam.PITCH_RATIO, NodeParam.GAIN, NodeParam.PAN, NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q,
                    NodeParam.START_FRAME, NodeParam.END_FRAME, NodeParam.REVERSE,
                    NodeParam.LOOP, NodeParam.LOOP_START, NodeParam.LOOP_END, NodeParam.LOOP_FADE_MS);
            case SAMPLE_SLICE -> Set.of(NodeParam.SLICES, NodeParam.INDEX, NodeParam.REVERSE);
            case FAST -> Set.of(NodeParam.FACTOR);
            case EUCLID -> Set.of(NodeParam.STEPS, NodeParam.PULSES, NodeParam.ROTATION);
            case STACK, ALTERNATE, OUTPUT, REVERSE -> Set.of();
            case SWING -> Set.of(NodeParam.SUBDIVISION, NodeParam.AMOUNT);
            case PROBABILITY -> Set.of(NodeParam.CHANCE, NodeParam.SEED);
            case POLYMETER -> Set.of(NodeParam.STEPS_PER_CYCLE);
            case TRANSPOSE -> Set.of(NodeParam.SEMITONES);
            case CHORD -> Set.of(NodeParam.CHORD, NodeParam.INVERSION);
            case SCALE_SEQUENCE -> Set.of(NodeParam.ROOT, NodeParam.SCALE, NodeParam.STEPS, NodeParam.STEPS_PER_CYCLE,
                    "value0", "value1", "value2", "value3", "value4", "value5", "value6", "value7");
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
            int wave = integer(node, NodeParam.WAVE, 0, 0, 2);
            double cutoffHz = number(node, NodeParam.CUTOFF_HZ, 20000);
            require(cutoffHz >= 20 && cutoffHz <= 20000, "cutoffHz must be 20..20000 Hz");
            double pulseWidth = number(node, NodeParam.PULSE_WIDTH, 0.5);
            require(pulseWidth >= 0.01 && pulseWidth <= 0.99, "pulseWidth must be 0.01..0.99");
            yield new Compiled(Pattern.tone(new Tone(Tone.Wave.values()[wave], frequency,
                    number(node, NodeParam.GAIN, .25), number(node, NodeParam.PAN, 0), cutoffHz, number(node, NodeParam.RESONANCE_Q, Biquad.DEFAULT_Q), pulseWidth)), 1, frequency, frequency, false);
        }
        case GENERATOR_SAMPLE -> {
            int loopInt = integer(node, NodeParam.LOOP, 0, 0, 1);
            boolean loop = loopInt == 1;
            double loopStart = number(node, NodeParam.LOOP_START, SampleVoice.DEFAULT_LOOP_START);
            double loopEnd = number(node, NodeParam.LOOP_END, SampleVoice.DEFAULT_LOOP_END);
            double loopFadeMs = number(node, NodeParam.LOOP_FADE_MS, SampleVoice.DEFAULT_LOOP_FADE_MS);
            if (loop) {
                require(loopStart >= 0.0 && loopStart <= 1.0, "loopStart must be 0..1");
                require(loopEnd >= 0.0 && loopEnd <= 1.0, "loopEnd must be 0..1");
                require(loopStart < loopEnd, "loopStart must be less than loopEnd");
                require(loopFadeMs >= 0.0 && loopFadeMs <= 500.0, "loopFadeMs must be 0..500");
            } else {
                loopStart = SampleVoice.DEFAULT_LOOP_START;
                loopEnd = SampleVoice.DEFAULT_LOOP_END;
                loopFadeMs = SampleVoice.DEFAULT_LOOP_FADE_MS;
            }
            SampleVoice voice = new SampleVoice(node.sample(),
                    number(node, NodeParam.PITCH_RATIO, 1), number(node, NodeParam.GAIN, .8), number(node, NodeParam.PAN, 0), number(node, NodeParam.CUTOFF_HZ, 20000),
                    number(node, NodeParam.RESONANCE_Q, Biquad.DEFAULT_Q), new SampleRegion(
                    integer(node, NodeParam.START_FRAME, 0, 0, groove.engine.samples.SampleData.MAX_FLOATS - 1),
                    integer(node, NodeParam.END_FRAME, 0, 0, groove.engine.samples.SampleData.MAX_FLOATS), 1, 0,
                    integer(node, NodeParam.REVERSE, 0, 0, 1) == 1),
                    loop, loopStart, loopEnd, loopFadeMs);
            yield new Compiled(Pattern.sample(voice), 1, Double.POSITIVE_INFINITY, 0, true, Set.of(voice));
        }
        case SAMPLE_SLICE -> {
            Compiled child = children.getFirst();
            require(child.samples && child.maxHz == 0, "Sample slicing requires sample-only input");
            int slices = integer(node, NodeParam.SLICES, 8, 1, 64);
            int index = integer(node, NodeParam.INDEX, 0, 0, slices - 1);
            boolean reverse = integer(node, NodeParam.REVERSE, 0, 0, 1) == 1;
            Set<SampleVoice> voices = new HashSet<>();
            for (SampleVoice voice : child.sampleVoices) voices.add(voice.slice(slices, index, reverse));
            yield new Compiled(child.pattern.slice(slices, index, reverse), child.cost, child.minHz, child.maxHz, true, Set.copyOf(voices));
        }
        case TRANSPOSE -> {
            Compiled child = children.getFirst();
            require(!child.samples, "Transpose requires tone events");
            double semitones = number(node, NodeParam.SEMITONES, 0);
            require(Math.abs(semitones) <= 48, "Transpose must be -48..48 semitones");
            double ratio = Math.pow(2, semitones / 12);
            pitchBounds(child.minHz * ratio, child.maxHz * ratio);
            yield new Compiled(child.pattern.transpose(semitones), child.cost, child.minHz * ratio, child.maxHz * ratio, false);
        }
        case CHORD -> {
            Compiled child = children.getFirst();
            require(!child.samples, "Chords require tone events");
            Pitch.Chord chord = Pitch.Chord.values()[integer(node, NodeParam.CHORD, 0, 0, Pitch.Chord.values().length - 1)];
            int inversion = integer(node, NodeParam.INVERSION, 0, 0, chord.size() - 1);
            int cost = child.cost * chord.size();
            require(cost <= MAX_EVENTS, "Graph exceeds event budget");
            int[] intervals = chord.intervals(inversion);
            double min = child.minHz * Math.pow(2, intervals[0] / 12.0);
            double max = child.maxHz * Math.pow(2, intervals[intervals.length - 1] / 12.0);
            pitchBounds(min, max);
            yield new Compiled(child.pattern.chord(chord, inversion), cost, min, max, false);
        }
        case SCALE_SEQUENCE -> {
            Compiled child = children.getFirst();
            require(!child.samples, "Scale sequences require tone events");
            int root = integer(node, NodeParam.ROOT, 60, 0, 127);
            Pitch.Scale scale = Pitch.Scale.values()[integer(node, NodeParam.SCALE, 0, 0, Pitch.Scale.values().length - 1)];
            int steps = integer(node, NodeParam.STEPS, 4, 1, 8);
            int rate = integer(node, NodeParam.STEPS_PER_CYCLE, 4, 1, 64);
            int cost = child.cost * rate;
            require(cost <= MAX_EVENTS, "Graph exceeds event budget");
            int[] degrees = new int[steps];
            double min = Double.POSITIVE_INFINITY, max = 0;
            for (int i = 0; i < 8; i++) {
                int degree = integer(node, NodeParam.VALUES[i], 0, -64, 64);
                if (i < steps) {
                    degrees[i] = degree;
                    double hz = Pitch.degreeHz(root, scale, degree);
                    min = Math.min(min, hz); max = Math.max(max, hz);
                }
            }
            pitchBounds(min, max);
            yield new Compiled(child.pattern.scaleSequence(root, scale, rate, degrees), cost, min, max, false);
        }
        case ALTERNATE, POLYMETER -> {
            int rate = node.type() == NodeType.POLYMETER ? integer(node, NodeParam.STEPS_PER_CYCLE, 4, 1, 64) : 1;
            int cost = children.stream().mapToInt(Compiled::cost).max().orElseThrow() * rate;
            require(cost <= MAX_EVENTS, "Graph exceeds event budget");
            Pattern[] patterns = children.stream().map(Compiled::pattern).toArray(Pattern[]::new);
            yield derived(node.type() == NodeType.ALTERNATE ? Pattern.alternate(patterns) : Pattern.polymeter(rate, patterns), cost, children);
        }
        case PROBABILITY -> {
            Compiled child = children.getFirst();
            double chance = number(node, NodeParam.CHANCE, .5);
            require(chance >= 0 && chance <= 1, "Invalid chance");
            int seed = integer(node, NodeParam.SEED, 0, 0, 65535);
            yield derived(child.pattern.probability(chance, seed), child.cost, children);
        }
        case STACK -> {
            int cost = children.stream().mapToInt(Compiled::cost).sum();
            require(cost <= MAX_EVENTS, "Graph exceeds event budget");
            yield derived(Pattern.stack(children.stream().map(Compiled::pattern).toArray(Pattern[]::new)), cost, children);
        }
        case FAST -> {
            Compiled child = children.getFirst();
            double factor = number(node, NodeParam.FACTOR, 2);
            require(factor >= .25 && factor <= 16, "Invalid factor");
            require(child.cost * factor <= MAX_EVENTS, "Graph exceeds event budget");
            yield derived(child.pattern.fast(factor), (int) Math.ceil(child.cost * Math.max(1, factor)), children);
        }
        case EUCLID -> {
            Compiled child = children.getFirst();
            int steps = integer(node, NodeParam.STEPS, 16, 1, 64);
            int pulses = integer(node, NodeParam.PULSES, 4, 0, steps);
            int rotation = integer(node, NodeParam.ROTATION, 0, -1024, 1024);
            require(child.cost * Math.max(1, pulses) <= MAX_EVENTS, "Graph exceeds event budget");
            yield derived(child.pattern.euclid(steps, pulses, rotation), child.cost * pulses, children);
        }
        case REVERSE -> derived(children.getFirst().pattern.reverse(), children.getFirst().cost, children);
        case SWING -> {
            Compiled child = children.getFirst();
            int subdivision = integer(node, NodeParam.SUBDIVISION, 16, 2, 64);
            require(subdivision % 2 == 0, "Subdivision must be an even integer in 2..64");
            double amount = number(node, NodeParam.AMOUNT, 0.333);
            require(amount >= 0 && amount <= 1, "Swing amount must be in 0..1");
            yield derived(child.pattern.swing(subdivision, amount), child.cost, children);
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
