package groove.engine;

public enum NodeType {
    TONE,
    GENERATOR_SAMPLE,
    FAST,
    EUCLID,
    STACK,
    OUTPUT;

    /** Stable ASCII stem for generated editor IDs, independent of system locale. */
    public String idStem() { return name().toLowerCase(java.util.Locale.ROOT); }
}
