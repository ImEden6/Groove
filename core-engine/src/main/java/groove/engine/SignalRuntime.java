package groove.engine;

import java.util.*;

/** Renderer-private preallocated stereo DSP and absolute-time 64-frame control ramps. */
public final class SignalRuntime {
    private final SignalGraph graph;
    private final SessionState state;
    private final double[] left, right, start, end;
    private final double[][] controls, delayLeft, delayRight, nodeParams;
    private final int[] cursors;
    private final Biquad[] filtersLeft, filtersRight;
    private final Biquad.Mode[] filterModes;
    private final boolean[] filterCoefficientsSet;
    private final Reverb[] reverbs;
    private final double[] reverbOut = new double[2];
    private long reverbGuardHits = 0;
    private long delaySnaps;
    private final LookaheadScheduler.Window[] triggerWindowByNode;
    private static final LookaheadScheduler.Window[] NO_TRIGGERS = new LookaheadScheduler.Window[0];
    private long controlBlock = Long.MIN_VALUE;
    /** Frames a carried switch takes to move changed settings to their new values; package-private so the switch trial can sweep it. */
    static int rampFrames = 480;
    /** Per node: settings used on the last frame, where a following carry ramps from. */
    private final double[][] applied, rampFrom;
    /** Per node: ramp frames still to go. */
    private final int[] rampLeft;
    /** Per delay while fading: the two read taps, as frames back from the newest input. */
    private final int[] tapFrom, tapTo;
    /** Per delay: how many of its most recent slots hold real input, not zeros left by lengthening. */
    private final int[] filled;
    private int rampTotal = 1, ramping;
    /** Delay and reverb buffers may hold something other than zeros; a new runtime's arrays start zeroed. */
    private boolean touched;
    private final WorldInputs world;
    /** Per world node: its smoothed value at the start and end of worldBlock. */
    private final double[] worldFrom, worldTo;
    private final long[] worldBlock;
    private static final double BLOCK_SECONDS = SignalGraph.CONTROL_FRAMES / (double) LiveRenderer.SAMPLE_RATE;

    private static final int MIX_GAIN = 0;
    private static final int FILTER_CUTOFF = 0, FILTER_Q = 1;
    private static final int REVERB_DECAY = 0, REVERB_DAMPING = 1, REVERB_BANDWIDTH = 2, REVERB_PREDELAY = 3;
    private static final int LFO_SYNC = 0, LFO_RATE = 1, LFO_WAVE = 2;
    private static final int STEP_COUNT = 0, STEP_RATE = 1, STEP_VAL_0 = 2;
    private static final int ATT_SCALE = 0, ATT_OFFSET = 1;
    private static final int ENV_ATTACK = 0, ENV_DECAY = 1, ENV_SUSTAIN = 2, ENV_RELEASE = 3, ENV_MODE = 4;
    private static final int ENV_WIDTH = 5, ENV_RELEASE_AT = 6;
    private static final int WORLD_SOURCE = 0, WORLD_SMOOTH = 1;

