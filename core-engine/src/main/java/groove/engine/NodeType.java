package groove.engine;

public enum NodeType {
    TONE,
    GENERATOR_SAMPLE,
    FAST,
    EUCLID,
    STACK,
    OUTPUT,
    LFO, ENVELOPE, ATTENUVERTER, STEP_SEQUENCE,
    AUDIO_RENDER, FILTER, DELAY, MIX_BUS;

    private static final java.util.List<Port> PATTERN_OUTPUT = java.util.List.of(new Port("out", PortType.PATTERN, 0, 128));
    private static final java.util.List<Port> PATTERN_INPUT = java.util.List.of(new Port("in", PortType.PATTERN, 1, 1));
    private static final java.util.List<Port> STACK_INPUT = java.util.List.of(new Port("in", PortType.PATTERN, 1, 16));
    private static final java.util.List<Port> NO_PORTS = java.util.List.of();
    private static final java.util.List<Port> MOD_OUTPUT = java.util.List.of(new Port("out", PortType.MOD_FLOAT, 0, 128));
    private static final java.util.List<Port> STEP_SEQUENCE_OUTPUT = java.util.List.of(
            new Port("out", PortType.MOD_FLOAT, 0, 128), new Port("trigger", PortType.TRIGGER, 0, 128));
    private static final java.util.List<Port> AUDIO_OUTPUT = java.util.List.of(new Port("out", PortType.AUDIO, 0, 128));
    private static final java.util.List<Port> OUTPUT_INPUT = java.util.List.of(
            new Port("in", PortType.PATTERN, 0, 1), new Port("audio", PortType.AUDIO, 0, 1));
    private static final java.util.List<Port> ENVELOPE_INPUT = java.util.List.of(new Port("trigger", PortType.TRIGGER, 1, 1));
    private static final java.util.List<Port> ATTENUVERTER_INPUT = java.util.List.of(new Port("in", PortType.MOD_FLOAT, 1, 1));
    private static final java.util.List<Port> FILTER_INPUT = java.util.List.of(
            new Port("in", PortType.AUDIO, 1, 1), new Port("cutoff", PortType.MOD_FLOAT, 0, 1));
    private static final java.util.List<Port> DELAY_INPUT = java.util.List.of(new Port("in", PortType.AUDIO, 1, 1));
    private static final java.util.List<Port> MIX_BUS_INPUT = java.util.List.of(
            new Port("in", PortType.AUDIO, 1, 16), new Port("gain", PortType.MOD_FLOAT, 0, 1));

    /** Existing generators emit musical events, not rendered audio. */
    public java.util.List<Port> outputPorts() {
        return switch (this) {
            case OUTPUT -> NO_PORTS;
            case LFO, ENVELOPE, ATTENUVERTER -> MOD_OUTPUT;
            case STEP_SEQUENCE -> STEP_SEQUENCE_OUTPUT;
            case AUDIO_RENDER, FILTER, DELAY, MIX_BUS -> AUDIO_OUTPUT;
            default -> PATTERN_OUTPUT;
        };
    }

    public java.util.List<Port> inputPorts() {
        return switch (this) {
            case TONE, GENERATOR_SAMPLE -> NO_PORTS;
            case STACK -> STACK_INPUT;
            case FAST, EUCLID, AUDIO_RENDER -> PATTERN_INPUT;
            case OUTPUT -> OUTPUT_INPUT;
            case LFO, STEP_SEQUENCE -> NO_PORTS;
            case ENVELOPE -> ENVELOPE_INPUT;
            case ATTENUVERTER -> ATTENUVERTER_INPUT;
            case FILTER -> FILTER_INPUT;
            case DELAY -> DELAY_INPUT;
            case MIX_BUS -> MIX_BUS_INPUT;
        };
    }

    public boolean isSignalNode() {
        return switch (this) {
            case LFO, ENVELOPE, ATTENUVERTER, STEP_SEQUENCE, AUDIO_RENDER, FILTER, DELAY, MIX_BUS -> true;
            default -> false;
        };
    }

    public Port inputPort(String name) {
        return inputPorts().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    public Port outputPort(String name) {
        return outputPorts().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** Stable ASCII stem for generated editor IDs, independent of system locale. */
    public String idStem() { return name().toLowerCase(java.util.Locale.ROOT); }
}
