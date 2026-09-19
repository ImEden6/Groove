package groove.engine;

/**
 * Direct Form I biquad, RBJ Audio-EQ-Cookbook filters with configurable resonance. Mutable, single-voice-owned; reset() on note attack so state
 * never bleeds between notes.
 */
public final class Biquad {
    public enum Mode { LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH }
    public static final double DEFAULT_Q = 0.70710678118654752; // 1/sqrt(2)
    private double b0 = 1, b1, b2, a1, a2;
    private double x1, x2, y1, y2;
    /** Denormal snaps: output below 1e-15 or stored input below {@link #DENORMAL_SNAP}. */
    private long snappedWrites;
    /** Stored state below this is zeroed; subnormal doubles run 10-100x slower on x86 and Java cannot enable flush-to-zero. */
    static final double DENORMAL_SNAP = 1e-30;

    public void setLowPass(double cutoffHz, double sampleRate) {
        setLowPass(cutoffHz, DEFAULT_Q, sampleRate);
    }

    public void setLowPass(double cutoffHz, double resonanceQ, double sampleRate) {
        set(Mode.LOW_PASS, cutoffHz, resonanceQ, sampleRate);
    }

    /** RBJ coefficients; band-pass has unity peak gain. https://www.w3.org/TR/audio-eq-cookbook/ */
    public void set(Mode mode, double cutoffHz, double resonanceQ, double sampleRate) {
        if (mode == null || !Double.isFinite(sampleRate) || sampleRate <= 40
                || !Double.isFinite(resonanceQ) || resonanceQ < .1 || resonanceQ > 20)
            throw new IllegalArgumentException("Invalid filter settings");
        double nyquistCeiling = sampleRate * 0.499;
        if (mode == Mode.LOW_PASS && (!Double.isFinite(cutoffHz) || cutoffHz >= nyquistCeiling)) {
            b0 = 1; b1 = 0; b2 = 0; a1 = 0; a2 = 0; // bypass: pass-through
            return;
        }
        if (!Double.isFinite(cutoffHz)) throw new IllegalArgumentException("Invalid filter cutoff");
        double clamped = Math.min(nyquistCeiling, Math.max(20, cutoffHz));
        double omega = 2 * Math.PI * clamped / sampleRate;
        double sinOmega = Math.sin(omega), cosOmega = Math.cos(omega);
        double alpha = sinOmega / (2 * resonanceQ);
        double a0 = 1 + alpha;
        double rawB0 = (1 - cosOmega) / 2, rawB1 = 1 - cosOmega, rawB2 = (1 - cosOmega) / 2;
        switch (mode) {
            case HIGH_PASS -> { rawB0 = (1 + cosOmega) / 2; rawB1 = -(1 + cosOmega); rawB2 = rawB0; }
            case BAND_PASS -> { rawB0 = alpha; rawB1 = 0; rawB2 = -alpha; }
            case NOTCH -> { rawB0 = 1; rawB1 = -2 * cosOmega; rawB2 = 1; }
            default -> { }
        }
        double rawA1 = -2 * cosOmega, rawA2 = 1 - alpha;
        b0 = rawB0 / a0; b1 = rawB1 / a0; b2 = rawB2 / a0;
        a1 = rawA1 / a0; a2 = rawA2 / a0;
    }

    public double process(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        if (!Double.isFinite(y)) { reset(); return 0; }
        if (Math.abs(y) < 1e-15) { if (y != 0) snappedWrites++; y = 0; }
        if (Math.abs(x) < DENORMAL_SNAP && x != 0) { snappedWrites++; x = 0; }
        x2 = x1; x1 = x;
        y2 = y1; y1 = y;
        return y;
    }

    public void reset() { x1 = 0; x2 = 0; y1 = 0; y2 = 0; }

    /** Continues {@code other}'s history under this filter's own coefficients. */
    void copyStateFrom(Biquad other) { x1 = other.x1; x2 = other.x2; y1 = other.y1; y2 = other.y2; }

    long snappedWrites() { return snappedWrites; }
    /** Tests only: |H(e^{jω})| of the current coefficients, ω in radians per sample. */
    double responseMagnitude(double omega) {
        double c1 = Math.cos(omega), s1 = Math.sin(omega), c2 = Math.cos(2 * omega), s2 = Math.sin(2 * omega);
        double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2);
        double dr = 1 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
        return Math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
    }
    /** Tests only: fills the filter state with one value. */
    void seedState(double value) { x1 = x2 = y1 = y2 = value; }
    /** Tests only: every state value is finite and none is subnormal. */
    boolean stateClean() { return clean(x1) && clean(x2) && clean(y1) && clean(y2); }

    static boolean clean(double v) { return Double.isFinite(v) && !(v != 0 && Math.abs(v) < Double.MIN_NORMAL); }
}
