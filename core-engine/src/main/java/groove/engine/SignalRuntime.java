package groove.engine;

import java.util.Arrays;

/** Renderer-private preallocated stereo DSP and absolute-time 64-frame control ramps. */
public final class SignalRuntime {
    private final SignalGraph graph;
    private final SessionState state;
    private final double[] left, right, start, end;
    private final double[][] controls, delayLeft, delayRight;
    private final int[] cursors;
    private final Biquad[] filtersLeft, filtersRight;
    private final boolean[] filterCoefficientsSet;
    private long controlBlock = Long.MIN_VALUE;

    SignalRuntime(SignalGraph graph, SessionState state) {
        this.graph = graph; this.state = state;
        int size = graph.nodes.length;
        left = new double[size]; right = new double[size]; start = new double[size]; end = new double[size];
        controls = new double[size][SignalGraph.CONTROL_FRAMES];
        delayLeft = new double[size][]; delayRight = new double[size][]; cursors = new int[size];
        filtersLeft = new Biquad[size]; filtersRight = new Biquad[size]; filterCoefficientsSet = new boolean[size];
        for (int i=0;i<size;i++) {
            Graph.Node n = graph.nodes[i];
            if (n.type() == NodeType.DELAY) {
                int frames = (int)p(n,NodeParam.FRAMES,64);
                delayLeft[i] = new double[frames]; delayRight[i] = new double[frames];
            }
            if (n.type() == NodeType.FILTER) { filtersLeft[i] = new Biquad(); filtersRight[i] = new Biquad(); }
        }
    }

    public void reset() {
        controlBlock = Long.MIN_VALUE;
        Arrays.fill(cursors,0);
        for (int i=0;i<graph.nodes.length;i++) {
            if (delayLeft[i] != null) { Arrays.fill(delayLeft[i],0); Arrays.fill(delayRight[i],0); }
            if (filtersLeft[i] != null) { filtersLeft[i].reset(); filtersRight[i].reset(); filterCoefficientsSet[i] = false; }
        }
    }

    /** In-place stereo frame; call sequentially at 48 kHz. No graph queries or allocation. */
    public void process(double[] stereo, long serverNanos) {
        if (graph.sourceCount() != 1) throw new IllegalArgumentException("Use independent stereo inputs for multiple audio sources");
        int source = graph.sourceNode(0);
        left[source] = stereo[0]; right[source] = stereo[1];
        processRouting(stereo, serverNanos);
    }

    /** Inputs follow SignalGraph.sourceNodeId order, not node-array positions. Buffers belong to the caller. */
    public void process(double[][] sources, double[] stereo, long serverNanos) {
        if (sources.length != graph.sourceCount() || stereo.length < 2) throw new IllegalArgumentException("Wrong source/output buffer count");
        for (int s = 0; s < sources.length; s++) {
            if (sources[s] == null || sources[s].length < 2) throw new IllegalArgumentException("Source requires a stereo frame");
            int node = graph.sourceNode(s);
            left[node] = sources[s][0]; right[node] = sources[s][1];
        }
        processRouting(stereo, serverNanos);
    }

