package groove.engine;

/** Immutable voice settings. Frequency is in Hz; pan is -1 (left) to +1. cutoffHz
 *  drives the voice's low-pass filter; upper-bound/Nyquist enforcement happens in
 *  Biquad.setLowPass, since Tone doesn't know the render sample rate. */
public record Tone(Wave wave, double frequency, double gain, double pan, double cutoffHz) {
    public enum Wave { SINE, SAW }
    public Tone {
        if (wave == null || !Double.isFinite(frequency) || frequency <= 0
                || !Double.isFinite(gain) || gain < 0 || gain > 1
                || !Double.isFinite(pan) || Math.abs(pan) > 1
                || !Double.isFinite(cutoffHz) || cutoffHz <= 0)
            throw new IllegalArgumentException("Invalid tone settings");
    }
}
