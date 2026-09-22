package groove.engine;

public final class NodeParam {
    public static final String FREQUENCY = "frequency";
    public static final String ROOT = "root";
    public static final String SEMITONES = "semitones";
    public static final String CHORD = "chord";
    public static final String INVERSION = "inversion";
    public static final String GAIN = "gain";
    public static final String PAN = "pan";
    public static final String WAVE = "wave";
    public static final String CUTOFF_HZ = "cutoffHz";
    public static final String RESONANCE_Q = "resonanceQ";
    public static final String PULSE_WIDTH = "pulseWidth";
    public static final String PITCH_RATIO = "pitchRatio";
    public static final String START_FRAME = "startFrame";
    public static final String END_FRAME = "endFrame";
    public static final String SLICES = "slices";
    public static final String INDEX = "index";
    public static final String REVERSE = "reverse";
    public static final String LOOP = "loop";
    public static final String LOOP_START = "loopStart";
    public static final String LOOP_END = "loopEnd";
    public static final String LOOP_FADE_MS = "loopFadeMs";
    public static final String FACTOR = "factor";
    public static final String STEPS = "steps";
    public static final String PULSES = "pulses";
    public static final String ROTATION = "rotation";
    public static final String RATE = "rate";
    public static final String SYNC = "sync";
    public static final String MODE = "mode";
    public static final String GATE = "gate";
    public static final String ATTACK = "attack";
    public static final String DECAY = "decay";
    public static final String RELEASE = "release";
    public static final String SUSTAIN = "sustain";
    public static final String SCALE = "scale";
    public static final String OFFSET = "offset";
    public static final String FRAMES = "frames";
    /** value0..value7: STEP_SEQUENCE step levels, keyed by index. */
    public static final String[] VALUES = {"value0","value1","value2","value3","value4","value5","value6","value7"};

    public static final String CHANCE = "chance";
    public static final String SEED = "seed";
    public static final String STEPS_PER_CYCLE = "stepsPerCycle";
    public static final String SUBDIVISION = "subdivision";
    public static final String AMOUNT = "amount";
    public static final String DIVISION = "division";
    /** DELAY: 1 exempts every feedback loop through this delay from the loop-gain limit. */
    public static final String FREE_RUN = "freeRun";
    public static final String DECAY_SECONDS = "decaySeconds";
    public static final String DAMPING_HZ = "dampingHz";
    public static final String BANDWIDTH_HZ = "bandwidthHz";
    public static final String PRE_DELAY_MS = "preDelayMs";
    public static final String SOURCE = "source";
    public static final String SMOOTH = "smooth";
    public static final String LOW = "low";
    public static final String HIGH = "high";

    private NodeParam() {}
}
