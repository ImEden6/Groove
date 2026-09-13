package groove.engine;

public final class NodeParam {
    public static final String FREQUENCY = "frequency";
    public static final String GAIN = "gain";
    public static final String PAN = "pan";
    public static final String WAVE = "wave";
    public static final String CUTOFF_HZ = "cutoffHz";
    public static final String RESONANCE_Q = "resonanceQ";
    public static final String PITCH_RATIO = "pitchRatio";
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

    private NodeParam() {}
}