    SignalRuntime(SignalGraph graph, SessionState state, WorldInputs world) {
        this.graph = graph; this.state = state; this.world = world;
        int size = graph.nodes.length;
        worldFrom = new double[size]; worldTo = new double[size]; worldBlock = new long[size];
        Arrays.fill(worldBlock, Long.MIN_VALUE);
        left = new double[size]; right = new double[size]; start = new double[size]; end = new double[size];
        controls = new double[size][SignalGraph.CONTROL_FRAMES];
        delayLeft = new double[size][]; delayRight = new double[size][]; cursors = new int[size];
        filtersLeft = new Biquad[size]; filtersRight = new Biquad[size]; filterCoefficientsSet = new boolean[size];
        filterModes = new Biquad.Mode[size];
        reverbs = new Reverb[size];
        triggerWindowByNode = new LookaheadScheduler.Window[size];
        nodeParams = new double[size][];
        applied = new double[size][]; rampFrom = new double[size][];
        rampLeft = new int[size]; tapFrom = new int[size]; tapTo = new int[size]; filled = new int[size];
        for (int i=0;i<size;i++) {
            Graph.Node n = graph.nodes[i];
            if (n.type() == NodeType.DELAY) {
                boolean sync = p(n, NodeParam.SYNC, 0) == 1;
                int frames;
                if (sync) {
                    int division = (int) p(n, NodeParam.DIVISION, 2);
                    frames = (int) Math.round(LiveRenderer.SAMPLE_RATE * 60.0 / state.bpm() * SignalGraph.DELAY_DIVISION_BEATS[division]);
                    if (frames < SignalGraph.CONTROL_FRAMES || frames > SignalGraph.MAX_SYNC_DELAY_FRAMES)
                        throw new IllegalArgumentException("Synced delay frames out of bounds: " + frames);
                } else {
                    frames = (int) p(n, NodeParam.FRAMES, 64);
                }
                delayLeft[i] = new double[frames]; delayRight[i] = new double[frames];
                filled[i] = frames;
            }
            int ramped = 0;
            switch (n.type()) {
                case MIX_BUS -> { nodeParams[i] = new double[]{p(n, NodeParam.GAIN, 1)}; ramped = 1; }
                case REVERB -> {
                    double[] p = new double[4];
                    p[REVERB_DECAY] = p(n, NodeParam.DECAY_SECONDS, 1.8);
                    p[REVERB_DAMPING] = p(n, NodeParam.DAMPING_HZ, 6000.0);
                    p[REVERB_BANDWIDTH] = p(n, NodeParam.BANDWIDTH_HZ, 12000.0);
                    p[REVERB_PREDELAY] = p(n, NodeParam.PRE_DELAY_MS, 0.0);
                    nodeParams[i] = p;
                    reverbs[i] = new Reverb();
                    reverbs[i].setParams(p[REVERB_DECAY], p[REVERB_DAMPING], p[REVERB_BANDWIDTH], p[REVERB_PREDELAY]);
                    // Predelay switches at once
                    ramped = 3;
                }
                case FILTER -> {
                    filtersLeft[i] = new Biquad(); filtersRight[i] = new Biquad();
                    filterModes[i] = Biquad.Mode.values()[(int)p(n,NodeParam.MODE,0)];
                    nodeParams[i] = new double[]{
                            p(n, NodeParam.CUTOFF_HZ, 20000),
                            p(n, NodeParam.RESONANCE_Q, Biquad.DEFAULT_Q)
                    };
                    ramped = 2;
                }
                case LFO -> nodeParams[i] = new double[]{
                        p(n, NodeParam.SYNC, 0),
                        p(n, NodeParam.RATE, 1),
                        p(n, NodeParam.WAVE, 0)
                };
                case STEP_SEQUENCE -> {
                    double[] p = new double[10];
                    p[STEP_COUNT] = p(n, NodeParam.STEPS, 4);
                    p[STEP_RATE] = p(n, NodeParam.RATE, 1);
                    for (int k = 0; k < 8; k++) p[STEP_VAL_0 + k] = p(n, NodeParam.VALUES[k], 0);
                    nodeParams[i] = p;
                }
                case ATTENUVERTER -> nodeParams[i] = new double[]{
                        p(n, NodeParam.SCALE, 1),
                        p(n, NodeParam.OFFSET, 0)
                };
                case WORLD -> nodeParams[i] = new double[]{p(n, NodeParam.SOURCE, 0), p(n, NodeParam.SMOOTH, 2)};
                case ENVELOPE -> {
                    double attack = p(n, NodeParam.ATTACK, .01);
                    double decay = p(n, NodeParam.DECAY, .1);
                    double sustain = p(n, NodeParam.SUSTAIN, .5);
                    double release = p(n, NodeParam.RELEASE, .1);
                    double mode = p(n, NodeParam.MODE, 0);
                    double width = 0, releaseAt = 0;
                    int trigIdx = graph.controlInput[i];
                    if (trigIdx >= 0) {
                        Graph.Node trigger = graph.nodes[trigIdx];
                        if (trigger.type() != NodeType.TRIGGER_RENDER) {
                            width = 1.0 / (p(trigger, NodeParam.STEPS, 4) * p(trigger, NodeParam.RATE, 1));
                            releaseAt = mode == 0 ? attack + decay : width * p(trigger, NodeParam.GATE, .5);
                        }
                    }
                    nodeParams[i] = new double[]{attack, decay, sustain, release, mode, width, releaseAt};
                }
                default -> nodeParams[i] = new double[0];
            }
            if (ramped > 0) { applied[i] = Arrays.copyOf(nodeParams[i], ramped); rampFrom[i] = new double[ramped]; }
        }
    }

    /**
     * Which effects of an earlier program's runtime this program continues, built on the control
     * thread from the two compiled graphs, never from live state. See docs/PHASE-2-SIGNALS.md.
     */
    public static final class TransferPlan {
        final SignalGraph from;
        /** Per node of the incoming graph: the outgoing node index it continues, or -1. */
        final int[] source;
        /** Per node: the outgoing node whose settings it ramps from, or -1. Carried effects plus same-id mix buses. */
        final int[] ramp;
        /** Per incoming audio source: the outgoing source with the same node id, or -1. */
        final int[] sources;
        /** Only audio patterns and ramped settings differ, so the switch needs no output crossfade. */
        final boolean seamless;
        final int carried;
        private TransferPlan(SignalGraph from, int[] source, int[] ramp, int[] sources, boolean seamless) {
            this.from = from; this.source = source; this.ramp = ramp; this.sources = sources; this.seamless = seamless;
            int n = 0;
            for (int s : source) if (s >= 0) n++;
            carried = n;
        }
        public int carried() { return carried; }
    }

