package groove.engine;

/** Signal domains are deliberately distinct; connections never insert implicit conversions. */
public enum PortType {
    PATTERN, MOD_FLOAT, TRIGGER, AUDIO
}