    private void processRouting(double[] stereo, long serverNanos) {
        int frame = prepareControls(serverNanos);
        // Read all old delay cells before evaluating or writing any feedback input.
        for (int i=0;i<graph.nodes.length;i++) if (delayLeft[i] != null) {
            left[i] = delayLeft[i][cursors[i]]; right[i] = delayRight[i][cursors[i]];
        }
        for (int i : graph.order) {
            Graph.Node n = graph.nodes[i];
            int[] inputs = graph.audioInputs[i];
            int mod = graph.controlInput[i];
            switch (n.type()) {
                case AUDIO_RENDER -> { /* Filled by source-to-node mapping before routing. */ }
                case MIX_BUS -> {
                    double gain = mod < 0 ? p(n,NodeParam.GAIN,1) : clamp(controls[mod][frame],0,1);
                    double l=0,r=0;
                    for (int source : inputs) { l += left[source]; r += right[source]; }
                    left[i] = bounded(l*gain); right[i] = bounded(r*gain);
                }
                case FILTER -> {
                    if (mod >= 0 || !filterCoefficientsSet[i]) {
                        double cutoff = mod < 0 ? p(n,NodeParam.CUTOFF_HZ,20000) : clamp(controls[mod][frame],20,20000);
                        double q = p(n,NodeParam.RESONANCE_Q,Biquad.DEFAULT_Q);
                        filtersLeft[i].setLowPass(cutoff,q,LiveRenderer.SAMPLE_RATE);
                        filtersRight[i].setLowPass(cutoff,q,LiveRenderer.SAMPLE_RATE);
                        filterCoefficientsSet[i] = true;
                    }
                    left[i] = bounded(filtersLeft[i].process(left[inputs[0]]));
                    right[i] = bounded(filtersRight[i].process(right[inputs[0]]));
                }
                case OUTPUT -> { left[i] = left[inputs[0]]; right[i] = right[inputs[0]]; }
                default -> { }
            }
        }
        for (int i=0;i<graph.nodes.length;i++) if (delayLeft[i] != null) {
            int source = graph.audioInputs[i][0], cursor = cursors[i];
            delayLeft[i][cursor] = bounded(left[source]); delayRight[i][cursor] = bounded(right[source]);
            cursors[i] = (cursor+1) % delayLeft[i].length;
        }
        stereo[0] = left[graph.output]; stereo[1] = right[graph.output];
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
            evaluate(block * (double)SignalGraph.CONTROL_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE, start);
            evaluate((block+1) * (double)SignalGraph.CONTROL_FRAMES * 1e9 / LiveRenderer.SAMPLE_RATE, end);
            for (int i=0;i<graph.nodes.length;i++) for (int f=0;f<SignalGraph.CONTROL_FRAMES;f++)
                controls[i][f] = start[i] + (end[i]-start[i]) * f / SignalGraph.CONTROL_FRAMES;
            controlBlock = block;
        }
        return Math.floorMod(absoluteFrame, SignalGraph.CONTROL_FRAMES);
    }

    private void evaluate(double nanos, double[] values) {
        double cycle = state.anchorCycle() + (nanos-state.effectiveNanos()) / 1e9 * state.bpm()/240;
        for (int i : graph.order) {
            Graph.Node n = graph.nodes[i];
            switch (n.type()) {
                case LFO -> {
                    double phase = p(n,NodeParam.SYNC,0) == 1 ? cycle*p(n,NodeParam.RATE,1)
                            : (nanos-(n.birthNanos() == null ? 0 : n.birthNanos()))/1e9*p(n,NodeParam.RATE,1);
                    phase -= Math.floor(phase);
                    values[i] = switch ((int)p(n,NodeParam.WAVE,0)) {
                        case 1 -> 1-4*Math.abs(phase-.5);
                        case 2 -> phase < .5 ? 1 : -1;
                        case 3 -> 2*phase-1;
                        default -> Math.sin(2*Math.PI*phase);
                    };
                }
                case STEP_SEQUENCE -> {
                    int steps = (int)p(n,NodeParam.STEPS,4);
                    int index = Math.floorMod((long)Math.floor(cycle*p(n,NodeParam.RATE,1)*steps), steps);
                    // Avoid constructing parameter keys in the audio callback.
                    values[i] = p(n, NodeParam.VALUES[index],0);
                }
                case ATTENUVERTER -> values[i] = clamp(values[graph.controlInput[i]]*p(n,NodeParam.SCALE,1)+p(n,NodeParam.OFFSET,0),-20000,20000);
                case ENVELOPE -> {
                    Graph.Node trigger = graph.nodes[graph.controlInput[i]];
                    double width = 1/(p(trigger,NodeParam.STEPS,4)*p(trigger,NodeParam.RATE,1));
                    double wholeStart = Math.floor(cycle/width)*width;
                    double age = Math.max(0,cycle-wholeStart);
                    double attack = p(n,NodeParam.ATTACK,.01), decay = p(n,NodeParam.DECAY,.1), sustain = p(n,NodeParam.SUSTAIN,.5);
                    double releaseAt = p(n,NodeParam.MODE,0) == 0 ? attack+decay : width*p(trigger,NodeParam.GATE,.5);
                    double release = p(n,NodeParam.RELEASE,.1);
                    values[i] = age < releaseAt ? held(age,attack,decay,sustain)
                            : held(releaseAt,attack,decay,sustain)*(release == 0 ? 0 : Math.max(0,1-(age-releaseAt)/release));
                }
                default -> values[i] = 0;
            }
        }
    }
    private static double held(double age,double attack,double decay,double sustain) {
        if (age < attack) return age/attack;
        if (age < attack+decay) return 1+(sustain-1)*(age-attack)/decay;
        return sustain;
    }
    private static double p(Graph.Node n,String key,double fallback) { return SignalGraph.param(n,key,fallback); }
    private static double bounded(double v) { return Double.isFinite(v) ? clamp(v,-8,8) : 0; }
    private static double clamp(double v,double min,double max) { return Math.max(min,Math.min(max,v)); }
}
