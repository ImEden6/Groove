package groove.engine;

/**
 * Direct Form I biquad, RBJ Audio-EQ-Cookbook low-pass with fixed Q (Butterworth,
 * no resonance peak). Mutable, single-voice-owned; reset() on note attack so state
 * never bleeds between notes.
 */
public final class Biquad {
    private static final double Q = 0.70710678118654752; // 1/sqrt(2)
    private double b0 = 1, b1, b2, a1, a2;
    private double x1, x2, y1, y2;

    public void setLowPass(double cutoffHz, double sampleRate) {
        double nyquistCeiling = sampleRate * 0.499;
        if (!Double.isFinite(cutoffHz) || cutoffHz >= nyquistCeiling) {
            b0 = 1; b1 = 0; b2 = 0; a1 = 0; a2 = 0; // bypass: pass-through
            return;
        }
        double clamped = Math.max(20, cutoffHz);
        double omega = 2 * Math.PI * clamped / sampleRate;
        double sinOmega = Math.sin(omega), cosOmega = Math.cos(omega);
        double alpha = sinOmega / (2 * Q);
        double a0 = 1 + alpha;
        double rawB0 = (1 - cosOmega) / 2, rawB1 = 1 - cosOmega, rawB2 = (1 - cosOmega) / 2;
        double rawA1 = -2 * cosOmega, rawA2 = 1 - alpha;
        b0 = rawB0 / a0; b1 = rawB1 / a0; b2 = rawB2 / a0;
        a1 = rawA1 / a0; a2 = rawA2 / a0;
    }

    public double process(double x) {
        double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x;
        y2 = y1; y1 = y;
        return y;
    }

    public void reset() { x1 = 0; x2 = 0; y1 = 0; y2 = 0; }
}