    /**
     * Matches stateful effects (delay, filter, reverb) by node id and type. An effect outside a
     * feedback loop carries only if its own audio inputs are unchanged; a filter also needs the same
     * mode. Effects in a feedback loop carry as a group: every member keeps its id, type and inputs,
     * the loop keeps its members, and every effect in it passes its own rule, or the whole group resets.
     */
    public static TransferPlan transferPlan(SignalGraph from, SignalGraph to) {
        Map<String, Integer> fromIds = new HashMap<>();
        for (int j = 0; j < from.nodes.length; j++) fromIds.put(from.nodes[j].id(), j);
        int[] match = new int[to.nodes.length];
        for (int i = 0; i < to.nodes.length; i++) {
            Integer j = fromIds.get(to.nodes[i].id());
            match[i] = j != null && from.nodes[j].type() == to.nodes[i].type()
                    && inputIds(to, i).equals(inputIds(from, j)) ? j : -1;
        }
        int[] source = new int[to.nodes.length];
        for (int i = 0; i < source.length; i++) source[i] = stateful(to.nodes[i]) && compatible(from, to, match[i], i) ? match[i] : -1;
        int[] toLoop = loops(to), fromLoop = loops(from);
        // An old loop can disappear entirely, leaving no incoming group to validate below.
        // Its surviving effects must reset even if their individual input edges stayed intact.
        for (int i = 0; i < source.length; i++)
            if (source[i] >= 0 && toLoop[i] < 0 && fromLoop[source[i]] >= 0) source[i] = -1;
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < toLoop.length; i++) if (toLoop[i] >= 0) groups.computeIfAbsent(toLoop[i], k -> new ArrayList<>()).add(i);
        for (List<Integer> members : groups.values()) {
            boolean intact = true;
            int fromGroup = members.isEmpty() || match[members.getFirst()] < 0 ? -1 : fromLoop[match[members.getFirst()]];
            for (int i : members) {
                if (match[i] < 0 || fromGroup < 0 || fromLoop[match[i]] != fromGroup) { intact = false; break; }
                if (stateful(to.nodes[i]) && !compatible(from, to, match[i], i)) { intact = false; break; }
            }
            if (intact) {
                int size = 0;
                for (int g : fromLoop) if (g == fromGroup) size++;
                intact = size == members.size();
            }
            if (!intact) for (int i : members) source[i] = -1;
        }
        // A bus holds no state, so it ramps whenever its id and type survive, whatever its inputs.
        int[] ramp = source.clone();
        for (int i = 0; i < ramp.length; i++) if (to.nodes[i].type() == NodeType.MIX_BUS) {
            Integer j = fromIds.get(to.nodes[i].id());
            ramp[i] = j != null && from.nodes[j].type() == NodeType.MIX_BUS ? j : -1;
        }
        int[] sources = new int[to.sourceCount()];
        Arrays.fill(sources, -1);
        for (int s = 0; s < sources.length; s++)
            for (int t = 0; t < from.sourceCount(); t++)
                if (to.sourceNodeId(s).equals(from.sourceNodeId(t))) { sources[s] = t; break; }
        boolean seamless = sameStructure(from, to);
        for (int i = 0; i < source.length && seamless; i++) if (stateful(to.nodes[i]) && source[i] < 0) seamless = false;
        return new TransferPlan(from, source, ramp, sources, seamless);
    }

    /**
     * True when the graphs differ only in audio patterns and in settings a carry ramps. Pattern nodes
     * that feed only audio sources may change freely, since the sources crossfade where they enter.
     */
    private static boolean sameStructure(SignalGraph from, SignalGraph to) {
        Set<String> freeFrom = audioPatterns(from), freeTo = audioPatterns(to);
        Map<String, Graph.Node> fixed = new HashMap<>();
        for (Graph.Node n : from.nodes) if (!freeFrom.contains(n.id())) fixed.put(n.id(), n);
        int count = 0;
        for (Graph.Node n : to.nodes) {
            if (freeTo.contains(n.id())) continue;
            Graph.Node was = fixed.get(n.id());
            if (was == null || !sameSettings(was, n)) return false;
            count++;
        }
        if (count != fixed.size()) return false;
        return fixedEdges(from.edges, freeFrom).equals(fixedEdges(to.edges, freeTo));
    }

    /** Pattern nodes upstream of no trigger render: they only shape what an audio source plays. */
    private static Set<String> audioPatterns(SignalGraph graph) {
        Map<String, List<String>> inputs = new HashMap<>();
        for (Graph.Edge e : graph.edges) inputs.computeIfAbsent(e.toNode(), k -> new ArrayList<>()).add(e.fromNode());
        Set<String> triggerSide = new HashSet<>();
        Deque<String> open = new ArrayDeque<>();
        for (Graph.Node n : graph.nodes) if (n.type() == NodeType.TRIGGER_RENDER) open.add(n.id());
        while (!open.isEmpty())
            for (String up : inputs.getOrDefault(open.pop(), List.of())) if (triggerSide.add(up)) open.add(up);
        Set<String> free = new HashSet<>();
        for (Graph.Node n : graph.nodes)
            if (!n.type().isSignalNode() && n.type() != NodeType.OUTPUT && !triggerSide.contains(n.id())) free.add(n.id());
        return free;
    }

    private static Set<Graph.Edge> fixedEdges(List<Graph.Edge> edges, Set<String> free) {
        Set<Graph.Edge> kept = new HashSet<>();
        for (Graph.Edge e : edges) if (!free.contains(e.fromNode())) kept.add(e);
        return kept;
    }

    /** Same node apart from settings a carry ramps; a delay's length is checked when it carries. */
    private static boolean sameSettings(Graph.Node a, Graph.Node b) {
        if (a.type() != b.type() || !Objects.equals(a.sample(), b.sample()) || !Objects.equals(a.birthNanos(), b.birthNanos())) return false;
        Set<String> ramped = switch (b.type()) {
            case MIX_BUS -> Set.of(NodeParam.GAIN);
            case FILTER -> Set.of(NodeParam.CUTOFF_HZ, NodeParam.RESONANCE_Q);
            case REVERB -> Set.of(NodeParam.DECAY_SECONDS, NodeParam.DAMPING_HZ, NodeParam.BANDWIDTH_HZ);
            case DELAY -> Set.of(NodeParam.FRAMES, NodeParam.SYNC, NodeParam.DIVISION, NodeParam.FREE_RUN);
            case WORLD -> Set.of(NodeParam.SMOOTH);
            default -> Set.of();
        };
        Map<String, Double> x = new HashMap<>(a.params()), y = new HashMap<>(b.params());
        x.keySet().removeAll(ramped); y.keySet().removeAll(ramped);
        return x.equals(y);
    }

    private static boolean stateful(Graph.Node n) {
        return n.type() == NodeType.DELAY || n.type() == NodeType.FILTER || n.type() == NodeType.REVERB || n.type() == NodeType.WORLD;
    }

    /** Same id, type and inputs (match >= 0), plus a filter keeping its mode. */
    private static boolean compatible(SignalGraph from, SignalGraph to, int j, int i) {
        if (j < 0) return false;
        if (to.nodes[i].type() == NodeType.FILTER)
            return p(to.nodes[i], NodeParam.MODE, 0) == p(from.nodes[j], NodeParam.MODE, 0);
        if (to.nodes[i].type() == NodeType.WORLD)
            return p(to.nodes[i], NodeParam.SOURCE, 0) == p(from.nodes[j], NodeParam.SOURCE, 0);
        return true;
    }

    private static List<String> inputIds(SignalGraph graph, int node) {
        List<String> ids = new ArrayList<>();
        for (int input : graph.audioInputs[node]) ids.add(graph.nodes[input].id());
        ids.sort(null);
        return ids;
    }

    /** Per node, an id for its audio feedback loop, or -1 outside any loop. */
    private static int[] loops(SignalGraph graph) {
        int n = graph.nodes.length;
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        for (int v = 0; v < n; v++) for (int u : graph.audioInputs[v]) out.get(u).add(v);
        int[] index = new int[n], low = new int[n], group = new int[n];
        Arrays.fill(index, -1);
        Arrays.fill(group, -1);
        boolean[] onStack = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        int[] counter = {0, 0};
        for (int v = 0; v < n; v++) if (index[v] < 0) strongConnect(v, out, index, low, onStack, stack, group, counter);
        // A lone node is only a loop if it feeds itself.
        int[] size = new int[counter[1]];
        for (int g : group) size[g]++;
        for (int v = 0; v < n; v++) if (size[group[v]] == 1 && !out.get(v).contains(v)) group[v] = -1;
        return group;
    }

    private static void strongConnect(int v, List<List<Integer>> out, int[] index, int[] low, boolean[] onStack,
                                      Deque<Integer> stack, int[] group, int[] counter) {
        index[v] = low[v] = counter[0]++;
        stack.push(v); onStack[v] = true;
        for (int w : out.get(v)) {
            if (index[w] < 0) { strongConnect(w, out, index, low, onStack, stack, group, counter); low[v] = Math.min(low[v], low[w]); }
            else if (onStack[w]) low[v] = Math.min(low[v], index[w]);
        }
        if (low[v] == index[v]) {
            int w;
            do { w = stack.pop(); onStack[w] = false; group[w] = counter[1]; } while (w != v);
            counter[1]++;
        }
    }

    /**
     * Sound thread: continues {@code previous}'s effect tails in this freshly reset runtime. Delays
     * keep their input history, remapped when their length changed; filters keep their history
     * under new coefficients; reverbs keep their tank. No allocation. Returns false when a delay
     * made shorter has to jump, which a seamless switch cannot hide.
     */
    boolean continueFrom(SignalRuntime previous, TransferPlan plan) {
        touched = true;
        boolean continuous = true;
        if (plan.from != previous.graph || plan.source.length != graph.nodes.length)
            throw new IllegalArgumentException("Transfer plan does not match these runtimes");
        for (int i = 0; i < plan.source.length; i++) {
            int j = plan.source[i];
            if (j < 0) continue;
            switch (graph.nodes[i].type()) {
                case DELAY -> {
                    carryHistory(previous.delayLeft[j], previous.cursors[j], delayLeft[i]);
                    cursors[i] = carryHistory(previous.delayRight[j], previous.cursors[j], delayRight[i]);
                }
                case FILTER -> { filtersLeft[i].copyStateFrom(previous.filtersLeft[j]); filtersRight[i].copyStateFrom(previous.filtersRight[j]); }
                case REVERB -> reverbs[i].copyStateFrom(previous.reverbs[j]);
                case WORLD -> { worldFrom[i] = previous.worldFrom[j]; worldTo[i] = previous.worldTo[j]; worldBlock[i] = previous.worldBlock[j]; }
                default -> { }
            }
        }
        rampTotal = Math.max(1, rampFrames);
        for (int i = 0; i < plan.ramp.length; i++) {
            int j = plan.ramp[i];
            if (j < 0) continue;
            if (delayLeft[i] != null) continuous &= continueTap(i, previous, j);
            else if (applied[i] != null) {
                int mod = graph.controlInput[i];
                // A modulated gain or cutoff follows its control; a filter's Q can still ramp
                if (mod >= 0 && graph.nodes[i].type() == NodeType.MIX_BUS) continue;
                int first = mod >= 0 ? FILTER_Q : 0;
                if (differs(previous.applied[j], nodeParams[i], first)) {
                    System.arraycopy(previous.applied[j], 0, rampFrom[i], 0, rampFrom[i].length);
                    startRamp(i);
                }
            }
        }
        return continuous;
    }

    /** Keeps a delay reading what was being heard, including a fade in progress, then fades to its own length. False if it jumps. */
    private boolean continueTap(int i, SignalRuntime previous, int j) {
        int length = delayLeft[i].length, was = previous.delayLeft[j].length;
        filled[i] = Math.min(previous.filled[j], length);
        boolean fading = previous.rampLeft[j] > 0;
        int from = fading ? previous.tapFrom[j] : was, to = fading ? previous.tapTo[j] : was;
        if (from > filled[i] || to > length) {
            // A shorter line has lost a tap of the fade; keep the one mostly heard if it still holds it
            int heard = fading && previous.rampWeight(j) >= 0.5 ? to : from;
            if (heard <= filled[i] && heard != length) fade(i, heard, length, rampTotal + Math.max(0, length - filled[i]));
            return false;
        }
        if (fading) fade(i, from, to, previous.rampLeft[j]);
        else if (from != length) fade(i, from, length, rampTotal + Math.max(0, length - filled[i]));
        return true;
    }

    /** Reads tap from, then fades to tap to once it holds real input: the frames beyond rampTotal wait for it. */
    private void fade(int i, int from, int to, int frames) {
        if (rampLeft[i] == 0) ramping++;
        tapFrom[i] = from; tapTo[i] = to; rampLeft[i] = frames;
    }

    /** Delay line of node i, read the given number of frames back from its newest input. */
    private double tap(double[] line, int i, int back) {
        return line[(cursors[i] + line.length - back) % line.length];
    }

    /** Compares the ramped settings from index first; a reverb's predelay switches at once. */
    private static boolean differs(double[] was, double[] target, int first) {
        for (int k = first; k < was.length; k++) if (was[k] != target[k]) return true;
        return false;
    }

    private void startRamp(int node) { if (rampLeft[node] == 0) ramping++; rampLeft[node] = rampTotal; }

    /** This frame's weight of the new setting: 0 while a ramp waits, 1 on its last frame. */
    private double rampWeight(int node) { return smoothstep(Math.max(0, rampTotal - rampLeft[node] + 1) / (double) rampTotal); }

    /** 0 to 1 with zero slope at both ends, so a ramp adds no corner where it starts or stops. */
    static double smoothstep(double w) { return w * w * (3 - 2 * w); }

    private static double lerp(double a, double b, double w) { return a + (b - a) * w; }
    private static double logLerp(double a, double b, double w) { return a * Math.pow(b / a, w); }

    /** A delay line holds its last L inputs, oldest at the cursor. Returns the new cursor. */
    private static int carryHistory(double[] from, int cursor, double[] to) {
        int oldLength = from.length, newLength = to.length;
        if (oldLength == newLength) {
            System.arraycopy(from, 0, to, 0, newLength);
            return cursor;
        }
        // Keep the most recent inputs, ending just before slot 0 of the new line; older slots stay zero.
        int keep = Math.min(oldLength, newLength);
        int start = cursor - keep;
        if (start < 0) start += oldLength;
        int first = Math.min(keep, oldLength - start);
        System.arraycopy(from, start, to, newLength - keep, first);
        System.arraycopy(from, 0, to, newLength - keep + first, keep - first);
        return 0;
    }

    public void reset() {
        // Every switch resets a new runtime; its buffers are already zero, so skip the fill
        boolean clear = touched;
        touched = false;
        controlBlock = Long.MIN_VALUE;
        Arrays.fill(worldBlock, Long.MIN_VALUE);
        Arrays.fill(cursors,0);
        for (int i=0;i<graph.nodes.length;i++) {
            if (delayLeft[i] != null) {
                if (clear) { Arrays.fill(delayLeft[i],0); Arrays.fill(delayRight[i],0); }
                filled[i] = delayLeft[i].length;
            }
            if (filtersLeft[i] != null) { filtersLeft[i].reset(); filtersRight[i].reset(); filterCoefficientsSet[i] = false; }
            if (reverbs[i] != null && clear) reverbs[i].reset();
            if (rampLeft[i] > 0 && reverbs[i] != null) {
                double[] p = nodeParams[i];
                reverbs[i].setParams(p[REVERB_DECAY], p[REVERB_DAMPING], p[REVERB_BANDWIDTH], p[REVERB_PREDELAY]);
            }
            if (applied[i] != null) System.arraycopy(nodeParams[i], 0, applied[i], 0, applied[i].length);
        }
        Arrays.fill(rampLeft, 0);
        ramping = 0;
    }

    /** In-place stereo frame; call sequentially at 48 kHz. No graph queries or allocation. */
    public void process(double[] stereo, long serverNanos) {
        if (graph.sourceCount() != 1) throw new IllegalArgumentException("Use independent stereo inputs for multiple audio sources");
        if (graph.triggerCount() != 0) throw new IllegalArgumentException("Prepared trigger windows required");
        int source = graph.sourceNode(0);
        left[source] = stereo[0]; right[source] = stereo[1];
        processRouting(stereo, serverNanos);
    }

    /** Inputs follow SignalGraph.sourceNodeId order, not node-array positions. Buffers belong to the caller. */
    public void process(double[][] sources, double[] stereo, long serverNanos) {
        process(sources, NO_TRIGGERS, stereo, serverNanos);
    }

    /** Like {@link #process(double[][], double[], long)}, plus one prepared scheduler window per
     *  trigger source (SignalGraph.triggerNodeId order), read by any TRIGGER_RENDER-driven
     *  ENVELOPE. A null window is treated as "nothing active" rather than failing. */
    public void process(double[][] sources, LookaheadScheduler.Window[] triggers, double[] stereo, long serverNanos) {
        if (sources.length != graph.sourceCount() || stereo.length < 2) throw new IllegalArgumentException("Wrong source/output buffer count");
        if (triggers.length != graph.triggerCount()) throw new IllegalArgumentException("Wrong trigger window count");
        for (int s = 0; s < sources.length; s++) {
            if (sources[s] == null || sources[s].length < 2) throw new IllegalArgumentException("Source requires a stereo frame");
            int node = graph.sourceNode(s);
            left[node] = sources[s][0]; right[node] = sources[s][1];
        }
        for (int t = 0; t < triggers.length; t++) triggerWindowByNode[graph.triggerNode(t)] = triggers[t];
        processRouting(stereo, serverNanos);
    }

    private void processRouting(double[] stereo, long serverNanos) {
        touched = true;
        int frame = prepareControls(serverNanos);
        // Read all old delay cells before evaluating or writing any feedback input.
        for (int i=0;i<graph.nodes.length;i++) if (delayLeft[i] != null) {
            left[i] = delayLeft[i][cursors[i]]; right[i] = delayRight[i][cursors[i]];
            if (rampLeft[i] > 0) {
                double w = rampWeight(i);
                left[i] = lerp(tap(delayLeft[i], i, tapFrom[i]), tap(delayLeft[i], i, tapTo[i]), w);
                right[i] = lerp(tap(delayRight[i], i, tapFrom[i]), tap(delayRight[i], i, tapTo[i]), w);
            }
        }
        for (int i : graph.order) {
            Graph.Node n = graph.nodes[i];
            int[] inputs = graph.audioInputs[i];
            int mod = graph.controlInput[i];
            switch (n.type()) {
                case AUDIO_RENDER -> { /* Filled by source-to-node mapping before routing. */ }
                case MIX_BUS -> {
                    double gain = mod >= 0 ? clamp(controls[mod][frame],0,1)
                            : rampLeft[i] > 0 ? lerp(rampFrom[i][MIX_GAIN], nodeParams[i][MIX_GAIN], rampWeight(i)) : nodeParams[i][MIX_GAIN];
                    applied[i][MIX_GAIN] = gain;
                    double l=0,r=0;
                    for (int source : inputs) { l += left[source]; r += right[source]; }
                    left[i] = bounded(l*gain); right[i] = bounded(r*gain);
                }
                case FILTER -> {
                    if (mod >= 0 || rampLeft[i] > 0 || !filterCoefficientsSet[i]) {
                        double cutoff = mod < 0 ? nodeParams[i][FILTER_CUTOFF] : clamp(controls[mod][frame],20,20000);
                        double q = nodeParams[i][FILTER_Q];
                        if (rampLeft[i] > 0) {
                            double w = rampWeight(i);
                            if (mod < 0) cutoff = logLerp(rampFrom[i][FILTER_CUTOFF], cutoff, w);
                            q = lerp(rampFrom[i][FILTER_Q], q, w);
                        }
                        filtersLeft[i].set(filterModes[i],cutoff,q,LiveRenderer.SAMPLE_RATE);
                        filtersRight[i].set(filterModes[i],cutoff,q,LiveRenderer.SAMPLE_RATE);
                        filterCoefficientsSet[i] = true;
                        applied[i][FILTER_CUTOFF] = cutoff; applied[i][FILTER_Q] = q;
                    }
                    left[i] = bounded(filtersLeft[i].process(left[inputs[0]]));
                    right[i] = bounded(filtersRight[i].process(right[inputs[0]]));
                }
                case REVERB -> {
                    if (rampLeft[i] > 0) {
                        double w = rampWeight(i);
                        double[] p = nodeParams[i], a = applied[i], from = rampFrom[i];
                        a[REVERB_DECAY] = lerp(from[REVERB_DECAY], p[REVERB_DECAY], w);
                        a[REVERB_DAMPING] = logLerp(from[REVERB_DAMPING], p[REVERB_DAMPING], w);
                        a[REVERB_BANDWIDTH] = logLerp(from[REVERB_BANDWIDTH], p[REVERB_BANDWIDTH], w);
                        reverbs[i].setParams(a[REVERB_DECAY], a[REVERB_DAMPING], a[REVERB_BANDWIDTH], p[REVERB_PREDELAY]);
                    }
                    double in = 0.5 * (left[inputs[0]] + right[inputs[0]]);
                    long absoluteFrame = Math.round(serverNanos * (LiveRenderer.SAMPLE_RATE / 1e9));
                    reverbs[i].process(in, absoluteFrame, reverbOut);
                    left[i] = boundedReverb(reverbOut[0]);
                    right[i] = boundedReverb(reverbOut[1]);
                }
                case OUTPUT -> { left[i] = left[inputs[0]]; right[i] = right[inputs[0]]; }
                default -> { }
            }
        }
        for (int i=0;i<graph.nodes.length;i++) if (delayLeft[i] != null) {
            int source = graph.audioInputs[i][0], cursor = cursors[i];
            delayLeft[i][cursor] = snapDelay(bounded(left[source])); delayRight[i][cursor] = snapDelay(bounded(right[source]));
            cursors[i] = (cursor+1) % delayLeft[i].length;
            if (filled[i] < delayLeft[i].length) filled[i]++;
        }
        stereo[0] = left[graph.output]; stereo[1] = right[graph.output];
        if (ramping > 0) for (int i = 0; i < rampLeft.length; i++) {
            if (rampLeft[i] == 0 || --rampLeft[i] > 0) continue;
            ramping--;
            // A fade handed over from an earlier switch still has to reach this line's own length
            if (delayLeft[i] != null && tapTo[i] != delayLeft[i].length)
                fade(i, tapTo[i], delayLeft[i].length, rampTotal + Math.max(0, delayLeft[i].length - filled[i]));
        }
    }

    /** Diagnostic/control API; uses the identical cached ramps as audio playback. */
    public double control(String nodeId, long serverNanos) {
        int frame = prepareControls(serverNanos);
        for (int i=0;i<graph.nodes.length;i++) if (graph.nodes[i].id().equals(nodeId)) return controls[i][frame];
        throw new IllegalArgumentException("Unknown control node");
    }

    private int prepareControls(long nanos) {
        long absoluteFrame = Math.round(nanos * (LiveRenderer.SAMPLE_RATE / 1e9));
        long block = Math.floorDiv(absoluteFrame, SignalGraph.CONTROL_FRAMES);
        if (block != controlBlock) {
            double startNanos = block * (double)SignalGraph.CONTROL_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE;
            double endNanos = (block+1) * (double)SignalGraph.CONTROL_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE;
            // One window scan per trigger-driven envelope covers both ramp endpoints, instead of
            // the two below (LFO/STEP_SEQUENCE/ATTENUVERTER/regular ENVELOPE) each scanning once;
            // written directly into start[]/end[] before evaluate() runs, so any node evaluated
            // later in graph.order that reads this envelope's value sees the real result either way.
            evaluateTriggerDrivenEnvelopes(startNanos, endNanos);
            settleWorld(block);
            evaluate(startNanos, start, false);
            evaluate(endNanos, end, true);
            for (int i=0;i<graph.nodes.length;i++) for (int f=0;f<SignalGraph.CONTROL_FRAMES;f++)
                controls[i][f] = start[i] + (end[i]-start[i]) * f / SignalGraph.CONTROL_FRAMES;
            controlBlock = block;
        }
        return Math.floorMod(absoluteFrame, SignalGraph.CONTROL_FRAMES);
    }

    private double cycleAt(double nanos) { return state.anchorCycle() + (nanos-state.effectiveNanos()) / 1e9 * state.bpm()/240; }

    /** Moves each world node's smoothed value on to this block; a carried node already there keeps it. */
    private void settleWorld(long block) {
        for (int i = 0; i < graph.nodes.length; i++) {
            if (graph.nodes[i].type() != NodeType.WORLD || worldBlock[i] == block) continue;
            double[] p = nodeParams[i];
            double target = world.get((int) p[WORLD_SOURCE]);
            if (worldBlock[i] == Long.MIN_VALUE) worldFrom[i] = worldTo[i] = target;
            else {
                // Time can step back on a resync; carry on from the last value
                long gap = block > worldBlock[i] ? block - worldBlock[i] : 1;
                worldFrom[i] = settle(worldTo[i], target, p[WORLD_SMOOTH], gap - 1);
                worldTo[i] = settle(worldFrom[i], target, p[WORLD_SMOOTH], 1);
            }
            worldBlock[i] = block;
        }
    }

    private static double settle(double value, double target, double seconds, long blocks) {
        if (seconds <= 0) return target;
        return target + (value - target) * Math.exp(-blocks * BLOCK_SECONDS / seconds);
    }

    private void evaluate(double nanos, double[] values, boolean blockEnd) {
        double cycle = cycleAt(nanos);
        for (int i : graph.order) {
            Graph.Node n = graph.nodes[i];
            switch (n.type()) {
                case LFO -> {
                    double[] p = nodeParams[i];
                    double rate = p[LFO_RATE];
                    double phase = p[LFO_SYNC] == 1 ? cycle * rate
                            : (nanos - (n.birthNanos() == null ? 0 : n.birthNanos())) / 1e9 * rate;
                    phase -= Math.floor(phase);
                    values[i] = switch ((int) p[LFO_WAVE]) {
                        case 1 -> 1 - 4 * Math.abs(phase - .5);
                        case 2 -> phase < .5 ? 1 : -1;
                        case 3 -> 2 * phase - 1;
                        default -> Math.sin(2 * Math.PI * phase);
                    };
                }
                case STEP_SEQUENCE -> {
                    double[] p = nodeParams[i];
                    int steps = (int) p[STEP_COUNT];
                    int index = Math.floorMod((long) Math.floor(cycle * p[STEP_RATE] * steps), steps);
                    values[i] = p[STEP_VAL_0 + index];
                }
                case ATTENUVERTER -> {
                    double[] p = nodeParams[i];
                    values[i] = clamp(values[graph.controlInput[i]] * p[ATT_SCALE] + p[ATT_OFFSET], -20000, 20000);
                }
                case ENVELOPE -> {
                    Graph.Node trigger = graph.nodes[graph.controlInput[i]];
                    if (trigger.type() == NodeType.TRIGGER_RENDER) break; // filled by evaluateTriggerDrivenEnvelopes already
                    double[] p = nodeParams[i];
                    values[i] = periodicEnvelopeValue(cycle, p[ENV_WIDTH], p[ENV_RELEASE_AT],
                            p[ENV_ATTACK], p[ENV_DECAY], p[ENV_SUSTAIN], p[ENV_RELEASE]);
                }
                case WORLD -> values[i] = blockEnd ? worldTo[i] : worldFrom[i];
                default -> values[i] = 0;
            }
        }
    }

    /** One scan of each trigger-driven envelope's window covers both ramp endpoints, instead of
     *  the naive two full scans (one per evaluate() call) that would otherwise repeat identical
     *  per-entry work at two nearby cycle positions. Order-independent: unlike other node types,
     *  ENVELOPE never reads another node's values[], only its trigger's params/window, so this
     *  doesn't need to respect graph.order. */
    private void evaluateTriggerDrivenEnvelopes(double startNanos, double endNanos) {
        double startCycle = cycleAt(startNanos), endCycle = cycleAt(endNanos);
        for (int i = 0; i < graph.nodes.length; i++) {
            Graph.Node n = graph.nodes[i];
            if (n.type() != NodeType.ENVELOPE) continue;
            Graph.Node trigger = graph.nodes[graph.controlInput[i]];
            if (trigger.type() != NodeType.TRIGGER_RENDER) continue;
            double[] p = nodeParams[i];
            double attack = p[ENV_ATTACK], decay = p[ENV_DECAY], sustain = p[ENV_SUSTAIN];
            double release = p[ENV_RELEASE], mode = p[ENV_MODE];
            LookaheadScheduler.Window window = triggerWindowByNode[graph.controlInput[i]];
            double bestStart = 0, bestEnd = 0;
            if (window != null) for (int e = 0; e < window.size(); e++) {
                Event event = window.entry(e).event();
                double onset = event.whole().start();
                double releaseAt = mode == 0 ? attack+decay : event.whole().end() - onset;
                bestStart = Math.max(bestStart, envelopeValueAt(startCycle, onset, releaseAt, attack, decay, sustain, release));
                bestEnd = Math.max(bestEnd, envelopeValueAt(endCycle, onset, releaseAt, attack, decay, sustain, release));
            }
            start[i] = bestStart; end[i] = bestEnd;
        }
    }

    private static double held(double age,double attack,double decay,double sustain) {
        if (age < attack) return age/attack;
        if (age < attack+decay) return 1+(sustain-1)*(age-attack)/decay;
        return sustain;
    }

    /** The envelope rises to min(attack, gate end), then never increases. Among periodic
     *  voice ages, MAX must therefore occur at one of the two ages bracketing that peak.
     *  This includes older releasing voices without scanning thousands of periodic onsets. */
    private static double periodicEnvelopeValue(double cycle, double width, double releaseAt,
                                                 double attack, double decay, double sustain, double release) {
        double latest = Math.floor(cycle / width);
        double age = cycle - latest * width;
        double older = Math.max(0, Math.floor((Math.min(attack, releaseAt) - age) / width));
        return Math.max(envelopeValueAt(cycle, (latest - older) * width, releaseAt, attack, decay, sustain, release),
                envelopeValueAt(cycle, (latest - older - 1) * width, releaseAt, attack, decay, sustain, release));
    }

    /** Overlapping voices combine with MAX, not SUM: existing patches assume a single ENVELOPE's
     *  output stays in its documented 0-1 range, and summing would silently multiply that range
     *  with polyphony density. Shared by both the periodic (STEP_SEQUENCE) and window-scanned
     *  (TRIGGER_RENDER) envelope paths, since the release-curve math is identical either way. */
    private static double envelopeValueAt(double cycle, double onset, double releaseAt,
                                           double attack, double decay, double sustain, double release) {
        if (onset > cycle) return 0;
        double age = cycle - onset;
        if (age >= releaseAt + release) return 0;
        return age < releaseAt ? held(age,attack,decay,sustain)
                : (release == 0 ? 0 : held(releaseAt,attack,decay,sustain) * Math.max(0,1-(age-releaseAt)/release));
    }

    private static double p(Graph.Node n,String key,double fallback) { return SignalGraph.param(n,key,fallback); }
    private static double bounded(double v) { return Double.isFinite(v) ? clamp(v,-8,8) : 0; }
    private static double clamp(double v,double min,double max) { return Math.max(min,Math.min(max,v)); }

    public long reverbGuardHits() { return reverbGuardHits; }

    /** Tests only: nodes still ramping toward their settings. */
    int ramping() { return ramping; }

    /** Feedback through a delay decays toward subnormals, which are far slower to compute. */
    private double snapDelay(double v) {
        if (Math.abs(v) < Biquad.DENORMAL_SNAP && v != 0) { delaySnaps++; return 0; }
        return v;
    }

    /** Denormal snaps across delay writes, filter state and reverbs. */
    long snappedWrites() {
        long total = delaySnaps;
        for (int i = 0; i < graph.nodes.length; i++) {
            if (filtersLeft[i] != null) total += filtersLeft[i].snappedWrites() + filtersRight[i].snappedWrites();
            if (reverbs[i] != null) total += reverbs[i].snappedWrites();
        }
        return total;
    }

    /** Tests only: fills delay, filter and reverb state with one value. */
    void seedState(double value) {
        touched = true;
        for (int i = 0; i < graph.nodes.length; i++) {
            if (delayLeft[i] != null) { Arrays.fill(delayLeft[i], value); Arrays.fill(delayRight[i], value); }
            if (filtersLeft[i] != null) { filtersLeft[i].seedState(value); filtersRight[i].seedState(value); }
            if (reverbs[i] != null) reverbs[i].seedState(value);
        }
    }

    /** Tests only: every delay, filter and reverb state value is finite and none is subnormal. */
    boolean stateClean() {
        for (int i = 0; i < graph.nodes.length; i++) {
            if (delayLeft[i] != null) for (int f = 0; f < delayLeft[i].length; f++)
                if (!Biquad.clean(delayLeft[i][f]) || !Biquad.clean(delayRight[i][f])) return false;
            if (filtersLeft[i] != null && !(filtersLeft[i].stateClean() && filtersRight[i].stateClean())) return false;
            if (reverbs[i] != null && !reverbs[i].stateClean()) return false;
        }
        return true;
    }

    private double boundedReverb(double v) {
        if (!Double.isFinite(v)) {
            reverbGuardHits++;
            return 0;
        }
        if (v < -8.0) {
            reverbGuardHits++;
            return -8.0;
        }
        if (v > 8.0) {
            reverbGuardHits++;
            return 8.0;
        }
        return v;
    }
}
